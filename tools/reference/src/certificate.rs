//! Identity certificates (`docs/spec/identity.md`, "Identity certificate").

use crate::spec::{ds, node_id, seed, x25519_public};
use ed25519_dalek::{Signature, Signer, SigningKey, VerifyingKey};
use hpke::kem::XWing;
use hpke::{Kem, Serializable};

pub const VERSION: u8 = 0x01;
pub const CLASSICAL: u8 = 0x01;
pub const POST_QUANTUM: u8 = 0x02;
pub const VALIDITY_MAX: u32 = 86_400;
pub const CLOCK_SKEW: u32 = 30;
const SIGNATURE_LENGTH: usize = 64;

/// The key material behind one certificate, all derived from a label.
pub struct Identity {
    pub signing: SigningKey,
    pub x25519_private: [u8; 32],
    pub xwing_ikm: [u8; 32],
}

impl Identity {
    #[must_use]
    pub fn new(label: &str) -> Self {
        Self {
            signing: SigningKey::from_bytes(&seed(&format!("{label} ed25519"))),
            x25519_private: seed(&format!("{label} x25519")),
            xwing_ikm: seed(&format!("{label} x-wing")),
        }
    }

    #[must_use]
    pub fn ed25519_key(&self) -> [u8; 32] {
        self.signing.verifying_key().to_bytes()
    }

    #[must_use]
    pub fn x25519_key(&self) -> [u8; 32] {
        x25519_public(&self.x25519_private)
    }

    /// The X-Wing public key: HPKE `DeriveKeyPair(xwing_ikm)` (draft-ietf-hpke-pq-05).
    #[must_use]
    pub fn xwing_key(&self) -> Vec<u8> {
        XWing::derive_keypair(&self.xwing_ikm).1.to_bytes().to_vec()
    }

    #[must_use]
    pub fn node_id(&self) -> [u8; 32] {
        node_id(&self.ed25519_key())
    }

    /// Every byte before the signature.
    #[must_use]
    pub fn body(&self, profile: u8, not_before: u32, not_after: u32) -> Vec<u8> {
        let mut out = vec![VERSION, profile];
        out.extend_from_slice(&self.ed25519_key());
        out.extend_from_slice(&self.x25519_key());
        if profile == POST_QUANTUM {
            out.extend_from_slice(&self.xwing_key());
        }
        out.extend_from_slice(&not_before.to_be_bytes());
        out.extend_from_slice(&not_after.to_be_bytes());
        out
    }

    #[must_use]
    pub fn certificate(&self, profile: u8, not_before: u32, not_after: u32) -> Vec<u8> {
        sign(&self.body(profile, not_before, not_after), &self.signing)
    }
}

/// `body ‖ Ed25519(DS("certificate") ‖ body)`.
#[must_use]
pub fn sign(body: &[u8], key: &SigningKey) -> Vec<u8> {
    let mut input = ds("certificate");
    input.extend_from_slice(body);
    let mut out = body.to_vec();
    out.extend_from_slice(&key.sign(&input).to_bytes());
    out
}

/// `DS("certificate") ‖ certificate bytes 0 .. (length − 65)`.
#[must_use]
pub fn signing_input(certificate: &[u8]) -> Vec<u8> {
    let mut out = ds("certificate");
    out.extend_from_slice(&certificate[..certificate.len() - SIGNATURE_LENGTH]);
    out
}

#[must_use]
pub fn length(profile: u8) -> usize {
    if profile == POST_QUANTUM { 1_354 } else { 138 }
}

/// Validation rules 1 to 6 in order; the error names the first rule broken, with the vectors
/// README error codes.
///
/// # Errors
/// The code of the first rule the certificate breaks.
pub fn verify(certificate: &[u8], profile: u8, expected_node_id: &[u8], now: u32) -> Result<(), &'static str> {
    check_layout(certificate, profile)?;
    let n = certificate.len();
    let not_before = u32::from_be_bytes(certificate[n - 72..n - 68].try_into().expect("4 B"));
    let not_after = u32::from_be_bytes(certificate[n - 68..n - 64].try_into().expect("4 B"));
    if not_after <= not_before || not_after - not_before > VALIDITY_MAX {
        return Err("out-of-range");
    }
    let key: [u8; 32] = certificate[2..34].try_into().expect("32 B");
    let signature = Signature::from_bytes(certificate[n - 64..].try_into().expect("64 B"));
    let verified = VerifyingKey::from_bytes(&key)
        .and_then(|verifying| verifying.verify_strict(&signing_input(certificate), &signature));
    if verified.is_err() {
        return Err("bad-signature");
    }
    if node_id(&key) != expected_node_id {
        return Err("node-id-mismatch");
    }
    if u64::from(now) >= u64::from(not_after) + u64::from(CLOCK_SKEW) {
        return Err("expired");
    }
    if u64::from(now) + u64::from(CLOCK_SKEW) < u64::from(not_before) {
        return Err("not-yet-valid");
    }
    Ok(())
}

fn check_layout(certificate: &[u8], profile: u8) -> Result<(), &'static str> {
    match certificate {
        [] | [_] => Err("truncated"),
        [version, ..] if *version != VERSION => Err("unknown-version"),
        [_, kind, ..] if *kind != profile => Err("unknown-kind"),
        _ if certificate.len() < length(profile) => Err("truncated"),
        _ if certificate.len() > length(profile) => Err("trailing-bytes"),
        _ => Ok(()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOT_BEFORE: u32 = 1_798_761_600;

    #[test]
    fn a_signed_certificate_verifies_and_a_flipped_bit_does_not() {
        let alice = Identity::new("test alice");
        let certificate = alice.certificate(CLASSICAL, NOT_BEFORE, NOT_BEFORE + VALIDITY_MAX);
        assert_eq!(verify(&certificate, CLASSICAL, &alice.node_id(), NOT_BEFORE), Ok(()));
        let flipped = crate::spec::flip(&certificate, 80);
        assert_eq!(
            verify(&flipped, CLASSICAL, &alice.node_id(), NOT_BEFORE),
            Err("bad-signature")
        );
    }

    #[test]
    fn layouts_have_the_spec_lengths() {
        let alice = Identity::new("test alice");
        assert_eq!(alice.certificate(CLASSICAL, 0, 1).len(), 138);
        assert_eq!(alice.certificate(POST_QUANTUM, 0, 1).len(), 1_354);
    }

    #[test]
    fn layout_errors_come_first() {
        assert_eq!(check_layout(&[1], CLASSICAL), Err("truncated"));
        assert_eq!(check_layout(&[2, 1], CLASSICAL), Err("unknown-version"));
        assert_eq!(check_layout(&[1, 2], CLASSICAL), Err("unknown-kind"));
        assert_eq!(check_layout(&[1, 1, 0], CLASSICAL), Err("truncated"));
        assert_eq!(check_layout(&[0; 139].map(|_| 1), CLASSICAL), Err("trailing-bytes"));
    }
}
