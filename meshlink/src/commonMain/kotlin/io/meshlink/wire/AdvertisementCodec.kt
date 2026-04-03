package io.meshlink.wire

/**
 * 16-byte BLE advertisement service-data codec.
 *
 * ```
 * Byte 0:      [4 bits: version major][4 bits: power mode]
 * Byte 1:      [8 bits: version minor]
 * Bytes 2-3:   [16 bits: mesh network hash (LE)]
 * Bytes 4-15:  [12 bytes: truncated SHA-256 of X25519 public key]
 * ```
 *
 * **BLE size constraint:** The scan response carries a Service Data AD with
 * the 16-bit UUID alias `0x7F3A` (4 bytes overhead: 1 length + 1 type + 2 UUID),
 * leaving 27 bytes for payload. This codec uses 16 bytes.
 *
 * The 128-bit UUID (`7F3Axxxx-8FC5-11EC-B909-0242AC120002`) is used in the
 * advertisement data for GATT service discovery. The scan response uses the
 * 16-bit alias for space efficiency.
 *
 * **Key hash = wire peer ID:** The 12-byte key hash in the advertisement is
 * identical to the wire protocol's 12-byte peer ID (first 12 bytes of SHA-256).
 * No dual-ID mapping is needed.
 *
 * **Mesh network hash:** A 16-bit hash of the `appId` string for pre-connection
 * filtering. `0x0000` means no filtering.
 */
object AdvertisementCodec {

    const val SIZE = 16
    const val KEY_HASH_SIZE = 12
    const val MESH_HASH_SIZE = 2

    /**
     * Encode a BLE advertisement payload.
     *
     * @param publicKeyHash SHA-256 hash of the X25519 public key (≥12 bytes).
     *   Only the first 12 bytes are used (matches wire peer ID).
     * @param meshHash 16-bit hash of the appId for pre-connection filtering.
     *   `0` means no filtering (connects to all MeshLink peers).
     */
    fun encode(
        versionMajor: Int,
        versionMinor: Int,
        powerMode: Int,
        publicKeyHash: ByteArray,
        meshHash: UShort = 0u,
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

        // Mesh network hash (little-endian)
        val hash = meshHash.toInt()
        result[2] = (hash and 0xFF).toByte()
        result[3] = ((hash shr 8) and 0xFF).toByte()

        publicKeyHash.copyInto(result, destinationOffset = 4, startIndex = 0, endIndex = KEY_HASH_SIZE)

        return result
    }

    fun decode(data: ByteArray): AdvertisementPayload {
        require(data.size >= SIZE) { "Advertisement payload must be at least $SIZE bytes, got ${data.size}" }

        val byte0 = data[0].toInt() and 0xFF
        val versionMajor = byte0 ushr 4
        val powerMode = byte0 and 0x0F
        val versionMinor = data[1].toInt() and 0xFF
        val meshHash =
            ((data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)).toUShort()
        val keyHash = data.copyOfRange(4, 4 + KEY_HASH_SIZE)

        return AdvertisementPayload(versionMajor, versionMinor, powerMode, keyHash, meshHash)
    }

    /**
     * Compute a 16-bit mesh network hash from an appId string.
     * Uses a simple hash that distributes well for short strings.
     * Returns 0 if appId is null (no filtering).
     */
    fun meshHash(appId: String?): UShort {
        if (appId == null) return 0u
        // FNV-1a 32-bit, then fold to 16-bit via XOR
        var h = 0x811c9dc5u
        for (b in appId.encodeToByteArray()) {
            h = h xor (b.toUInt() and 0xFFu)
            h = h * 0x01000193u
        }
        return ((h xor (h shr 16)) and 0xFFFFu).toUShort()
    }
}

data class AdvertisementPayload(
    val versionMajor: Int,
    val versionMinor: Int,
    val powerMode: Int,
    val keyHash: ByteArray,
    val meshHash: UShort = 0u,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdvertisementPayload) return false
        return versionMajor == other.versionMajor &&
            versionMinor == other.versionMinor &&
            powerMode == other.powerMode &&
            keyHash.contentEquals(other.keyHash) &&
            meshHash == other.meshHash
    }

    override fun hashCode(): Int {
        var result = versionMajor
        result = 31 * result + versionMinor
        result = 31 * result + powerMode
        result = 31 * result + keyHash.contentHashCode()
        result = 31 * result + meshHash.hashCode()
        return result
    }
}
