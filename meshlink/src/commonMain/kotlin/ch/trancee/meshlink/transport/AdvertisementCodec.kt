package ch.trancee.meshlink.transport

/**
 * Codec for the 16-byte BLE advertisement payload (spec §7).
 *
 * Layout:
 * ```
 * Byte 0 [7:5]  protocolVersion  3 bits  (0–7)
 * Byte 0 [4:3]  powerMode        2 bits  (0=Performance, 1=Balanced, 2=PowerSaver, 3=reserved)
 * Byte 0 [2:0]  reserved         3 bits  must be 0
 * Bytes 1–2     meshHash         16 bits little-endian  (0x0000 → 0x0001 substitution applied by caller)
 * Byte  3       l2capPsm         8 bits  0x00=GATT-only, 128–255=valid PSM
 * Bytes 4–15    keyHash          12 bytes first 12 bytes of SHA-256(Ed25519Pub ‖ X25519Pub)
 * ```
 */
internal object AdvertisementCodec {

    /**
     * Decoded advertisement fields. The [keyHash] field uses content-based [equals]/[hashCode] so
     * instances can be compared and stored in collections correctly.
     */
    data class AdvertisementPayload(
        val protocolVersion: Int,
        val powerMode: Int,
        val meshHash: UShort,
        val l2capPsm: UByte,
        val keyHash: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AdvertisementPayload) return false
            return protocolVersion == other.protocolVersion &&
                powerMode == other.powerMode &&
                meshHash == other.meshHash &&
                l2capPsm == other.l2capPsm &&
                keyHash.contentEquals(other.keyHash)
        }

        override fun hashCode(): Int {
            var result = protocolVersion
            result = 31 * result + powerMode
            result = 31 * result + meshHash.hashCode()
            result = 31 * result + l2capPsm.hashCode()
            result = 31 * result + keyHash.contentHashCode()
            return result
        }
    }

    /**
     * Packs [payload] into a 16-byte advertisement buffer per spec §7.
     *
     * @throws IllegalArgumentException if any field value is out of range.
     */
    fun encode(payload: AdvertisementPayload): ByteArray {
        if (payload.protocolVersion !in 0..7)
            throw IllegalArgumentException(
                "protocolVersion must be 0–7, got ${payload.protocolVersion}"
            )
        if (payload.powerMode !in 0..2)
            throw IllegalArgumentException(
                "powerMode must be 0–2 (3 is reserved), got ${payload.powerMode}"
            )
        val psm = payload.l2capPsm.toInt()
        if (psm in 1..127)
            throw IllegalArgumentException("l2capPsm must be 0 (GATT-only) or 128–255, got $psm")
        if (payload.keyHash.size != 12)
            throw IllegalArgumentException(
                "keyHash must be exactly 12 bytes, got ${payload.keyHash.size}"
            )

        val bytes = ByteArray(16)
        bytes[0] = ((payload.protocolVersion shl 5) or (payload.powerMode shl 3)).toByte()
        val meshHashInt = payload.meshHash.toInt()
        bytes[1] = (meshHashInt and 0xFF).toByte() // little-endian low byte
        bytes[2] = ((meshHashInt ushr 8) and 0xFF).toByte() // little-endian high byte
        bytes[3] = payload.l2capPsm.toByte()
        payload.keyHash.copyInto(bytes, destinationOffset = 4)
        return bytes
    }

    /**
     * Unpacks a 16-byte [bytes] buffer into an [AdvertisementPayload].
     *
     * @throws IllegalArgumentException if [bytes] is not exactly 16 bytes.
     */
    fun decode(bytes: ByteArray): AdvertisementPayload {
        if (bytes.size != 16)
            throw IllegalArgumentException("Expected exactly 16 bytes, got ${bytes.size}")

        val b0 = bytes[0].toInt() and 0xFF
        val protocolVersion = (b0 ushr 5) and 0x07
        val powerMode = (b0 ushr 3) and 0x03
        val meshHashLow = bytes[1].toInt() and 0xFF
        val meshHashHigh = bytes[2].toInt() and 0xFF
        val meshHash = ((meshHashHigh shl 8) or meshHashLow).toUShort()
        val l2capPsm = bytes[3].toUByte()
        val keyHash = bytes.copyOfRange(4, 16)

        return AdvertisementPayload(
            protocolVersion = protocolVersion,
            powerMode = powerMode,
            meshHash = meshHash,
            l2capPsm = l2capPsm,
            keyHash = keyHash,
        )
    }
}
