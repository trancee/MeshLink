//! Noise `XX`, `XXpsk3`, `XXhfs` and `XXhfs+psk3` link handshakes (`docs/spec/link.md`, "Handshake"),
//! and `IK` and `IKhfs` first messages (`docs/spec/end-to-end.md`, "First messages").
//!
//! snow's own ML-KEM-768 draws from the system random generator, so its transcripts can't be
//! reproduced. [`FixedKem`] runs the same FIPS 203 algorithms from fixed inputs instead: key
//! generation from a 64 B seed `d ‖ z` and encapsulation from a 32 B message `m`.

use ml_kem::kem::{Decapsulate, KeyExport};
use ml_kem::{B32, Ciphertext, DecapsulationKey, EncapsulationKey, Key, MlKem768, Seed};
use snow::params::{CipherChoice, DHChoice, HashChoice, KemChoice};
use snow::resolvers::{CryptoResolver, DefaultResolver};
use snow::types::{Cipher, Dh, Hash, Kem, Random};
use snow::{Builder, Error, HandshakeState, TransportState};

const PUBLIC_KEY_LENGTH: usize = 1_184;
const CIPHERTEXT_LENGTH: usize = 1_088;
const SHARED_SECRET_LENGTH: usize = 32;
const MAX_MESSAGE: usize = 65_535;

/// The fixed ML-KEM input of one side: the initiator's key generation seed or the responder's
/// encapsulation message.
#[derive(Clone, Copy)]
pub enum KemInput {
    Seed([u8; 64]),
    Message([u8; 32]),
}

impl KemInput {
    #[must_use]
    pub fn bytes(&self) -> Vec<u8> {
        match self {
            Self::Seed(seed) => seed.to_vec(),
            Self::Message(m) => m.to_vec(),
        }
    }
}

pub struct FixedKem {
    input: KemInput,
    decapsulation_key: Option<DecapsulationKey<MlKem768>>,
    public_key: Vec<u8>,
}

impl Kem for FixedKem {
    fn name(&self) -> &'static str {
        "MLKEM768"
    }

    fn pub_len(&self) -> usize {
        PUBLIC_KEY_LENGTH
    }

    fn ciphertext_len(&self) -> usize {
        CIPHERTEXT_LENGTH
    }

    fn shared_secret_len(&self) -> usize {
        SHARED_SECRET_LENGTH
    }

    fn generate(&mut self, _rng: &mut dyn Random) {
        let KemInput::Seed(seed) = self.input else {
            panic!("only the initiator generates a KEM key")
        };
        let key = DecapsulationKey::<MlKem768>::from_seed(Seed::from(seed));
        self.public_key = key.encapsulation_key().to_bytes().to_vec();
        self.decapsulation_key = Some(key);
    }

    fn pubkey(&self) -> &[u8] {
        &self.public_key
    }

    fn encapsulate(
        &self,
        pubkey: &[u8],
        shared_secret_out: &mut [u8],
        ciphertext_out: &mut [u8],
    ) -> Result<(usize, usize), Error> {
        let KemInput::Message(m) = self.input else {
            panic!("only the responder encapsulates")
        };
        let key = Key::<EncapsulationKey<MlKem768>>::try_from(pubkey).map_err(|_| Error::Kem)?;
        let encapsulation_key = EncapsulationKey::<MlKem768>::new(&key).map_err(|_| Error::Kem)?;
        let (ciphertext, shared_secret) = encapsulation_key.encapsulate_deterministic(&B32::from(m));
        shared_secret_out[..SHARED_SECRET_LENGTH].copy_from_slice(&shared_secret);
        ciphertext_out[..CIPHERTEXT_LENGTH].copy_from_slice(&ciphertext);
        Ok((SHARED_SECRET_LENGTH, CIPHERTEXT_LENGTH))
    }

    fn decapsulate(&self, ciphertext: &[u8], shared_secret_out: &mut [u8]) -> Result<usize, Error> {
        let key = self.decapsulation_key.as_ref().ok_or(Error::Kem)?;
        let ciphertext = Ciphertext::<MlKem768>::try_from(ciphertext).map_err(|_| Error::Kem)?;
        shared_secret_out[..SHARED_SECRET_LENGTH].copy_from_slice(&key.decapsulate(&ciphertext));
        Ok(SHARED_SECRET_LENGTH)
    }
}

/// snow's default resolver with [`FixedKem`] as the KEM, or no KEM for a pattern without one.
struct FixedResolver(Option<KemInput>);

impl CryptoResolver for FixedResolver {
    fn resolve_rng(&self) -> Option<Box<dyn Random>> {
        DefaultResolver.resolve_rng()
    }

    fn resolve_dh(&self, choice: &DHChoice) -> Option<Box<dyn Dh>> {
        DefaultResolver.resolve_dh(choice)
    }

    fn resolve_hash(&self, choice: &HashChoice) -> Option<Box<dyn Hash>> {
        DefaultResolver.resolve_hash(choice)
    }

    fn resolve_cipher(&self, choice: &CipherChoice) -> Option<Box<dyn Cipher>> {
        DefaultResolver.resolve_cipher(choice)
    }

    fn resolve_kem(&self, choice: &KemChoice) -> Option<Box<dyn Kem>> {
        let input = self.0?;
        matches!(choice, KemChoice::MlKem768).then(|| {
            Box::new(FixedKem {
                input,
                decapsulation_key: None,
                public_key: Vec::new(),
            }) as Box<dyn Kem>
        })
    }
}

/// Everything one side feeds into its handshake.
pub struct Side {
    pub static_private: [u8; 32],
    pub ephemeral_private: [u8; 32],
    pub kem: Option<KemInput>,
    pub prologue: Vec<u8>,
    pub psk: Option<[u8; 32]>,
}

impl Side {
    fn build(&self, protocol: &str, initiator: bool) -> HandshakeState {
        self.build_knowing(protocol, initiator, None)
    }

    /// The side's handshake state; an `IK` initiator holds `responder_key` as the responder's
    /// static key.
    fn build_knowing(&self, protocol: &str, initiator: bool, responder_key: Option<&[u8]>) -> HandshakeState {
        let mut builder = Builder::with_resolver(
            protocol.parse().expect("protocol name"),
            Box::new(FixedResolver(self.kem)),
        )
        .local_private_key(&self.static_private)
        .expect("static key")
        .fixed_ephemeral_key_for_testing_only(&self.ephemeral_private)
        .prologue(&self.prologue)
        .expect("prologue");
        if let Some(psk) = &self.psk {
            builder = builder.psk(3, psk).expect("psk");
        }
        if let Some(key) = responder_key {
            builder = builder.remote_public_key(key).expect("responder key");
        }
        if initiator {
            builder.build_initiator()
        } else {
            builder.build_responder()
        }
        .expect("handshake state")
    }
}

/// One transport message after the handshake.
pub struct Transport {
    pub from_initiator: bool,
    pub plaintext: Vec<u8>,
    pub ciphertext: Vec<u8>,
}

/// A run up to its end or its first rejected message.
pub struct Transcript {
    /// Every message as sent, including a tampered or rejected last one.
    pub messages: Vec<Vec<u8>>,
    pub outcome: Result<Completed, Rejected>,
}

pub struct Completed {
    pub handshake_hash: Vec<u8>,
    pub transport: Vec<Transport>,
}

/// The step (1 to 3) whose message the reader rejected.
#[derive(Debug, PartialEq, Eq)]
pub struct Rejected(pub usize);

/// Runs the three messages, flipping `tamper = (step, byte)` in transit: the messages as
/// delivered, and both finished sides or the step whose message the reader rejected.
fn exchange(
    protocol: &str,
    initiator: &Side,
    responder: &Side,
    payloads: &[Vec<u8>; 3],
    tamper: Option<(usize, usize)>,
) -> (Vec<Vec<u8>>, Result<[HandshakeState; 2], Rejected>) {
    let mut sides = [initiator.build(protocol, true), responder.build(protocol, false)];
    let mut messages = Vec::new();
    for (index, payload) in payloads.iter().enumerate() {
        let (writer, reader) = if index % 2 == 0 { (0, 1) } else { (1, 0) };
        let mut message = vec![0; MAX_MESSAGE];
        let length = sides[writer].write_message(payload, &mut message).expect("write");
        message.truncate(length);
        if let Some((step, byte)) = tamper.filter(|(step, _)| *step == index + 1) {
            message = crate::spec::flip(&message, byte);
            debug_assert_eq!(step, index + 1);
        }
        messages.push(message.clone());
        let mut read = vec![0; MAX_MESSAGE];
        match sides[reader].read_message(&message, &mut read) {
            Ok(n) => assert_eq!(&read[..n], payload.as_slice(), "payload {}", index + 1),
            Err(_) => return (messages, Err(Rejected(index + 1))),
        }
    }
    (messages, Ok(sides))
}

/// The initiator's and the responder's transport state after an untampered handshake.
#[must_use]
pub fn transports(protocol: &str, initiator: &Side, responder: &Side, payloads: &[Vec<u8>; 3]) -> [TransportState; 2] {
    let (_, sides) = exchange(protocol, initiator, responder, payloads, None);
    sides
        .expect("handshake")
        .map(|side| side.into_transport_mode().expect("transport"))
}

/// Runs the three messages, flipping `tamper = (step, byte)` in transit, then one transport
/// message each way.
#[must_use]
pub fn run(
    protocol: &str,
    initiator: &Side,
    responder: &Side,
    payloads: &[Vec<u8>; 3],
    tamper: Option<(usize, usize)>,
) -> Transcript {
    let (messages, sides) = exchange(protocol, initiator, responder, payloads, tamper);
    let sides = match sides {
        Ok(sides) => sides,
        Err(rejected) => {
            return Transcript {
                messages,
                outcome: Err(rejected),
            };
        }
    };
    let [i, r] = sides;
    assert_eq!(
        i.get_handshake_hash(),
        r.get_handshake_hash(),
        "handshake hashes differ"
    );
    let handshake_hash = i.get_handshake_hash().to_vec();
    let mut i = i.into_transport_mode().expect("initiator transport");
    let mut r = r.into_transport_mode().expect("responder transport");
    let mut transport = Vec::new();
    for (from_initiator, plaintext) in [(true, b"ping".to_vec()), (false, b"pong".to_vec())] {
        let (writer, reader) = if from_initiator {
            (&mut i, &mut r)
        } else {
            (&mut r, &mut i)
        };
        let mut ciphertext = vec![0; plaintext.len() + 16];
        writer
            .write_message(&plaintext, &mut ciphertext)
            .expect("transport write");
        let mut read = vec![0; plaintext.len()];
        reader.read_message(&ciphertext, &mut read).expect("transport read");
        assert_eq!(read, plaintext);
        transport.push(Transport {
            from_initiator,
            plaintext,
            ciphertext,
        });
    }
    Transcript {
        messages,
        outcome: Ok(Completed {
            handshake_hash,
            transport,
        }),
    }
}

/// `IK` or `IKhfs` message 1: the initiator, holding `responder_key`, writes `payload`, and
/// `tamper` flips that byte in transit. The message as delivered and the payload the responder
/// reads from it, or `None` if the responder rejects it.
#[must_use]
pub fn first_message(
    protocol: &str,
    initiator: &Side,
    responder_key: &[u8; 32],
    responder: &Side,
    payload: &[u8],
    tamper: Option<usize>,
) -> (Vec<u8>, Option<Vec<u8>>) {
    let mut writer = initiator.build_knowing(protocol, true, Some(responder_key));
    let mut message = vec![0; MAX_MESSAGE];
    let length = writer.write_message(payload, &mut message).expect("write");
    message.truncate(length);
    if let Some(byte) = tamper {
        message = crate::spec::flip(&message, byte);
    }
    let mut read = vec![0; MAX_MESSAGE];
    let payload = responder
        .build(protocol, false)
        .read_message(&message, &mut read)
        .ok()
        .map(|n| read[..n].to_vec());
    (message, payload)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::spec::{seed, seed64};

    const XXHFS: &str = "Noise_XXhfs_25519+MLKEM768_ChaChaPoly_SHA256";

    fn side(label: &str, kem: KemInput) -> Side {
        Side {
            static_private: seed(label),
            ephemeral_private: seed(&format!("{label} e")),
            kem: Some(kem),
            prologue: b"p".to_vec(),
            psk: None,
        }
    }

    fn pair() -> (Side, Side) {
        (
            side("i", KemInput::Seed(seed64("i kem"))),
            side("r", KemInput::Message(seed("r kem"))),
        )
    }

    #[test]
    fn fixed_inputs_give_the_same_transcript_twice() {
        let (i, r) = pair();
        let payloads = [Vec::new(), b"two".to_vec(), b"three".to_vec()];
        let first = run(XXHFS, &i, &r, &payloads, None);
        let second = run(XXHFS, &i, &r, &payloads, None);
        assert_eq!(first.messages, second.messages);
        assert_eq!(
            first.messages.iter().map(Vec::len).collect::<Vec<_>>(),
            [1_216, 32 + 1_104 + 48 + 3 + 16, 48 + 5 + 16]
        );
    }

    /// snow's own ML-KEM-768 encapsulates with system randomness; the fixed initiator decapsulates
    /// it, so `FixedKem` speaks the same KEM as snow.
    #[test]
    fn fixed_kem_interoperates_with_snow_ml_kem() {
        let (i, _) = pair();
        let responder_key = seed("r");
        let responder = Builder::new(XXHFS.parse().expect("name"))
            .local_private_key(&responder_key)
            .expect("key")
            .prologue(b"p")
            .expect("prologue");
        let mut sides = [i.build(XXHFS, true), responder.build_responder().expect("responder")];
        let (mut message, mut read) = (vec![0; MAX_MESSAGE], vec![0; MAX_MESSAGE]);
        for writer in [0, 1, 0] {
            let n = sides[writer].write_message(b"", &mut message).expect("write");
            sides[1 - writer].read_message(&message[..n], &mut read).expect("read");
        }
        assert_eq!(sides[0].get_handshake_hash(), sides[1].get_handshake_hash());
    }

    #[test]
    fn a_flipped_kem_ciphertext_bit_fails_message_2() {
        let (i, r) = pair();
        let payloads = [Vec::new(), Vec::new(), Vec::new()];
        assert_eq!(
            run(XXHFS, &i, &r, &payloads, Some((2, 100))).outcome.err(),
            Some(Rejected(2))
        );
    }
}
