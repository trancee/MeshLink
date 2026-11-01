package ch.trancee.meshlink

import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/**
 * Per-message options for [MeshLink.send]. Receipts are opt-in and only allowed when the preset
 * allows them.
 *
 * @property directOnly fail instead of routing through other nodes.
 * @property deliveryReceipt report [DeliveryStatus.Delivered] once the destination application took the message.
 * @property readReceipt report [DeliveryStatus.Read] once the destination application called [ReceivedMessage.markRead].
 * @property messageLifetimeSeconds lifetime of this message; `null` uses the preset's lifetime, which is also the maximum.
 */
public class SendOptions(
    public val directOnly: Boolean = false,
    public val deliveryReceipt: Boolean = false,
    public val readReceipt: Boolean = false,
    public val messageLifetimeSeconds: Int? = null,
) {
    /** Shared defaults. */
    public companion object {
        /** Routed, no receipts, preset lifetime. */
        public val Default: SendOptions = SendOptions()
    }
}

/**
 * Options for [MeshLink.broadcast].
 *
 * @property messageLifetimeSeconds how long relays keep forwarding; `null` uses the preset's lifetime.
 */
public class BroadcastOptions(public val messageLifetimeSeconds: Int? = null) {
    /** Shared defaults. */
    public companion object {
        /** Preset lifetime. */
        public val Default: BroadcastOptions = BroadcastOptions()
    }
}

/**
 * Synchronous outcome of [MeshLink.send] and [MeshLink.broadcast].
 *
 * @sample ch.trancee.meshlink.samples.sendAndTrack
 */
public sealed class SendResult {
    /** The node took responsibility for [message]; follow [OutgoingMessage.status]. */
    public class Accepted internal constructor(public val message: OutgoingMessage) : SendResult() {
        override fun toString(): String = "Accepted(${message.messageId})"
    }

    /** The message was not queued, because of [reason]. */
    public class Rejected internal constructor(public val reason: RejectionReason) : SendResult() {
        override fun toString(): String = "Rejected($reason)"
    }
}

/** Why a message was rejected before it was queued. Stable [code]s in 100–199. */
public enum class RejectionReason(public val code: Int) {
    /** The node is not [MeshState.Running]. */
    NotRunning(100),

    /** The payload is empty or larger than the preset's `maxPayloadBytes`. */
    PayloadSize(101),

    /** The destination is the local node. */
    InvalidDestination(102),

    /** The hop limit is below 1 or above the preset's `maxHopLimit`. */
    HopLimitOutOfRange(103),

    /** The message lifetime is below 1 second or above the preset's lifetime. */
    LifetimeOutOfRange(104),

    /** A receipt was requested that the preset does not allow. */
    ReceiptNotAllowed(105),
}

/** A message this node accepted for sending. */
public class OutgoingMessage internal constructor(
    /** Identifier, also carried by the matching [ReceivedMessage]. */
    public val messageId: MessageId,
    /** Destination, or `null` for a mesh broadcast. */
    public val destination: NodeId?,
    /** Delivery progress; ends in [DeliveryStatus.Sent] when no receipt was requested. */
    public val status: StateFlow<DeliveryStatus>,
)

/** Progress of an [OutgoingMessage]. `Sent` is not `Delivered`. */
public sealed class DeliveryStatus {
    /** Waiting to be handed to the first hop. */
    public data object Queued : DeliveryStatus()

    /** Handed to the mesh and acknowledged hop by hop; final when no delivery receipt was requested. */
    public data object Sent : DeliveryStatus()

    /** The destination is unreachable; held until a route appears or the message lifetime passes. */
    public data object Held : DeliveryStatus()

    /** The destination application took the message (delivery receipt). */
    public data object Delivered : DeliveryStatus()

    /** The destination application marked the message read (read receipt). */
    public data object Read : DeliveryStatus()

    /** Delivery stopped because of [reason]. */
    public data class Failed(public val reason: FailureReason) : DeliveryStatus()

    /** The message lifetime passed before delivery. */
    public data object Expired : DeliveryStatus()
}

/** Why delivery failed after acceptance. Stable [code]s in 200–299. */
public enum class FailureReason(public val code: Int) {
    /** No route and store-and-forward is off. */
    NoRoute(200),

    /** [SendOptions.directOnly] was set and the destination is not a direct peer. */
    NoDirectLink(201),

    /** The destination did not acknowledge after the maximum number of attempts. */
    NotAcknowledged(202),
}

/**
 * A message the local application took from [MeshLink.messages].
 *
 * @sample ch.trancee.meshlink.samples.receiveAndMarkRead
 */
public class ReceivedMessage internal constructor(
    /** Identifier shared with the sender's [OutgoingMessage]. */
    public val messageId: MessageId,
    /** Originating node. */
    public val sender: NodeId,
    payload: ByteArray,
    /** Number of hops the message travelled; 1 for a direct peer. */
    public val hops: Int,
    /** `true` for a mesh broadcast. */
    public val isBroadcast: Boolean,
    /** Whether the sender asked for a read receipt. */
    public val readReceiptRequested: Boolean,
    /** When the local node received the message. */
    public val receivedAt: Instant,
    private val onRead: (ReceivedMessage) -> Unit,
) {
    private val content: ByteArray = payload.copyOf()
    private var read = false

    /** Application data; a fresh copy on every access. */
    public val payload: ByteArray get() = content.copyOf()

    /**
     * Sends a read receipt if the sender requested one; otherwise does nothing. Idempotent and
     * callable from any thread.
     */
    public fun markRead() {
        if (read || !readReceiptRequested) return
        read = true
        onRead(this)
    }
}
