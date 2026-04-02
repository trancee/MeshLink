package io.meshlink.crypto

/**
 * HMAC-SHA-256 (RFC 2104).
 */
internal object HmacSha256 {

    private const val BLOCK_SIZE = 64

    fun mac(key: ByteArray, data: ByteArray): ByteArray {
        // If key is longer than block size, hash it first
        val k = if (key.size > BLOCK_SIZE) Sha256.hash(key) else key

        // Pad key to block size
        val paddedKey = ByteArray(BLOCK_SIZE)
        k.copyInto(paddedKey)

        val ipad = ByteArray(BLOCK_SIZE) { (paddedKey[it].toInt() xor 0x36).toByte() }
        val opad = ByteArray(BLOCK_SIZE) { (paddedKey[it].toInt() xor 0x5c).toByte() }

        // inner = SHA-256(ipad || data)
        val innerInput = ByteArray(BLOCK_SIZE + data.size)
        ipad.copyInto(innerInput)
        data.copyInto(innerInput, BLOCK_SIZE)
        val inner = Sha256.hash(innerInput)
        // HMAC = SHA-256(opad || inner)
        val outerInput = ByteArray(BLOCK_SIZE + inner.size)
        opad.copyInto(outerInput)
        inner.copyInto(outerInput, BLOCK_SIZE)
        return Sha256.hash(outerInput)
    }
}
