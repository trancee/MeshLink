package ch.trancee.meshlink.messaging

import ch.trancee.meshlink.transfer.Priority

/** Whether an [InboundMessage] was delivered via unicast routing or broadcast flood-fill. */
internal enum class MessageKind {
    UNICAST,
    BROADCAST,
}

/**
 * A fully-decoded message received from a peer, emitted on [DeliveryPipeline.messages].
 *
 * Custom [equals]/[hashCode] delegate ByteArray fields to [contentEquals]/[contentHashCode] so that
 * two logically-identical messages compare equal even if their arrays are distinct heap objects.
 */
internal data class InboundMessage(
    val messageId: ByteArray,
    val senderId: ByteArray,
    val payload: ByteArray,
    val priority: Priority,
    val receivedAt: Long,
    val kind: MessageKind,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InboundMessage) return false
        return priority == other.priority &&
            receivedAt == other.receivedAt &&
            kind == other.kind &&
            messageId.contentEquals(other.messageId) &&
            senderId.contentEquals(other.senderId) &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var h = messageId.contentHashCode()
        h = 31 * h + senderId.contentHashCode()
        h = 31 * h + payload.contentHashCode()
        h = 31 * h + priority.hashCode()
        h = 31 * h + receivedAt.hashCode()
        h = 31 * h + kind.hashCode()
        return h
    }
}
