package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.CryptoProvider.Companion.HKDF_SHA256_MAX_LENGTH
import ch.trancee.meshlink.internal.Hashing

/** The libsodium [Hashing] family of [SodiumCryptoProvider]. */
internal object SodiumHashing : Hashing {
    private const val BLAKE2B_MIN_LENGTH = 16
    private const val BLAKE2B_MAX_LENGTH = 64
    private const val SHA256_LENGTH = 32
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

    override fun sha256(input: ByteArray): ByteArray = sodiumSha256(input)

    override fun hkdfSha256Extract(
        salt: ByteArray,
        ikm: ByteArray,
    ): ByteArray = sodiumHkdfSha256Extract(salt, ikm)

    override fun hkdfSha256Expand(
        prk: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        requireLength("HKDF-SHA-256 PRK", prk, SHA256_LENGTH)
        require(length in 1..HKDF_SHA256_MAX_LENGTH) { "HKDF-SHA-256 output must be 1 to 8160 bytes, not $length" }
        return sodiumHkdfSha256Expand(prk, info, length)
    }
}

// The libsodium calls behind SodiumHashing: JNI on Android and the JVM test hosts, cinterop on iOS. Callers
// validate every argument first; an implementation only adapts arrays and calls libsodium.

/** `crypto_generichash_blake2b`, [outLength] bytes over [input], keyed with [key] if not `null`. */
internal expect fun sodiumBlake2b(
    outLength: Int,
    input: ByteArray,
    key: ByteArray?,
): ByteArray

/** `crypto_hash_sha256` of [input]. */
internal expect fun sodiumSha256(input: ByteArray): ByteArray

/** `crypto_kdf_hkdf_sha256_extract`: the 32-byte PRK of [ikm] under [salt]. */
internal expect fun sodiumHkdfSha256Extract(
    salt: ByteArray,
    ikm: ByteArray,
): ByteArray

/** `crypto_kdf_hkdf_sha256_expand`: [length] bytes from the 32-byte [prk] and [info]. */
internal expect fun sodiumHkdfSha256Expand(
    prk: ByteArray,
    info: ByteArray,
    length: Int,
): ByteArray
