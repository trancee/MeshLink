package ch.trancee.meshlink.messaging

/** Terminal failure outcome for a delivery attempt, emitted on [DeliveryPipeline.transferFailures]. */
enum class DeliveryOutcome {
    /** No delivery confirmation was received within the TTL window. */
    TIMED_OUT,

    /** The destination peer is not reachable via any known route. */
    DESTINATION_UNREACHABLE,

    /** The underlying transfer layer reported a non-recoverable send failure. */
    SEND_FAILED,
}
