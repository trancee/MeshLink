package io.meshlink.wire

/**
 * 10-byte BLE advertisement service-data codec.
 *
 * ```
 * Byte 0:     [4 bits: version major][4 bits: power mode]
 * Byte 1:     [8 bits: version minor]
 * Bytes 2-9:  [8 bytes: truncated SHA-256 of X25519 public key]
 * ```
 *
 * **BLE size constraint:** BLE 4.x scan response data is limited to 31 bytes.
 * A Service Data AD structure with a 128-bit UUID requires 18 bytes of overhead
 * (1 length + 1 type + 16 UUID), leaving 13 bytes for the payload. This codec
 * uses 10 bytes, well within that limit.
 *
 * This is a pure codec — callers must pre-hash the public key via SHA-256
 * before calling [encode]. This keeps the wire module free of crypto dependencies.
 */
object AdvertisementCodec {

    const val SIZE = 10
    const val KEY_HASH_SIZE = 8

    /**
     * Encode a BLE advertisement payload.
     *
     * @param publicKeyHash SHA-256 hash of the X25519 public key (≥8 bytes).
     *   Only the first 8 bytes are used.
     */
    fun encode(
        versionMajor: Int,
        versionMinor: Int,
        powerMode: Int,
        publicKeyHash: ByteArray,
    ): ByteArray {
        require(publicKeyHash.size >= KEY_HASH_SIZE) {
            "publicKeyHash must be at least $KEY_HASH_SIZE bytes, got ${publicKeyHash.size}"
        }

        val major = versionMajor.coerceIn(0, 15)
        val minor = versionMinor.coerceIn(0, 255)
        val power = powerMode.coerceIn(0, 15)

        val result = ByteArray(SIZE)
        result[0] = ((major shl 4) or power).toByte()
        result[1] = minor.toByte()

        publicKeyHash.copyInto(result, destinationOffset = 2, startIndex = 0, endIndex = KEY_HASH_SIZE)

        return result
    }

    fun decode(data: ByteArray): AdvertisementPayload {
        require(data.size >= SIZE) { "Advertisement payload must be at least $SIZE bytes, got ${data.size}" }

        val byte0 = data[0].toInt() and 0xFF
        val versionMajor = byte0 ushr 4
        val powerMode = byte0 and 0x0F
        val versionMinor = data[1].toInt() and 0xFF
        val keyHash = data.copyOfRange(2, 2 + KEY_HASH_SIZE)

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
