package ch.trancee.meshlink.wire

data class Hello(val sender: ByteArray, val seqNo: UShort, val routeDigest: UInt) : WireMessage {
    override val type = MessageType.HELLO

    override fun encode(wb: WriteBuffer) {
        wb.startTable(3)
        wb.addByteVector(0, sender)
        wb.addUShort(1, seqNo)
        wb.addUInt(2, routeDigest)
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
        fun decode(rb: ReadBuffer): Hello =
            Hello(
                sender = rb.getByteArray(0) ?: ByteArray(12),
                seqNo = rb.getUShort(1),
                routeDigest = rb.getUInt(2),
            )
    }
}
