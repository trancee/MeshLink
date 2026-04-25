package ch.trancee.meshlink.wire

data class Chunk(
    val messageId: ByteArray,
    val seqNum: UShort,
    val totalChunks: UShort,
    val payload: ByteArray,
) : WireMessage {
    override val type = MessageType.CHUNK

    override fun encode(wb: WriteBuffer) {
        wb.startTable(4)
        wb.addByteVector(0, messageId)
        wb.addUShort(1, seqNum)
        wb.addUShort(2, totalChunks)
        wb.addByteVector(3, payload)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Chunk) return false
        return seqNum == other.seqNum &&
            totalChunks == other.totalChunks &&
            messageId.contentEquals(other.messageId) &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var h = messageId.contentHashCode()
        h = 31 * h + seqNum.hashCode()
        h = 31 * h + totalChunks.hashCode()
        h = 31 * h + payload.contentHashCode()
        return h
    }

    companion object {
        fun decode(rb: ReadBuffer): Chunk =
            Chunk(
                messageId = rb.getByteArray(0) ?: ByteArray(16),
                seqNum = rb.getUShort(1),
                totalChunks = rb.getUShort(2),
                payload = rb.getByteArray(3) ?: ByteArray(0),
            )
    }
}
