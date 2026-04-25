package ch.trancee.meshlink.wire

data class ResumeRequest(val messageId: ByteArray, val bytesReceived: UInt) : WireMessage {
    override val type = MessageType.RESUME_REQUEST

    override fun encode(wb: WriteBuffer) {
        wb.startTable(2)
        wb.addByteVector(0, messageId)
        wb.addUInt(1, bytesReceived)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ResumeRequest) return false
        return bytesReceived == other.bytesReceived && messageId.contentEquals(other.messageId)
    }

    override fun hashCode(): Int = 31 * messageId.contentHashCode() + bytesReceived.hashCode()

    companion object {
        fun decode(rb: ReadBuffer): ResumeRequest =
            ResumeRequest(
                messageId = rb.getByteArray(0) ?: ByteArray(16),
                bytesReceived = rb.getUInt(1),
            )
    }
}
