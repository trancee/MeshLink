package ch.trancee.meshlink.wire

internal data class Hello(val sender: ByteArray, val seqNo: UShort, val routeDigest: UInt) :
    WireMessage {
    override val type = MessageType.HELLO

    override fun encode(buffer: WriteBuffer) {
        buffer.startTable(3)
        buffer.addByteVector(0, sender)
        buffer.addUShort(1, seqNo)
        buffer.addUInt(2, routeDigest)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Hello) return false
        return seqNo == other.seqNo &&
            routeDigest == other.routeDigest &&
            sender.contentEquals(other.sender)
    }

    override fun hashCode(): Int =
        31 * (31 * sender.contentHashCode() + seqNo.hashCode()) + routeDigest.hashCode()

    companion object {
        fun decode(buffer: ReadBuffer): Hello =
            Hello(
                sender = buffer.getByteArray(0) ?: ByteArray(12),
                seqNo = buffer.getUShort(1),
                routeDigest = buffer.getUInt(2),
            )
    }
}
