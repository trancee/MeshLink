package ch.trancee.meshlink.wire

data class Nack(val messageId: ByteArray, val reason: UByte) : WireMessage {
    override val type = MessageType.NACK

    override fun encode(buffer: WriteBuffer) {
        buffer.startTable(2)
        buffer.addByteVector(0, messageId)
        buffer.addUByte(1, reason)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Nack) return false
        return reason == other.reason && messageId.contentEquals(other.messageId)
    }

    override fun hashCode(): Int = 31 * messageId.contentHashCode() + reason.hashCode()

    companion object {
        fun decode(buffer: ReadBuffer): Nack =
            Nack(messageId = buffer.getByteArray(0) ?: ByteArray(16), reason = buffer.getUByte(1))
    }
}
