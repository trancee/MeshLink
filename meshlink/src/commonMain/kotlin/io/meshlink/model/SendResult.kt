package io.meshlink.model

/**
 * Result of a [MeshLinkApi.send] call, indicating whether the message
 * was sent immediately or queued for deferred delivery.
 */
sealed class SendResult {
    /** The message ID assigned to this send operation. */
    abstract val messageId: MessageId

    /** Message was accepted and transmission has started (direct or routed). */
    data class Sent(override val messageId: MessageId) : SendResult()

    /** Message was queued for deferred delivery. */
    data class Queued(override val messageId: MessageId, val reason: QueueReason) : SendResult()
}

/** Why a message was queued instead of sent immediately. */
enum class QueueReason {
    /** The mesh is paused — message will be sent on [MeshLinkApi.resume]. */
    PAUSED,

    /** No route exists to the destination — message will be sent when a route is discovered. */
    ROUTE_PENDING,
}
