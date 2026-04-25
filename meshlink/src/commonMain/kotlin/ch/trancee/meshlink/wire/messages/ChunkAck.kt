package ch.trancee.meshlink.wire

data class ChunkAck(val messageId: ByteArray, val ackSequence: UShort, val sackBitmask: ULong) :
    WireMessage {
    override val type = MessageType.CHUNK_ACK

    override fun encode(wb: WriteBuffer) {
        wb.startTable(3)
        wb.addByteVector(0, messageId)
        wb.addUShort(1, ackSequence)
        wb.addULong(2, sackBitmask)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChunkAck) return false
        return ackSequence == other.ackSequence &&
            sackBitmask == other.sackBitmask &&
            messageId.contentEquals(other.messageId)
    }

    override fun hashCode(): Int =
        31 * (31 * messageId.contentHashCode() + ackSequence.hashCode()) + sackBitmask.hashCode()

    companion object {
        fun decode(rb: ReadBuffer): ChunkAck =
            ChunkAck(
                messageId = rb.getByteArray(0) ?: ByteArray(16),
                ackSequence = rb.getUShort(1),
                sackBitmask = rb.getULong(2),
            )
    }
}
