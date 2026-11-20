//! Signed mesh broadcasts (`docs/spec/routing.md`, "BROADCAST"): flood type `0x80` with container
//! kind `0x05`.

use crate::spec::{ds, node_id};
use ed25519_dalek::{Signature, Signer, SigningKey, VerifyingKey};

pub const TYPE: u8 = 0x80;
pub const SIGNED_BROADCAST: u8 = 0x05;
pub const PAYLOAD_MAX: usize = 1_024;
const HEADER: usize = 18;
const ORIGIN_KEY: usize = 32;
const SIGNATURE_LENGTH: usize = 64;

/// The flood header fields of one broadcast.
#[derive(Clone, Copy)]
pub struct Header {
    pub hop_limit: u8,
    pub message_id: [u8; 12],
    pub expiry: u32,
}

/// Every byte of the frame before the signature, with `origin_key` as the origin.
#[must_use]
pub fn unsigned(header: Header, origin_key: &[u8; 32], payload: &[u8]) -> Vec<u8> {
    assert!(payload.len() <= PAYLOAD_MAX, "payload over {PAYLOAD_MAX} B");
    let mut out = vec![TYPE, header.hop_limit];
    out.extend_from_slice(&header.message_id);
    out.extend_from_slice(&header.expiry.to_be_bytes());
    out.push(SIGNED_BROADCAST);
    out.extend_from_slice(origin_key);
    out.extend_from_slice(&u16::try_from(payload.len()).expect("payload length").to_be_bytes());
    out.extend_from_slice(payload);
    out
}

/// `DS("broadcast") ‖ frame[0] ‖ frame[2 .. length − 65]` of a signed `frame`.
#[must_use]
pub fn signing_input(frame: &[u8]) -> Vec<u8> {
    let mut out = ds("broadcast");
    out.push(frame[0]);
    out.extend_from_slice(&frame[2..frame.len() - SIGNATURE_LENGTH]);
    out
}

/// The frame of `payload` from `key`'s identity, signed by `signer`.
#[must_use]
pub fn sign(header: Header, key: &SigningKey, signer: &SigningKey, payload: &[u8]) -> Vec<u8> {
    let mut frame = unsigned(header, &key.verifying_key().to_bytes(), payload);
    frame.extend_from_slice(&[0; SIGNATURE_LENGTH]);
    let signature = signer.sign(&signing_input(&frame)).to_bytes();
    let at = frame.len() - SIGNATURE_LENGTH;
    frame[at..].copy_from_slice(&signature);
    frame
}

/// The origin key of a well-formed signed broadcast.
#[must_use]
pub fn origin_key(frame: &[u8]) -> [u8; 32] {
    frame[HEADER + 1..HEADER + 1 + ORIGIN_KEY].try_into().expect("32 B")
}

/// The origin's NodeId if the signature by `origin_key` verifies.
///
/// # Errors
/// `bad-signature` if it doesn't.
pub fn verify(frame: &[u8]) -> Result<[u8; 32], &'static str> {
    let key = origin_key(frame);
    let n = frame.len();
    let signature = Signature::from_bytes(frame[n - SIGNATURE_LENGTH..].try_into().expect("64 B"));
    VerifyingKey::from_bytes(&key)
        .and_then(|verifying| verifying.verify_strict(&signing_input(frame), &signature))
        .map(|()| node_id(&key))
        .map_err(|_| "bad-signature")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::spec::{flip, seed};

    const HEADER_FIELDS: Header = Header {
        hop_limit: 64,
        message_id: [7; 12],
        expiry: 1_798_762_200,
    };

    #[test]
    fn a_signed_broadcast_verifies_at_any_hop_limit_and_a_flipped_payload_bit_does_not() {
        let alice = SigningKey::from_bytes(&seed("test alice ed25519"));
        let frame = sign(HEADER_FIELDS, &alice, &alice, b"hello");
        let node = node_id(&alice.verifying_key().to_bytes());
        assert_eq!(verify(&frame), Ok(node));
        let mut lowered = frame.clone();
        lowered[1] = 1;
        assert_eq!(verify(&lowered), Ok(node));
        assert_eq!(verify(&flip(&frame, HEADER + 35)), Err("bad-signature"));
    }

    #[test]
    fn the_signing_input_matches_the_derivations_vector() {
        let frame = hex_bytes(
            "804057fac0f41ce4572c65194c2e6b36eed8056bd4a421df32031ccfec06579097c275c3c386d639d79dc8649d5c5bf5a44347000f576174657220617420676174652033",
        );
        let mut signed = frame.clone();
        signed.extend_from_slice(&[0; SIGNATURE_LENGTH]);
        let expected = "144d6573684c696e6b2f312062726f6164636173748057fac0f41ce4572c65194c2e6b36eed8056bd4a421df32031ccfec06579097c275c3c386d639d79dc8649d5c5bf5a44347000f576174657220617420676174652033";
        assert_eq!(crate::json::to_hex(&signing_input(&signed)), expected);
    }

    fn hex_bytes(s: &str) -> Vec<u8> {
        (0..s.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&s[i..i + 2], 16).expect("hex"))
            .collect()
    }
}
