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

    override fun encode(wb: WriteBuffer) {
        wb.startTable(8)
        wb.addByteVector(0, messageId)
        wb.addByteVector(1, origin)
        wb.addByteVector(2, destination)
        wb.addUByte(3, hopLimit)
        // Flatten List<ByteArray> to single byte array (N × 12 bytes).
        val flat = ByteArray(visitedList.size * 12)
        for ((i, peer) in visitedList.withIndex()) {
            peer.copyInto(flat, i * 12)
        }
        wb.addByteVector(4, flat)
        wb.addByte(5, priority)
        wb.addULong(6, originationTime)
        wb.addByteVector(7, payload)
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
        fun decode(rb: ReadBuffer): RoutedMessage {
            val flatVisited = rb.getByteArray(4) ?: ByteArray(0)
            val peerCount = flatVisited.size / 12
            val visited = List(peerCount) { i -> flatVisited.copyOfRange(i * 12, i * 12 + 12) }
            return RoutedMessage(
                messageId = rb.getByteArray(0) ?: ByteArray(16),
                origin = rb.getByteArray(1) ?: ByteArray(12),
                destination = rb.getByteArray(2) ?: ByteArray(12),
                hopLimit = rb.getUByte(3),
                visitedList = visited,
                priority = rb.getByte(5),
                originationTime = rb.getULong(6),
                payload = rb.getByteArray(7) ?: ByteArray(0),
            )
        }
    }
}
