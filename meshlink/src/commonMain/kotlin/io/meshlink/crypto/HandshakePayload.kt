package io.meshlink.crypto

/**
 * 5-byte payload carried inside Noise XX handshake messages
 * for protocol version negotiation and capability exchange.
 *
 * Wire format (big-endian):
 *   [0..1] protocolVersion  (UShort)
 *   [2]    capabilityFlags  (UByte, bit 0 = L2CAP support)
 *   [3..4] l2capPsm         (UShort, 0 if not supported)
 */
data class HandshakePayload(
    val protocolVersion: UShort,
    val capabilityFlags: UByte,
    val l2capPsm: UShort,
) {
    fun encode(): ByteArray {
        val data = ByteArray(SIZE)
        data[0] = (protocolVersion.toInt() ushr 8).toByte()
        data[1] = (protocolVersion.toInt() and 0xFF).toByte()
        data[2] = capabilityFlags.toByte()
        data[3] = (l2capPsm.toInt() ushr 8).toByte()
        data[4] = (l2capPsm.toInt() and 0xFF).toByte()
        return data
    }

    companion object {
        const val SIZE = 5
        const val CAP_L2CAP: UByte = 0x01u

        fun decode(data: ByteArray): HandshakePayload {
            require(data.size >= SIZE) { "Handshake payload too short: ${data.size} < $SIZE" }
            val version = ((data[0].toInt() and 0xFF) shl 8 or (data[1].toInt() and 0xFF)).toUShort()
            val caps = data[2].toUByte()
            val psm = ((data[3].toInt() and 0xFF) shl 8 or (data[4].toInt() and 0xFF)).toUShort()
            return HandshakePayload(version, caps, psm)
        }
    }
}
