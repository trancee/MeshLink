package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.CryptoProvider
import ch.trancee.meshlink.internal.Ed25519KeyPair

/** The [CryptoProvider] backed by libsodium (ADR 0004). */
public object SodiumCryptoProvider : CryptoProvider {
    private const val BLAKE2B_MIN_LENGTH = 16
    private const val BLAKE2B_MAX_LENGTH = 64
    private const val ED25519_SEED_LENGTH = 32
    private val blake2bLengths = BLAKE2B_MIN_LENGTH..BLAKE2B_MAX_LENGTH

    override fun blake2b(
        outLength: Int,
        input: ByteArray,
        key: ByteArray?,
    ): ByteArray {
        require(outLength in blake2bLengths) { "BLAKE2b output must be 16 to 64 bytes, not $outLength" }
        if (key != null) require(key.size in blake2bLengths) { "BLAKE2b key must be 16 to 64 bytes, not ${key.size}" }
        return sodiumBlake2b(outLength, input, key)
    }

    override fun ed25519KeyPair(): Ed25519KeyPair = ed25519KeyPair(randomBytes(ED25519_SEED_LENGTH))

    override fun ed25519KeyPair(seed: ByteArray): Ed25519KeyPair {
        require(seed.size == ED25519_SEED_LENGTH) { "Ed25519 seed must be 32 bytes, not ${seed.size}" }
        return Ed25519KeyPair(seed.copyOf(), sodiumEd25519PublicKey(seed))
    }

    override fun randomBytes(count: Int): ByteArray {
        require(count >= 0) { "random byte count must not be negative, not $count" }
        return sodiumRandomBytes(count)
    }
}
