package io.meshlink.wire

import io.meshlink.crypto.Sha256

/**
 * Frozen 17-byte BLE advertisement payload codec.
 *
 * ```
 * Byte 0:      [4 bits: version major][4 bits: power mode]
 * Byte 1:      [8 bits: version minor]
 * Bytes 2-16:  [15 bytes: truncated SHA-256 of X25519 public key]
 * ```
 */
object AdvertisementCodec {

    const val SIZE = 17

    fun encode(
        versionMajor: Int,
        versionMinor: Int,
        powerMode: Int,
        publicKey: ByteArray,
    ): ByteArray {
        val major = versionMajor.coerceIn(0, 15)
        val minor = versionMinor.coerceIn(0, 255)
        val power = powerMode.coerceIn(0, 15)

        val result = ByteArray(SIZE)
        result[0] = ((major shl 4) or power).toByte()
        result[1] = minor.toByte()

        val hash = Sha256.hash(publicKey)
        hash.copyInto(result, destinationOffset = 2, startIndex = 0, endIndex = 15)

        return result
    }

    fun decode(data: ByteArray): AdvertisementPayload {
        require(data.size >= SIZE) { "Advertisement payload must be at least $SIZE bytes, got ${data.size}" }

        val byte0 = data[0].toInt() and 0xFF
        val versionMajor = byte0 ushr 4
        val powerMode = byte0 and 0x0F
        val versionMinor = data[1].toInt() and 0xFF
        val keyHash = data.copyOfRange(2, 17)

        return AdvertisementPayload(versionMajor, versionMinor, powerMode, keyHash)
    }
}

data class AdvertisementPayload(
    val versionMajor: Int,
    val versionMinor: Int,
    val powerMode: Int,
    val keyHash: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdvertisementPayload) return false
        return versionMajor == other.versionMajor &&
            versionMinor == other.versionMinor &&
            powerMode == other.powerMode &&
            keyHash.contentEquals(other.keyHash)
    }

    override fun hashCode(): Int {
        var result = versionMajor
        result = 31 * result + versionMinor
        result = 31 * result + powerMode
        result = 31 * result + keyHash.contentHashCode()
        return result
    }
}
