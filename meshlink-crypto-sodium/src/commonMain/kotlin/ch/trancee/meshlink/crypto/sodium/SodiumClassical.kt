package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.Aead
import ch.trancee.meshlink.internal.CryptoFailure
import ch.trancee.meshlink.internal.CryptoProvider.Companion.ED25519_SEED_LENGTH
import ch.trancee.meshlink.internal.CryptoProvider.Companion.X25519_KEY_LENGTH
import ch.trancee.meshlink.internal.CryptoResult
import ch.trancee.meshlink.internal.Ed25519KeyPair
import ch.trancee.meshlink.internal.KeyAgreement
import ch.trancee.meshlink.internal.KeyPair
import ch.trancee.meshlink.internal.Signatures

/** The libsodium [Signatures], [KeyAgreement] and [Aead] families of [SodiumCryptoProvider]. */
internal object SodiumClassical :
    Signatures,
    KeyAgreement,
    Aead {
    private const val ED25519_PUBLIC_KEY_LENGTH = 32
    private const val ED25519_SIGNATURE_LENGTH = 64
    private const val AEAD_KEY_LENGTH = 32
    private const val AEAD_NONCE_LENGTH = 12
    private const val AEAD_TAG_LENGTH = 16

    override fun ed25519KeyPair(): Ed25519KeyPair = ed25519KeyPair(SodiumRandom.randomBytes(ED25519_SEED_LENGTH))

    override fun ed25519KeyPair(seed: ByteArray): Ed25519KeyPair {
        requireLength("Ed25519 seed", seed, ED25519_SEED_LENGTH)
        return Ed25519KeyPair(seed.copyOf(), sodiumEd25519PublicKey(seed))
    }

    override fun ed25519Sign(
        seed: ByteArray,
        message: ByteArray,
    ): ByteArray {
        requireLength("Ed25519 seed", seed, ED25519_SEED_LENGTH)
        return sodiumEd25519Sign(seed, message)
    }

    override fun ed25519Verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        requireLength("Ed25519 public key", publicKey, ED25519_PUBLIC_KEY_LENGTH)
        return signature.size == ED25519_SIGNATURE_LENGTH && sodiumEd25519Verify(publicKey, message, signature)
    }

    override fun x25519KeyPair(): KeyPair = x25519KeyPair(SodiumRandom.randomBytes(X25519_KEY_LENGTH))

    override fun x25519KeyPair(seed: ByteArray): KeyPair {
        requireLength("X25519 secret key", seed, X25519_KEY_LENGTH)
        return KeyPair(seed.copyOf(), sodiumX25519PublicKey(seed))
    }

    override fun x25519(
        secretKey: ByteArray,
        publicKey: ByteArray,
    ): CryptoResult<ByteArray> {
        requireLength("X25519 secret key", secretKey, X25519_KEY_LENGTH)
        requireLength("X25519 public key", publicKey, X25519_KEY_LENGTH)
        return sodiumX25519(secretKey, publicKey).orFailed(CryptoFailure.INVALID_PUBLIC_KEY)
    }

    override fun chaCha20Poly1305Seal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        requireAeadKeyAndNonce(key, nonce)
        return sodiumChaCha20Poly1305Seal(key, nonce, plaintext, associatedData)
    }

    override fun chaCha20Poly1305Open(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): CryptoResult<ByteArray> {
        requireAeadKeyAndNonce(key, nonce)
        if (ciphertext.size < AEAD_TAG_LENGTH) return CryptoResult.Failed(CryptoFailure.AUTHENTICATION_FAILED)
        return sodiumChaCha20Poly1305Open(key, nonce, ciphertext, associatedData)
            .orFailed(CryptoFailure.AUTHENTICATION_FAILED)
    }

    private fun requireAeadKeyAndNonce(
        key: ByteArray,
        nonce: ByteArray,
    ) {
        requireLength("ChaCha20-Poly1305 key", key, AEAD_KEY_LENGTH)
        requireLength("ChaCha20-Poly1305 nonce", nonce, AEAD_NONCE_LENGTH)
    }
}

// The libsodium calls behind SodiumClassical: JNI on Android and the JVM test hosts, cinterop on iOS. Callers
// validate every argument first; an implementation only adapts arrays and calls libsodium.

/** The public key from `crypto_sign_ed25519_seed_keypair` for a 32-byte [seed]. */
internal expect fun sodiumEd25519PublicKey(seed: ByteArray): ByteArray

/** `crypto_sign_ed25519_detached` of [message] by the key pair of the 32-byte [seed]. */
internal expect fun sodiumEd25519Sign(
    seed: ByteArray,
    message: ByteArray,
): ByteArray

/** `crypto_sign_ed25519_verify_detached` of the 64-byte [signature] over [message]. */
internal expect fun sodiumEd25519Verify(
    publicKey: ByteArray,
    message: ByteArray,
    signature: ByteArray,
): Boolean

/** `crypto_scalarmult_curve25519_base` of the 32-byte secret [key]. */
internal expect fun sodiumX25519PublicKey(key: ByteArray): ByteArray

/** `crypto_scalarmult_curve25519`, or `null` if libsodium refuses a low-order [publicKey]. */
internal expect fun sodiumX25519(
    secretKey: ByteArray,
    publicKey: ByteArray,
): ByteArray?

/** `crypto_aead_chacha20poly1305_ietf_encrypt`: the ciphertext with its 16-byte tag appended. */
internal expect fun sodiumChaCha20Poly1305Seal(
    key: ByteArray,
    nonce: ByteArray,
    plaintext: ByteArray,
    associatedData: ByteArray,
): ByteArray

/**
 * `crypto_aead_chacha20poly1305_ietf_decrypt` of a [ciphertext] of at least 16 bytes, or `null` if
 * the tag fails.
 */
internal expect fun sodiumChaCha20Poly1305Open(
    key: ByteArray,
    nonce: ByteArray,
    ciphertext: ByteArray,
    associatedData: ByteArray,
): ByteArray?
