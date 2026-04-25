package ch.trancee.meshlink.wire

/**
 * Delivery ACK. [signature] is optional — absent when `flags bit 0 (HAS_SIGNATURE)` is clear. No
 * signerPublicKey field — verified against the pinned Ed25519 key (saves 32B per ACK).
 */
data class DeliveryAck(
    val messageId: ByteArray,
    val recipientId: ByteArray,
    val flags: UByte,
    val signature: ByteArray?,
) : WireMessage {
    override val type = MessageType.DELIVERY_ACK

    override fun encode(wb: WriteBuffer) {
        wb.startTable(4)
        wb.addByteVector(0, messageId)
        wb.addByteVector(1, recipientId)
        wb.addUByte(2, flags)
        if (signature != null) wb.addByteVector(3, signature)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeliveryAck) return false
        return flags == other.flags &&
            messageId.contentEquals(other.messageId) &&
            recipientId.contentEquals(other.recipientId) &&
            (signature == null) == (other.signature == null) &&
            (signature == null || signature.contentEquals(other.signature))
    }

    override fun hashCode(): Int {
        var h = messageId.contentHashCode()
        h = 31 * h + recipientId.contentHashCode()
        h = 31 * h + flags.hashCode()
        h = 31 * h + (signature?.contentHashCode() ?: 0)
        return h
    }

    companion object {
        fun decode(rb: ReadBuffer): DeliveryAck =
            DeliveryAck(
                messageId = rb.getByteArray(0) ?: ByteArray(16),
                recipientId = rb.getByteArray(1) ?: ByteArray(12),
                flags = rb.getUByte(2),
                signature = rb.getByteArray(3),
            )
    }
}
