//! The post-quantum first-message seal (`docs/spec/end-to-end.md`, "First message"): HPKE base
//! mode with X-Wing, HKDF-SHA256 and ChaCha20Poly1305, `aad` empty.

use core::convert::Infallible;
use hpke::aead::AeadCtxS;
use hpke::aead::ChaCha20Poly1305;
use hpke::kdf::HkdfSha256;
use hpke::kem::XWing;
use hpke::rand_core::{TryCryptoRng, TryRng};
use hpke::{Deserializable, Kem, OpModeR, OpModeS, Serializable};

pub const ENC_LENGTH: usize = 1_120;
const TAG_LENGTH: usize = 16;

/// Hands out fixed bytes and panics past their end, so a seal uses exactly its randomness.
pub struct FixedRng(pub Vec<u8>);

impl TryRng for FixedRng {
    type Error = Infallible;

    fn try_next_u32(&mut self) -> Result<u32, Infallible> {
        let mut b = [0; 4];
        self.try_fill_bytes(&mut b)?;
        Ok(u32::from_le_bytes(b))
    }

    fn try_next_u64(&mut self) -> Result<u64, Infallible> {
        let mut b = [0; 8];
        self.try_fill_bytes(&mut b)?;
        Ok(u64::from_le_bytes(b))
    }

    fn try_fill_bytes(&mut self, dst: &mut [u8]) -> Result<(), Infallible> {
        assert!(dst.len() <= self.0.len(), "fixed randomness exhausted");
        dst.copy_from_slice(&self.0[..dst.len()]);
        self.0.drain(..dst.len());
        Ok(())
    }
}

impl TryCryptoRng for FixedRng {}

type Sender = AeadCtxS<ChaCha20Poly1305, HkdfSha256, XWing>;

/// HPKE `SetupBaseS` with fixed encapsulation randomness: `enc` and the sender context.
fn sender(recipient_public_key: &[u8], info: &[u8], randomness: &[u8; 64]) -> (Vec<u8>, Sender) {
    let public_key = <XWing as Kem>::PublicKey::from_bytes(recipient_public_key).expect("X-Wing public key");
    let mut rng = FixedRng(randomness.to_vec());
    let (enc, context) =
        hpke::setup_sender_with_rng::<ChaCha20Poly1305, HkdfSha256, XWing>(&OpModeS::Base, &public_key, info, &mut rng)
            .expect("setup");
    assert!(rng.0.is_empty(), "seal left randomness unused");
    (enc.to_bytes().to_vec(), context)
}

/// `enc ‖ ct`: `plaintext` sealed to `recipient_public_key` with `aad` empty.
#[must_use]
pub fn seal(recipient_public_key: &[u8], info: &[u8], plaintext: &[u8], randomness: &[u8; 64]) -> Vec<u8> {
    let (mut out, mut context) = sender(recipient_public_key, info, randomness);
    out.extend(context.seal(plaintext, b"").expect("seal"));
    assert_eq!(out.len(), ENC_LENGTH + plaintext.len() + TAG_LENGTH);
    out
}

/// The plaintext, or `None` when the payload doesn't open.
#[must_use]
pub fn open(recipient_ikm: &[u8], info: &[u8], payload: &[u8]) -> Option<Vec<u8>> {
    if payload.len() < ENC_LENGTH + TAG_LENGTH {
        return None;
    }
    let (private_key, _) = XWing::derive_keypair(recipient_ikm);
    let enc = <XWing as Kem>::EncappedKey::from_bytes(&payload[..ENC_LENGTH]).ok()?;
    let mut context =
        hpke::setup_receiver::<ChaCha20Poly1305, HkdfSha256, XWing>(&OpModeR::Base, &private_key, &enc, info).ok()?;
    context.open(&payload[ENC_LENGTH..], b"").ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::json::parse::parse;

    const KNOWN_ANSWERS: &str = include_str!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../third_party/vectors/hpke-pq/test-vectors.json"
    ));

    /// draft-ietf-hpke-pq-05 Appendix A.5: MLKEM768-X25519 (X-Wing), HKDF-SHA256, ChaCha20Poly1305, base mode,
    /// from the vendored hpke-pq vectors.
    #[test]
    fn matches_the_draft_known_answers() {
        let all = parse(KNOWN_ANSWERS);
        let case = all
            .items()
            .iter()
            .find(|c| {
                c.get("mode").int() == 0
                    && c.get("kem_id").int() == 0x647a
                    && c.get("kdf_id").int() == 1
                    && c.get("aead_id").int() == 3
            })
            .expect("X-Wing case");
        let (private_key, public_key) = XWing::derive_keypair(&case.get("ikmR").bytes());
        assert_eq!(public_key.to_bytes().to_vec(), case.get("pkRm").bytes());
        assert_eq!(private_key.to_bytes().to_vec(), case.get("skRm").bytes());
        let randomness: [u8; 64] = case.get("ikmE").bytes().try_into().expect("64 B");
        let (enc, mut context) = sender(&public_key.to_bytes(), &case.get("info").bytes(), &randomness);
        assert_eq!(enc, case.get("enc").bytes());
        for encryption in case.get("encryptions").items() {
            let ct = context
                .seal(&encryption.get("pt").bytes(), &encryption.get("aad").bytes())
                .expect("seal");
            assert_eq!(ct, encryption.get("ct").bytes());
        }
        for export in case.get("exports").items() {
            let mut out = vec![0; usize::try_from(export.get("L").int()).expect("length")];
            context
                .export(&export.get("exporter_context").bytes(), &mut out)
                .expect("export");
            assert_eq!(out, export.get("exported_value").bytes());
        }
    }

    #[test]
    fn a_seal_opens_and_a_flipped_bit_does_not() {
        let ikm = [7; 32];
        let (_, public_key) = XWing::derive_keypair(&ikm);
        let sealed = seal(&public_key.to_bytes(), b"info", b"hello", &[9; 64]);
        assert_eq!(open(&ikm, b"info", &sealed), Some(b"hello".to_vec()));
        assert_eq!(open(&ikm, b"info", &crate::spec::flip(&sealed, ENC_LENGTH)), None);
        assert_eq!(open(&ikm, b"other", &sealed), None);
        assert_eq!(open(&ikm, b"info", &sealed[..ENC_LENGTH + 15]), None);
    }
}
