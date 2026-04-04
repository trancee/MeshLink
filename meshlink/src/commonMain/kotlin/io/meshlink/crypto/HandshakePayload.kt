package io.meshlink.crypto

import io.meshlink.wire.getUShortBE
import io.meshlink.wire.putUShortBE

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
        data.putUShortBE(0, protocolVersion)
        data[2] = capabilityFlags.toByte()
        data.putUShortBE(3, l2capPsm)
        return data
    }

    companion object {
        const val SIZE = 5
        const val CAP_L2CAP: UByte = 0x01u

        fun decode(data: ByteArray): HandshakePayload {
            require(data.size >= SIZE) { "Handshake payload too short: ${data.size} < $SIZE" }
            val version = data.getUShortBE(0)
            val caps = data[2].toUByte()
            val psm = data.getUShortBE(3)
            return HandshakePayload(version, caps, psm)
        }
    }
}
