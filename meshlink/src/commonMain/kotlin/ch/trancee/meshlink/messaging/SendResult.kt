package ch.trancee.meshlink.messaging

/** Result returned by [DeliveryPipeline.send]. */
sealed interface SendResult {
    /** Message was accepted and handed off for immediate transmission. */
    object Sent : SendResult

    /** Message was accepted but held in the store-and-forward buffer. */
    data class Queued(val reason: QueuedReason) : SendResult
}
