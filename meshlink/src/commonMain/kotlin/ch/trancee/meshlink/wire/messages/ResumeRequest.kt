package ch.trancee.meshlink.wire

internal data class ResumeRequest(val messageId: ByteArray, val bytesReceived: UInt) : WireMessage {
    override val type = MessageType.RESUME_REQUEST

    override fun encode(buffer: WriteBuffer) {
        buffer.startTable(2)
        buffer.addByteVector(0, messageId)
        buffer.addUInt(1, bytesReceived)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ResumeRequest) return false
        return bytesReceived == other.bytesReceived && messageId.contentEquals(other.messageId)
    }

    override fun hashCode(): Int = 31 * messageId.contentHashCode() + bytesReceived.hashCode()

    companion object {
        fun decode(buffer: ReadBuffer): ResumeRequest =
            ResumeRequest(
                messageId = buffer.getByteArray(0) ?: ByteArray(16),
                bytesReceived = buffer.getUInt(1),
            )
    }
}
