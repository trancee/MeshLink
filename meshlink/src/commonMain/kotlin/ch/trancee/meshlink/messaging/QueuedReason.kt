package ch.trancee.meshlink.messaging

/** Reason why a [SendResult.Queued] message was not sent immediately. */
internal enum class QueuedReason {
    /** A rate limiter is currently paused; message buffered for later retry. */
    PAUSED,

    /** No route to the destination is known yet; message buffered until a route appears. */
    ROUTE_PENDING,

    /** The concurrent-transfer limit is reached; message buffered until a slot opens. */
    TRANSFER_LIMIT,
}
