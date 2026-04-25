package ch.trancee.meshlink.messaging

/**
 * Terminal delivery-confirmed event emitted on [DeliveryPipeline.deliveryConfirmations].
 *
 * Custom [equals]/[hashCode] use [ByteArray.contentEquals]/[ByteArray.contentHashCode] for
 * [messageId] so two logically-identical events compare equal even with distinct array objects.
 */
class Delivered(val messageId: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Delivered) return false
        return messageId.contentEquals(other.messageId)
    }

    override fun hashCode(): Int = messageId.contentHashCode()
}
