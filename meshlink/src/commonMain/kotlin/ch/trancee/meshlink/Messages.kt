package ch.trancee.meshlink

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How [MeshLink.send] sends one message (ADR 0007 decision 7).
 *
 * @property directOnly whether only a link to the destination may carry the message, with `hop_limit` 1, so
 *   no other node relays or holds it (ADR 0013 decision 8). Until such a link forms the message waits as
 *   [DeliveryStatus.Held], and it expires if none forms. Its statuses and receipt are those of any message.
 * @property readReceipt whether the message asks its recipient for a read receipt (ADR 0013 decision 10);
 *   [ReadReceiptRequest.Default] follows [MeshLinkConfig.readReceiptsRequestByDefault].
 */
public class SendOptions(
    public val directOnly: Boolean = false,
    public val readReceipt: ReadReceiptRequest = ReadReceiptRequest.Default,
)

/**
 * Whether a message asks its recipient for a read receipt (ADR 0013 decision 10). Only a message that
 * asks can reach [DeliveryStatus.Read], and only if the recipient allows read receipts and its app
 * calls [ReceivedMessage.markRead]; a refusal looks like an unread message.
 */
public enum class ReadReceiptRequest {
    /** As the node's config says by default. */
    Default,

    /** Ask for a read receipt. */
    Request,

    /** Don't ask for one. */
    DoNotRequest,
}

/** What [MeshLink.send] did with a message (ADR 0007 decision 7). It never throws for these. */
public sealed interface SendResult {
    /** The node took the message; [message] reports its [DeliveryStatus]. */
    public class Accepted internal constructor(
        /** The accepted message. */
        public val message: OutgoingMessage,
    ) : SendResult

    /** The node refused the message for [reason]; nothing was sent. */
    public data class Rejected(
        /** Why the node refused the message. */
        public val reason: RejectionReason,
    ) : SendResult
}

/** Why [MeshLink.send] refused a message. */
public enum class RejectionReason {
    /** The node is not [MeshState.Running] or [MeshState.Blocked]. */
    NotRunning,

    /** The destination is this node's own NodeId. */
    UnknownPeer,

    /**
     * The payload is larger than the configured cap: [Limits.maxUnicastPayload] for [MeshLink.send],
     * [Limits.maxBroadcastPayload] for [MeshLink.broadcast].
     */
    TooLarge,

    /**
     * Unfinished messages hold the send queue's byte limit until they are delivered, expire or fail; await
     * [MeshLink.awaitSendCapacity], then send again.
     */
    QueueFull,

    /** The hop limit [MeshLink.broadcast] was given is not 1 to 255. */
    InvalidHopLimit,
}

/** A message [MeshLink.send] or [MeshLink.broadcast] accepted. */
public class OutgoingMessage internal constructor(
    /** The destination; `null` for a broadcast, which goes to every node within its hop limit. */
    public val to: NodeId?,
    /** Where the message is; it only moves forward (ADR 0013 decisions 5 and 6). */
    public val status: StateFlow<DeliveryStatus>,
)

/**
 * Where an [OutgoingMessage] is (ADR 0013 decisions 5 and 6). A message starts `Queued` and only
 * moves forward: `Queued` is never re-entered, `Sent` and `Held` may alternate, and `Delivered`,
 * `Read`, `Expired` and `Failed` are final, except that a read receipt moves `Delivered` on to `Read`.
 */
public sealed interface DeliveryStatus {
    /**
     * Accepted; nothing is on air yet. In `Classical` and `PostQuantum` a message also waits here
     * while MeshLink looks up its destination's certificate, none being held or the one held having
     * expired, or while an earlier message to that destination opens the conversation.
     */
    public data object Queued : DeliveryStatus

    /**
     * Waiting for a way to the destination: a send found no link at all, or, for a direct-only send
     * ([SendOptions.directOnly]), no link to the destination. In the medium and high density classes it
     * is also `Held` when a flooded send found no route within `resend-base` + `resend-per-hop` ×
     * `u6-flood-radius` (1.6 s). Nodes in range may hold a copy meanwhile and hand it over when the
     * destination comes back (spec "Holding"); this node keeps retrying until the message expires.
     */
    public data object Held : DeliveryStatus

    /** A copy of the message left this node. This does not mean the destination received it. */
    public data object Sent : DeliveryStatus

    /**
     * Final: the destination's delivery receipt arrived; its app took the message. A receipt that
     * arrives after the origin stopped retrying still counts, until the message would turn
     * [Expired]. In `classical` and `post-quantum` the receipt is sealed end to end, in the reply to
     * a first message or in the conversation's session, so only the destination can produce it; a
     * relay can't forge one. In `unsecured` any node on the route could forge it.
     */
    public data object Delivered : DeliveryStatus

    /**
     * Final: the destination's read receipt arrived; its app marked the message read
     * ([ReceivedMessage.markRead]). Only a message that asked for a read receipt
     * ([SendOptions.readReceipt]) gets here, from `Delivered` or straight from `Sent` if the delivery
     * receipt was lost. A lost read receipt leaves the message `Delivered`, which therefore doesn't
     * mean unread. The node watches at most 1,000 delivered messages for their read receipts and
     * forgets the oldest first. A read receipt is sealed end to end like a delivery receipt; in
     * `unsecured` any node on the route could forge it.
     */
    public data object Read : DeliveryStatus

    /** Final: no receipt arrived by the message's expiry plus 30 s of clock skew. */
    public data object Expired : DeliveryStatus

    /** Final: a local cause ended the message. */
    public data class Failed(
        /** The local cause. */
        public val reason: FailureReason,
    ) : DeliveryStatus
}

/** The local cause of [DeliveryStatus.Failed] (ADR 0013 decision 9). */
public enum class FailureReason {
    /** The node stopped or closed before the message ended. */
    Stopped,
}

/**
 * A message another node sent to this one, or to every node if [broadcast].
 *
 * Thread-safe.
 */
public class ReceivedMessage internal constructor(
    /**
     * The sender's NodeId. The `unsecured` profile does not authenticate it; in the `classical` and
     * `post-quantum` profiles a broadcast's sender is the NodeId of its verified origin signature.
     */
    public val sender: NodeId,
    /** The message bytes. */
    public val payload: ByteArray,
    /** Whether the sender broadcast the message to the mesh ([MeshLink.broadcast]) rather than to this node. */
    public val broadcast: Boolean,
    /**
     * Whether the sender asked for a read receipt ([SendOptions.readReceipt]); [markRead] sends one only
     * if this node also allows them ([MeshLinkConfig.readReceiptsSend]). Never for a broadcast.
     */
    public val readReceiptRequested: Boolean = false,
    // Sends the read receipt; null if none may go.
    private val reader: (() -> Unit)? = null,
) {
    private val marked = MutableStateFlow(false)

    /**
     * Marks the message read: if the sender asked for a read receipt and this node allows them, a read
     * receipt goes back to the sender within 2,000 ms, batched with the others to it, even after the
     * message's lifetime. Does nothing otherwise, and nothing again on a second call. The receipt is
     * best effort: nothing acknowledges it, and if it is lost the sender sees `Delivered`.
     */
    public fun markRead() {
        if (marked.compareAndSet(expect = false, update = true)) reader?.invoke()
    }
}
