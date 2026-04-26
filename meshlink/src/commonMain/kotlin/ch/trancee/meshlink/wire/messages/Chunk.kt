package ch.trancee.meshlink.wire

internal data class Chunk(
    val messageId: ByteArray,
    val seqNo: UShort,
    val totalChunks: UShort,
    val payload: ByteArray,
) : WireMessage {
    override val type = MessageType.CHUNK

    override fun encode(buffer: WriteBuffer) {
        buffer.startTable(4)
        buffer.addByteVector(0, messageId)
        buffer.addUShort(1, seqNo)
        buffer.addUShort(2, totalChunks)
        buffer.addByteVector(3, payload)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Chunk) return false
        return seqNo == other.seqNo &&
            totalChunks == other.totalChunks &&
            messageId.contentEquals(other.messageId) &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var h = messageId.contentHashCode()
        h = 31 * h + seqNo.hashCode()
        h = 31 * h + totalChunks.hashCode()
        h = 31 * h + payload.contentHashCode()
        return h
    }

    companion object {
        fun decode(buffer: ReadBuffer): Chunk =
            Chunk(
                messageId = buffer.getByteArray(0) ?: ByteArray(16),
                seqNo = buffer.getUShort(1),
                totalChunks = buffer.getUShort(2),
                payload = buffer.getByteArray(3) ?: ByteArray(0),
            )
    }
}
