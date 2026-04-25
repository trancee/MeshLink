package ch.trancee.meshlink.wire

/**
 * Unicast routed message. [visitedList] is flattened to a single [ByteArray] on the wire (N × 12
 * bytes) and parsed back to `List<ByteArray>` on decode. Each element is a 12-byte peer Key Hash.
 */
data class RoutedMessage(
    val messageId: ByteArray,
    val origin: ByteArray,
    val destination: ByteArray,
    val hopLimit: UByte,
    val visitedList: List<ByteArray>,
    val priority: Byte,
    val originationTime: ULong,
    val payload: ByteArray,
) : WireMessage {
    override val type = MessageType.ROUTED_MESSAGE

    override fun encode(buffer: WriteBuffer) {
        buffer.startTable(8)
        buffer.addByteVector(0, messageId)
        buffer.addByteVector(1, origin)
        buffer.addByteVector(2, destination)
        buffer.addUByte(3, hopLimit)
        // Flatten List<ByteArray> to single byte array (N × 12 bytes).
        val flat = ByteArray(visitedList.size * 12)
        for ((i, peer) in visitedList.withIndex()) {
            peer.copyInto(flat, i * 12)
        }
        buffer.addByteVector(4, flat)
        buffer.addByte(5, priority)
        buffer.addULong(6, originationTime)
        buffer.addByteVector(7, payload)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RoutedMessage) return false
        return hopLimit == other.hopLimit &&
            priority == other.priority &&
            originationTime == other.originationTime &&
            messageId.contentEquals(other.messageId) &&
            origin.contentEquals(other.origin) &&
            destination.contentEquals(other.destination) &&
            visitedList.size == other.visitedList.size &&
            visitedList.zip(other.visitedList).all { (a, b) -> a.contentEquals(b) } &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var h = messageId.contentHashCode()
        h = 31 * h + origin.contentHashCode()
        h = 31 * h + destination.contentHashCode()
        h = 31 * h + hopLimit.hashCode()
        h = 31 * h + visitedList.sumOf { it.contentHashCode() }
        h = 31 * h + priority.hashCode()
        h = 31 * h + originationTime.hashCode()
        h = 31 * h + payload.contentHashCode()
        return h
    }

    companion object {
        fun decode(buffer: ReadBuffer): RoutedMessage {
            val flatVisited = buffer.getByteArray(4) ?: ByteArray(0)
            val peerCount = flatVisited.size / 12
            val visited = List(peerCount) { i -> flatVisited.copyOfRange(i * 12, i * 12 + 12) }
            return RoutedMessage(
                messageId = buffer.getByteArray(0) ?: ByteArray(16),
                origin = buffer.getByteArray(1) ?: ByteArray(12),
                destination = buffer.getByteArray(2) ?: ByteArray(12),
                hopLimit = buffer.getUByte(3),
                visitedList = visited,
                priority = buffer.getByte(5),
                originationTime = buffer.getULong(6),
                payload = buffer.getByteArray(7) ?: ByteArray(0),
            )
        }
    }
}
