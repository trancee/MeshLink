package ch.trancee.meshlink

import kotlinx.coroutines.flow.StateFlow

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

    /** The payload is larger than one message can carry. */
    TooLarge,

    /**
     * Unfinished messages hold the send queue's byte limit until they expire or fail; await
     * [MeshLink.awaitSendCapacity], then send again.
     */
    QueueFull,
}

/** A message [MeshLink.send] accepted. */
public class OutgoingMessage internal constructor(
    /** The destination. */
    public val to: NodeId,
    /** Where the message is; it only moves forward (ADR 0013 decisions 5 and 6). */
    public val status: StateFlow<DeliveryStatus>,
)

/** Where an [OutgoingMessage] is (ADR 0013 decision 5). */
public sealed interface DeliveryStatus {
    /** Accepted; nothing is on air yet. */
    public data object Queued : DeliveryStatus

    /** Waiting because this node has no link at all. */
    public data object Held : DeliveryStatus

    /** A copy of the message left this node. This does not mean the destination received it. */
    public data object Sent : DeliveryStatus

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

/** A message another node sent to this one. */
public class ReceivedMessage internal constructor(
    /** The NodeId the sender claims; the `unsecured` profile does not authenticate it. */
    public val sender: NodeId,
    /** The message bytes. */
    public val payload: ByteArray,
)
