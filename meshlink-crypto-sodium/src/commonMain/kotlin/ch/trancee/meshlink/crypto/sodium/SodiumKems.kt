package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.CryptoFailure
import ch.trancee.meshlink.internal.CryptoProvider.Companion.ML_KEM_768_ENCAPSULATION_SEED_LENGTH
import ch.trancee.meshlink.internal.CryptoProvider.Companion.ML_KEM_768_SEED_LENGTH
import ch.trancee.meshlink.internal.CryptoProvider.Companion.X_WING_ENCAPSULATION_SEED_LENGTH
import ch.trancee.meshlink.internal.CryptoProvider.Companion.X_WING_SEED_LENGTH
import ch.trancee.meshlink.internal.CryptoResult
import ch.trancee.meshlink.internal.KemEncapsulation
import ch.trancee.meshlink.internal.KeyEncapsulation
import ch.trancee.meshlink.internal.KeyPair

/** The libsodium [KeyEncapsulation] family of [SodiumCryptoProvider]. */
internal object SodiumKems :
    KeyEncapsulation {
    private const val ML_KEM_768_PUBLIC_KEY_LENGTH = 1184
    private const val ML_KEM_768_SECRET_KEY_LENGTH = 2400
    private const val ML_KEM_768_CIPHERTEXT_LENGTH = 1088
    private const val X_WING_PUBLIC_KEY_LENGTH = 1216
    private const val X_WING_CIPHERTEXT_LENGTH = 1120
    private const val KEM_SHARED_SECRET_LENGTH = 32

    override fun mlKem768KeyPair(): KeyPair = mlKem768KeyPair(SodiumRandom.randomBytes(ML_KEM_768_SEED_LENGTH))

    override fun mlKem768KeyPair(seed: ByteArray): KeyPair {
        requireLength("ML-KEM-768 seed", seed, ML_KEM_768_SEED_LENGTH)
        val keys = sodiumMlKem768KeyPair(seed)
        return KeyPair(
            secretKey = keys.copyOfRange(ML_KEM_768_PUBLIC_KEY_LENGTH, keys.size),
            publicKey = keys.copyOf(ML_KEM_768_PUBLIC_KEY_LENGTH),
        )
    }

    override fun mlKem768Encapsulate(publicKey: ByteArray): CryptoResult<KemEncapsulation> =
        mlKem768Encapsulate(publicKey, SodiumRandom.randomBytes(ML_KEM_768_ENCAPSULATION_SEED_LENGTH))

    override fun mlKem768Encapsulate(
        publicKey: ByteArray,
        seed: ByteArray,
    ): CryptoResult<KemEncapsulation> {
        requireLength("ML-KEM-768 public key", publicKey, ML_KEM_768_PUBLIC_KEY_LENGTH)
        requireLength("ML-KEM-768 encapsulation seed", seed, ML_KEM_768_ENCAPSULATION_SEED_LENGTH)
        return encapsulation(sodiumMlKem768Encapsulate(publicKey, seed))
    }

    override fun mlKem768Decapsulate(
        secretKey: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        requireLength("ML-KEM-768 secret key", secretKey, ML_KEM_768_SECRET_KEY_LENGTH)
        requireLength("ML-KEM-768 ciphertext", ciphertext, ML_KEM_768_CIPHERTEXT_LENGTH)
        return sodiumMlKem768Decapsulate(secretKey, ciphertext)
    }

    override fun xWingKeyPair(): KeyPair = xWingKeyPair(SodiumRandom.randomBytes(X_WING_SEED_LENGTH))

    override fun xWingKeyPair(seed: ByteArray): KeyPair {
        requireLength("X-Wing seed", seed, X_WING_SEED_LENGTH)
        return KeyPair(seed.copyOf(), sodiumXWingPublicKey(seed))
    }

    override fun xWingEncapsulate(publicKey: ByteArray): CryptoResult<KemEncapsulation> =
        xWingEncapsulate(publicKey, SodiumRandom.randomBytes(X_WING_ENCAPSULATION_SEED_LENGTH))

    override fun xWingEncapsulate(
        publicKey: ByteArray,
        seed: ByteArray,
    ): CryptoResult<KemEncapsulation> {
        requireLength("X-Wing public key", publicKey, X_WING_PUBLIC_KEY_LENGTH)
        requireLength("X-Wing encapsulation seed", seed, X_WING_ENCAPSULATION_SEED_LENGTH)
        return encapsulation(sodiumXWingEncapsulate(publicKey, seed))
    }

    override fun xWingDecapsulate(
        secretKey: ByteArray,
        ciphertext: ByteArray,
    ): CryptoResult<ByteArray> {
        requireLength("X-Wing secret key", secretKey, X_WING_SEED_LENGTH)
        requireLength("X-Wing ciphertext", ciphertext, X_WING_CIPHERTEXT_LENGTH)
        return sodiumXWingDecapsulate(secretKey, ciphertext).orFailed(CryptoFailure.INVALID_CIPHERTEXT)
    }

    // The natives return the ciphertext followed by the shared secret.
    private fun encapsulation(output: ByteArray?): CryptoResult<KemEncapsulation> =
        if (output == null) {
            CryptoResult.Failed(CryptoFailure.INVALID_PUBLIC_KEY)
        } else {
            val split = output.size - KEM_SHARED_SECRET_LENGTH
            CryptoResult.Ok(KemEncapsulation(output.copyOf(split), output.copyOfRange(split, output.size)))
        }
}

// The libsodium calls behind SodiumKems: JNI on Android and the JVM test hosts, cinterop on iOS. Callers
// validate every argument first; an implementation only adapts arrays and calls libsodium.
// A function that can refuse its input returns `null` for libsodium's -1; one with two outputs
// returns them concatenated, so the JNI shim returns a single array.

/** `crypto_kem_mlkem768_seed_keypair` of the 64-byte [seed]: the public key, then the secret key. */
internal expect fun sodiumMlKem768KeyPair(seed: ByteArray): ByteArray

/**
 * `crypto_kem_mlkem768_enc_deterministic`: the ciphertext, then the shared secret; `null` for a
 * refused key.
 */
internal expect fun sodiumMlKem768Encapsulate(
    publicKey: ByteArray,
    seed: ByteArray,
): ByteArray?

/** `crypto_kem_mlkem768_dec`: the shared secret, or the implicit-rejection key. */
internal expect fun sodiumMlKem768Decapsulate(
    secretKey: ByteArray,
    ciphertext: ByteArray,
): ByteArray

/** The public key from `crypto_kem_xwing_seed_keypair` for the 32-byte [seed]. */
internal expect fun sodiumXWingPublicKey(seed: ByteArray): ByteArray

/**
 * `crypto_kem_xwing_enc_deterministic`: the ciphertext, then the shared secret; `null` for a refused
 * key.
 */
internal expect fun sodiumXWingEncapsulate(
    publicKey: ByteArray,
    seed: ByteArray,
): ByteArray?

/** `crypto_kem_xwing_dec`: the shared secret, or `null` if libsodium refuses the ciphertext. */
internal expect fun sodiumXWingDecapsulate(
    secretKey: ByteArray,
    ciphertext: ByteArray,
): ByteArray?
