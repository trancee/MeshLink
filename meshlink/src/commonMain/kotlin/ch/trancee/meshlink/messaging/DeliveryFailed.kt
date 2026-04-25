package ch.trancee.meshlink.messaging

/**
 * Terminal delivery-failure event emitted on [DeliveryPipeline.transferFailures].
 *
 * Custom [equals]/[hashCode] use [ByteArray.contentEquals]/[ByteArray.contentHashCode] for
 * [messageId] so two logically-identical events compare equal even with distinct array objects.
 */
class DeliveryFailed(val messageId: ByteArray, val outcome: DeliveryOutcome) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeliveryFailed) return false
        return outcome == other.outcome && messageId.contentEquals(other.messageId)
    }

    override fun hashCode(): Int = 31 * messageId.contentHashCode() + outcome.hashCode()
}
