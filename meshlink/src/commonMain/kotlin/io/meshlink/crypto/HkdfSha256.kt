package io.meshlink.crypto

/**
 * HKDF-SHA-256 (RFC 5869).
 * Extract-then-Expand key derivation using HMAC-SHA-256.
 */
internal object HkdfSha256 {

    private const val HASH_LEN = 32

    fun derive(ikm: ByteArray, salt: ByteArray?, info: ByteArray, outputLength: Int): ByteArray {
        require(outputLength in 1..255 * HASH_LEN) {
            "outputLength must be in 1..${255 * HASH_LEN}"
        }

        // Extract: PRK = HMAC-Hash(salt, IKM)
        val actualSalt = if (salt == null || salt.isEmpty()) ByteArray(HASH_LEN) else salt
        val prk = HmacSha256.mac(actualSalt, ikm)

        // Expand: T(1) || T(2) || ... truncated to outputLength
        val n = (outputLength + HASH_LEN - 1) / HASH_LEN
        val okm = ByteArray(outputLength)
        var prev = ByteArray(0)
        var offset = 0

        for (i in 1..n) {
            prev = HmacSha256.mac(prk, prev + info + byteArrayOf(i.toByte()))
            val toCopy = minOf(HASH_LEN, outputLength - offset)
            prev.copyInto(okm, offset, 0, toCopy)
            offset += toCopy
        }

        return okm
    }
}
