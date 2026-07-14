package ch.trancee.meshlink.crypto

internal fun hkdfSha256(
    provider: CryptoProvider,
    salt: ByteArray,
    ikm: ByteArray,
    info: ByteArray,
    outputLength: Int,
): ByteArray {
    require(outputLength <= HKDF_MAX_OUTPUT_BLOCKS * HKDF_HASH_LEN_BYTES) {
        "HKDF output too large"
    }
    val effectiveSalt = if (salt.isEmpty()) ByteArray(HKDF_HASH_LEN_BYTES) else salt
    val pseudoRandomKey = provider.hmacSha256(effectiveSalt, ikm)
    val output = ByteArray(outputLength)
    var previous = byteArrayOf()
    var offset = 0
    var counter = 1
    while (offset < outputLength) {
        previous =
            provider.hmacSha256(pseudoRandomKey, previous + info + byteArrayOf(counter.toByte()))
        val bytesToCopy = minOf(previous.size, outputLength - offset)
        previous.copyOfRange(0, bytesToCopy).copyInto(output, destinationOffset = offset)
        offset += bytesToCopy
        counter += 1
    }
    return output
}

private const val HKDF_HASH_LEN_BYTES: Int = 32
private const val HKDF_MAX_OUTPUT_BLOCKS: Int = 255
