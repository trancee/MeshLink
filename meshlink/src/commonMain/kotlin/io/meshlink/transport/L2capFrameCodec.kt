package io.meshlink.transport

/**
 * A decoded L2CAP frame containing the frame type and payload.
 */
data class L2capFrame(
    val type: Byte,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other !is L2capFrame) return false
        return type == other.type && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * type.hashCode() + payload.contentHashCode()
}

/**
 * Length-prefixed framing codec for L2CAP CoC data.
 *
 * Wire format (4-byte header + payload):
 * ```
 *  byte 0       : frame type (DATA / ACK / CLOSE)
 *  bytes 1-3    : payload length as 3-byte little-endian unsigned integer
 *  bytes 4..N   : payload
 * ```
 *
 * Maximum payload size: 16 777 215 bytes (2^24 − 1).
 */
object L2capFrameCodec {

    /** Regular data frame. */
    const val TYPE_DATA: Byte = 0x00

    /** Acknowledgement frame. */
    const val TYPE_ACK: Byte = 0x01

    /** Close / shutdown frame. */
    const val TYPE_CLOSE: Byte = 0x02

    private const val HEADER_SIZE = 4
    private const val MAX_PAYLOAD_SIZE = 0xFF_FF_FF // 16 777 215

    /**
     * Encode a [payload] with the given frame [type] into the wire format.
     *
     * @throws IllegalArgumentException if [payload] exceeds the maximum size.
     */
    fun encode(type: Byte, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD_SIZE) {
            "Payload size ${payload.size} exceeds maximum $MAX_PAYLOAD_SIZE"
        }
        val frame = ByteArray(HEADER_SIZE + payload.size)
        frame[0] = type
        val len = payload.size
        frame[1] = (len and 0xFF).toByte()
        frame[2] = ((len shr 8) and 0xFF).toByte()
        frame[3] = ((len shr 16) and 0xFF).toByte()
        payload.copyInto(frame, HEADER_SIZE)
        return frame
    }

    /**
     * Decode a wire-format [frame] into an [L2capFrame].
     *
     * @throws IllegalArgumentException if the frame is too short or the
     *   declared length does not match the actual payload bytes.
     */
    fun decode(frame: ByteArray): L2capFrame {
        require(frame.size >= HEADER_SIZE) {
            "Frame too short: ${frame.size} bytes (minimum $HEADER_SIZE)"
        }
        val type = frame[0]
        val len = (frame[1].toInt() and 0xFF) or
            ((frame[2].toInt() and 0xFF) shl 8) or
            ((frame[3].toInt() and 0xFF) shl 16)
        require(frame.size == HEADER_SIZE + len) {
            "Frame length mismatch: header says $len bytes but frame has ${frame.size - HEADER_SIZE}"
        }
        val payload = frame.copyOfRange(HEADER_SIZE, HEADER_SIZE + len)
        return L2capFrame(type, payload)
    }
}
