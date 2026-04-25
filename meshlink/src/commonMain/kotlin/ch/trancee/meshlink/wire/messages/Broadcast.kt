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

    override fun encode(buffer: WriteBuffer) {
        buffer.startTable(9)
        buffer.addByteVector(0, messageId)
        buffer.addByteVector(1, origin)
        buffer.addUByte(2, remainingHops)
        buffer.addUShort(3, appIdHash)
        buffer.addUByte(4, flags)
        buffer.addByte(5, priority)
        if (signature != null) buffer.addByteVector(6, signature)
        if (signerKey != null) buffer.addByteVector(7, signerKey)
        buffer.addByteVector(8, payload)
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
        fun decode(buffer: ReadBuffer): Broadcast =
            Broadcast(
                messageId = buffer.getByteArray(0) ?: ByteArray(16),
                origin = buffer.getByteArray(1) ?: ByteArray(12),
                remainingHops = buffer.getUByte(2),
                appIdHash = buffer.getUShort(3),
                flags = buffer.getUByte(4),
                priority = buffer.getByte(5),
                signature = buffer.getByteArray(6),
                signerKey = buffer.getByteArray(7),
                payload = buffer.getByteArray(8) ?: ByteArray(0),
            )
    }
}
