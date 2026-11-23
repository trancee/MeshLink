//! Protocol 1 derivation rules (`docs/spec/README.md`, "Derivation rules").

use blake2::{Blake2b256, Digest};
use hkdf::Hkdf;
use sha2::Sha256;
use x25519_dalek::{X25519_BASEPOINT_BYTES, x25519};

/// `DS(purpose) = u8(len(s)) ‖ s`, where `s = "MeshLink/1 " ‖ purpose`.
#[must_use]
pub fn ds(purpose: &str) -> Vec<u8> {
    let s = format!("MeshLink/1 {purpose}");
    let mut out = vec![u8::try_from(s.len()).expect("domain string longer than 255 bytes")];
    out.extend_from_slice(s.as_bytes());
    out
}

/// `H(purpose, inputs)`: unkeyed BLAKE2b with a 32 B output over `DS(purpose) ‖ inputs`.
#[must_use]
pub fn h(purpose: &str, parts: &[&[u8]]) -> [u8; 32] {
    let mut hasher = Blake2b256::new();
    hasher.update(ds(purpose));
    for part in parts {
        hasher.update(part);
    }
    hasher.finalize().into()
}

/// `KDF(ikm, salt, purpose, context, L)`: HKDF-SHA-256 with `info = DS(purpose) ‖ context`.
#[must_use]
pub fn kdf(ikm: &[u8], salt: &[u8], purpose: &str, context: &[u8], length: usize) -> Vec<u8> {
    let salt = if salt.is_empty() { None } else { Some(salt) };
    let mut info = ds(purpose);
    info.extend_from_slice(context);
    let mut out = vec![0; length];
    Hkdf::<Sha256>::new(salt, ikm)
        .expand(&info, &mut out)
        .expect("HKDF output too long");
    out
}

#[must_use]
pub fn node_id(ed25519_key: &[u8]) -> [u8; 32] {
    h("node-id", &[ed25519_key])
}

#[must_use]
pub fn mesh_id_digest(mesh_id: &str, profile: u8) -> [u8; 32] {
    let length = u16::try_from(mesh_id.len())
        .expect("mesh identifier too long")
        .to_be_bytes();
    h("mesh-id", &[&length, mesh_id.as_bytes(), &[profile]])
}

#[must_use]
pub fn mesh_psk(mesh_key: &[u8], mesh_id_digest: &[u8]) -> [u8; 32] {
    kdf(mesh_key, mesh_id_digest, "mesh-psk", b"", 32)
        .try_into()
        .expect("32 B psk")
}

#[must_use]
pub fn link_prologue(versions: &[u8], selected: u8, profile: u8, mesh_id_digest: &[u8]) -> Vec<u8> {
    let mut out = ds("link-prologue");
    out.push(u8::try_from(versions.len()).expect("too many versions"));
    out.extend_from_slice(versions);
    out.extend_from_slice(&[selected, profile]);
    out.extend_from_slice(mesh_id_digest);
    out
}

/// The end-to-end Noise prologue: `DS("e2e-prologue") ‖ u8(protocol_version) ‖ u8(profile)`.
#[must_use]
pub fn e2e_prologue(version: u8, profile: u8) -> Vec<u8> {
    let mut out = ds("e2e-prologue");
    out.extend_from_slice(&[version, profile]);
    out
}

#[must_use]
pub fn hpke_info(sender_static_key: &[u8], recipient_node_id: &[u8]) -> Vec<u8> {
    let mut out = ds("hpke-first-message");
    out.extend_from_slice(sender_static_key);
    out.extend_from_slice(recipient_node_id);
    out
}

/// One TLV: `u8 type ‖ u16 length ‖ value`.
#[must_use]
pub fn tlv(code: u8, value: &[u8]) -> Vec<u8> {
    let mut out = vec![code];
    out.extend_from_slice(&u16::try_from(value.len()).expect("TLV too long").to_be_bytes());
    out.extend_from_slice(value);
    out
}

#[must_use]
pub fn x25519_public(private: &[u8; 32]) -> [u8; 32] {
    x25519(*private, X25519_BASEPOINT_BYTES)
}

/// A fixed 32 B input: `SHA-256("meshlink-reference " ‖ label)`. Every key, seed and nonce in the
/// tier 2 vectors comes from a label, so the files are reproducible byte for byte.
#[must_use]
pub fn seed(label: &str) -> [u8; 32] {
    Sha256::digest(format!("meshlink-reference {label}")).into()
}

/// A fixed 64 B input: `seed(label ‖ "/0") ‖ seed(label ‖ "/1")`.
#[must_use]
pub fn seed64(label: &str) -> [u8; 64] {
    let mut out = [0; 64];
    out[..32].copy_from_slice(&seed(&format!("{label}/0")));
    out[32..].copy_from_slice(&seed(&format!("{label}/1")));
    out
}

/// Flips the lowest bit of `bytes[index]`.
#[must_use]
pub fn flip(bytes: &[u8], index: usize) -> Vec<u8> {
    let mut out = bytes.to_vec();
    out[index] ^= 1;
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn domain_string_has_length_prefix() {
        assert_eq!(ds("node-id"), b"\x12MeshLink/1 node-id".to_vec());
    }

    #[test]
    fn empty_salt_means_32_zero_bytes() {
        assert_eq!(
            kdf(b"ikm", b"", "mesh-psk", b"", 32),
            kdf(b"ikm", &[0; 32], "mesh-psk", b"", 32)
        );
    }
}
