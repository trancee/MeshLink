//! The certificate lookup reply seal (`routing.md` "Certificate lookup", ADR 0018).

use snow::params::CipherChoice;
use snow::resolvers::{CryptoResolver, DefaultResolver};
use snow::types::Cipher;
use x25519_dalek::x25519;

use crate::spec::{h, kdf};

/// The ChaCha20-Poly1305 tag.
pub const TAG_LENGTH: usize = 16;

/// `lookup_tag = trunc_8(H("lookup-tag", salt ‖ NodeId))`.
#[must_use]
pub fn tag(salt: &[u8], node_id: &[u8]) -> [u8; 8] {
    h("lookup-tag", &[salt, node_id])[..8].try_into().expect("8 B tag")
}

/// The X25519 shared secret, or `None` when it is all zero: the peer's key has low order.
#[must_use]
pub fn shared(private: &[u8; 32], public: &[u8; 32]) -> Option<[u8; 32]> {
    let shared = x25519(*private, *public);
    (shared != [0; 32]).then_some(shared)
}

/// `KDF(shared, salt ‖ lookup_tag, "lookup-reply", requester_ephemeral ‖ responder_ephemeral, 32)`.
#[must_use]
pub fn key(shared: &[u8], salt: &[u8], lookup_tag: &[u8], requester: &[u8], responder: &[u8]) -> [u8; 32] {
    kdf(
        shared,
        &[salt, lookup_tag].concat(),
        "lookup-reply",
        &[requester, responder].concat(),
        32,
    )
    .try_into()
    .expect("32 B key")
}

// snow's ChaChaPoly is RustCrypto's chacha20poly1305; Noise nonce 0 is the 12 zero bytes the spec names.
fn cipher(key: &[u8; 32]) -> Box<dyn Cipher> {
    let mut cipher = DefaultResolver
        .resolve_cipher(&CipherChoice::ChaChaPoly)
        .expect("ChaChaPoly");
    cipher.set(key);
    cipher
}

/// The certificate sealed under `key`, nonce zero and empty associated data: ciphertext ‖ tag.
#[must_use]
pub fn seal(key: &[u8; 32], certificate: &[u8]) -> Vec<u8> {
    let mut out = vec![0; certificate.len() + TAG_LENGTH];
    cipher(key).encrypt(0, &[], certificate, &mut out);
    out
}

/// The certificate in `sealed`, or `None` if its tag fails.
#[must_use]
pub fn open(key: &[u8; 32], sealed: &[u8]) -> Option<Vec<u8>> {
    let mut out = vec![0; sealed.len().checked_sub(TAG_LENGTH)?];
    cipher(key).decrypt(0, &[], sealed, &mut out).ok().map(|_| out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::spec::{seed, x25519_public};

    #[test]
    fn both_sides_derive_one_key_and_the_seal_opens() {
        let (requester, responder) = (seed("requester"), seed("responder"));
        let (requester_public, responder_public) = (x25519_public(&requester), x25519_public(&responder));
        let ours = shared(&requester, &responder_public).expect("contributory");
        let theirs = shared(&responder, &requester_public).expect("contributory");
        let sealing = key(&theirs, b"saltsalt", b"tagtagta", &requester_public, &responder_public);
        let opening = key(&ours, b"saltsalt", b"tagtagta", &requester_public, &responder_public);

        let sealed = seal(&sealing, b"certificate");

        assert_eq!(open(&opening, &sealed).as_deref(), Some(&b"certificate"[..]));
        assert_eq!(open(&opening, &sealed[1..]), None);
    }

    #[test]
    fn a_low_order_key_gives_no_shared_secret() {
        assert_eq!(shared(&seed("responder"), &[0; 32]), None);
    }
}
