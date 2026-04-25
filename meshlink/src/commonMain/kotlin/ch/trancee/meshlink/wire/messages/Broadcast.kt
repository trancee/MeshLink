package ch.trancee.meshlink.wire

/**
 * Flood-fill broadcast message. [signature] and [signerKey] are optional — absent when `flags bit 0
 * (HAS_SIGNATURE)` is clear. [remainingHops] is NOT signed (decremented per relay).
 */
data class Broadcast(
    val messageId: ByteArray,
    val origin: ByteArray,
    val remainingHops: UByte,
    val appIdHash: UShort,
    val flags: UByte,
    val priority: Byte,
    val signature: ByteArray?,
    val signerKey: ByteArray?,
    val payload: ByteArray,
) : WireMessage {
    override val type = MessageType.BROADCAST

    override fun encode(wb: WriteBuffer) {
        wb.startTable(9)
        wb.addByteVector(0, messageId)
        wb.addByteVector(1, origin)
        wb.addUByte(2, remainingHops)
        wb.addUShort(3, appIdHash)
        wb.addUByte(4, flags)
        wb.addByte(5, priority)
        if (signature != null) wb.addByteVector(6, signature)
        if (signerKey != null) wb.addByteVector(7, signerKey)
        wb.addByteVector(8, payload)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Broadcast) return false
        return remainingHops == other.remainingHops &&
            appIdHash == other.appIdHash &&
            flags == other.flags &&
            priority == other.priority &&
            messageId.contentEquals(other.messageId) &&
            origin.contentEquals(other.origin) &&
            (signature == null) == (other.signature == null) &&
            (signature == null || signature.contentEquals(other.signature)) &&
            (signerKey == null) == (other.signerKey == null) &&
            (signerKey == null || signerKey.contentEquals(other.signerKey)) &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var h = messageId.contentHashCode()
        h = 31 * h + origin.contentHashCode()
        h = 31 * h + remainingHops.hashCode()
        h = 31 * h + appIdHash.hashCode()
        h = 31 * h + flags.hashCode()
        h = 31 * h + priority.hashCode()
        h = 31 * h + (signature?.contentHashCode() ?: 0)
        h = 31 * h + (signerKey?.contentHashCode() ?: 0)
        h = 31 * h + payload.contentHashCode()
        return h
    }

    companion object {
        fun decode(rb: ReadBuffer): Broadcast =
            Broadcast(
                messageId = rb.getByteArray(0) ?: ByteArray(16),
                origin = rb.getByteArray(1) ?: ByteArray(12),
                remainingHops = rb.getUByte(2),
                appIdHash = rb.getUShort(3),
                flags = rb.getUByte(4),
                priority = rb.getByte(5),
                signature = rb.getByteArray(6),
                signerKey = rb.getByteArray(7),
                payload = rb.getByteArray(8) ?: ByteArray(0),
            )
    }
}
