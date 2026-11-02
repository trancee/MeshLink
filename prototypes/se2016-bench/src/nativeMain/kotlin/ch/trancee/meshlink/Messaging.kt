package ch.trancee.meshlink

import kotlinx.coroutines.flow.StateFlow

/** Opaque node identifier (fake: an arbitrary label). */
public class NodeId(public val label: String) {
    override fun equals(other: Any?): Boolean = other is NodeId && other.label == label
    override fun hashCode(): Int = label.hashCode()
    override fun toString(): String = "NodeId($label)"
}

/** Per-message options (ADR 0007 shape; the fake ignores them). */
public class SendOptions(public val directOnly: Boolean = false, public val deliveryReceipt: Boolean = false) {
    public companion object {
        public val Default: SendOptions = SendOptions()
    }
}

/** Synchronous outcome of [MeshLink.send]. */
public sealed class SendResult {
    public class Accepted internal constructor(public val message: OutgoingMessage) : SendResult()
    public class Rejected internal constructor(public val reason: RejectionReason) : SendResult()
}

public enum class RejectionReason { NotRunning, PayloadSize, QueueFull }

public class OutgoingMessage internal constructor(
    public val messageId: Long,
    public val destination: NodeId,
    public val status: StateFlow<DeliveryStatus>,
)

public sealed class DeliveryStatus {
    public data object Queued : DeliveryStatus()
    public data object Sent : DeliveryStatus()
}

/** A message taken from [MeshLink.messages]. [payload] is a fresh copy on every access, as in the API prototype. */
public class ReceivedMessage internal constructor(
    public val messageId: Long,
    public val sender: NodeId,
    payload: ByteArray,
    public val hops: Int,
) {
    private val content = payload
    public val payload: ByteArray get() = content.copyOf()
}

public enum class MeshState { Stopped, Running }
