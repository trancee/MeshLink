package ch.trancee.meshlink.wire

data class Nack(val messageId: ByteArray, val reason: UByte) : WireMessage {
    override val type = MessageType.NACK

    override fun encode(wb: WriteBuffer) {
        wb.startTable(2)
        wb.addByteVector(0, messageId)
        wb.addUByte(1, reason)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Nack) return false
        return reason == other.reason && messageId.contentEquals(other.messageId)
    }

    override fun hashCode(): Int = 31 * messageId.contentHashCode() + reason.hashCode()

    companion object {
        fun decode(rb: ReadBuffer): Nack =
            Nack(messageId = rb.getByteArray(0) ?: ByteArray(16), reason = rb.getUByte(1))
    }
}
