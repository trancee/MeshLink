package ch.trancee.meshlink.transport

internal enum class FrameType(val code: Byte) {
    DATA(0x00),
    ACK(0x01),
    CLOSE(0x02);

    companion object {
        fun fromByte(code: Byte): FrameType? = entries.firstOrNull { it.code == code }
    }
}

internal class L2capFrame(val type: FrameType, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is L2capFrame) return false
        return type == other.type && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * type.hashCode() + payload.contentHashCode()
}

internal object L2capFrameCodec {
    fun encode(type: FrameType, payload: ByteArray): ByteArray {
        if (payload.size > 65535)
            throw IllegalArgumentException("Payload exceeds max L2CAP frame size: ${payload.size}")
        val frame = ByteArray(3 + payload.size)
        frame[0] = type.code
        frame[1] = (payload.size and 0xFF).toByte()
        frame[2] = ((payload.size shr 8) and 0xFF).toByte()
        payload.copyInto(frame, 3)
        return frame
    }

    fun decode(frame: ByteArray): L2capFrame {
        if (frame.size < 3)
            throw IllegalArgumentException("Frame too short: ${frame.size} bytes, minimum 3")
        val typeCode = frame[0]
        val frameType =
            FrameType.fromByte(typeCode)
                ?: throw IllegalArgumentException(
                    "Unknown frame type: 0x${(typeCode.toInt() and 0xFF).toString(16).padStart(2, '0')}"
                )
        val length = (frame[1].toInt() and 0xFF) or ((frame[2].toInt() and 0xFF) shl 8)
        if (3 + length > frame.size)
            throw IllegalArgumentException(
                "Frame truncated: declared ${length} payload bytes but only ${frame.size - 3} available"
            )
        val payload = frame.copyOfRange(3, 3 + length)
        return L2capFrame(frameType, payload)
    }
}
