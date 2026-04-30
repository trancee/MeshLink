package ch.trancee.meshlink.wire

/**
 * One-byte type discriminator that precedes every wire message. Codes 0x00–0x0B are defined;
 * 0x0C–0xFF are reserved. Unknown codes map to [UNKNOWN] — callers should drop such frames.
 */
internal enum class MessageType(val code: UByte) {
    HANDSHAKE(0x00u),
    KEEPALIVE(0x01u),
    ROTATION_ANNOUNCEMENT(0x02u),
    HELLO(0x03u),
    UPDATE(0x04u),
    CHUNK(0x05u),
    CHUNK_ACK(0x06u),
    NACK(0x07u),
    RESUME_REQUEST(0x08u),
    BROADCAST(0x09u),
    ROUTED_MESSAGE(0x0Au),
    DELIVERY_ACK(0x0Bu),
    UNKNOWN(0xFFu);

    companion object {
        // O(1) lookup table indexed by type byte (0x00..0x0B → enum value, else UNKNOWN).
        private val LOOKUP =
            Array(256) { code -> entries.find { it.code == code.toUByte() } ?: UNKNOWN }

        /** Returns the [MessageType] for [byte], or [UNKNOWN] if the code is not recognised. */
        fun fromByte(byte: UByte): MessageType = LOOKUP[byte.toInt()]
    }
}
