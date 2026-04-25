package ch.trancee.meshlink.wire

data class ChunkAck(val messageId: ByteArray, val ackSequence: UShort, val sackBitmask: ULong) :
    WireMessage {
    override val type = MessageType.CHUNK_ACK

    override fun encode(buffer: WriteBuffer) {
        buffer.startTable(3)
        buffer.addByteVector(0, messageId)
        buffer.addUShort(1, ackSequence)
        buffer.addULong(2, sackBitmask)
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
        fun decode(buffer: ReadBuffer): ChunkAck =
            ChunkAck(
                messageId = buffer.getByteArray(0) ?: ByteArray(16),
                ackSequence = buffer.getUShort(1),
                sackBitmask = buffer.getULong(2),
            )
    }
}
