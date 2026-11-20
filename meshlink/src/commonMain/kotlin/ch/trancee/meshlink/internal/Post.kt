package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.FailureReason
import ch.trancee.meshlink.MeshState
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.OutgoingMessage
import ch.trancee.meshlink.RejectionReason
import ch.trancee.meshlink.Requirement
import ch.trancee.meshlink.SendResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What [Links] needs from the node's messaging; called on the node's dispatcher. */
internal interface Mail {
    /** A link to [peer] completed its hello. */
    fun linked(peer: NodeId)

    /** The last link to [peer] closed. */
    fun unlinked(peer: NodeId)

    /** The next frame for [peer], waiting for one. */
    suspend fun next(peer: NodeId): Letter

    /** A routed frame arrived from [peer] on a link whose hello completed. */
    fun received(
        peer: NodeId,
        frame: ByteArray,
    )
}

/** A frame ready to write: its routed [frame], and [settle] to report how the write ended. */
internal class Letter(
    val frame: ByteArray,
    val settle: (Settled) -> Unit,
)

/** How a frame's write ended. */
internal enum class Settled {
    /** Every record was written. */
    SENT,

    /** The peer refused the frame's stream with `BUSY`. */
    BUSY,

    /** A record could not be written: the link is closing or broken. */
    FAILED,
}

/**
 * A node's messaging: validates and sends its messages, relays other nodes' frames, and accepts the
 * `MESSAGE` frames for this node into [inbox] (ADR 0007 decisions 7 and 9, ADR 0013, spec "Unicast
 * forwarding"). [density] is the node's current density class.
 *
 * A [secured] mesh may not carry the unsecured container (spec "End-to-end"), and its own containers
 * come later: it keeps its messages `Queued` without writing them and processes no frame for itself,
 * but relays the secured containers of other nodes.
 *
 * [send] and [awaitCapacity] are thread-safe; everything else runs on [scope]'s serial dispatcher.
 */
internal class Post(
    crypto: CryptoProvider,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val lifetimeSeconds: Int,
    private val secured: Boolean,
    private val density: () -> DensityClass,
) : Mail {
    private val capacity = Capacity()
    private val lanes = Lanes()
    private val outbox = Outbox(clock, scope, capacity, density, ::transmit)
    private val self = MutableStateFlow<NodeId?>(null)

    /** Messages received for this node. */
    val inbox = Inbox()

    private val end = UnsecuredEnd(crypto, clock, inbox)
    private val kinds = if (secured) SECURED_KINDS else UNSECURED_KINDS
    private val router = Router(clock, lanes, kinds, { tag, now -> !secured && end.owns(tag, now) }, end::deliver)

    /** Starts a run of the node whose NodeId is [nodeId]. */
    fun begin(nodeId: ByteArray) {
        end.begin(nodeId)
        self.value = NodeId(nodeId)
        outbox.begin()
    }

    /** Ends the run: unfinished messages fail with `Stopped`. */
    suspend fun end() {
        outbox.end()
    }

    /** Ends [inbox] for good. */
    fun close() {
        inbox.close()
    }

    /** Accepts or rejects a message of [payload] to [to] from a node in [state]. */
    fun send(
        to: NodeId,
        payload: ByteArray,
        state: MeshState,
    ): SendResult {
        val reason =
            when {
                !accepting(state) -> RejectionReason.NotRunning
                to == self.value -> RejectionReason.UnknownPeer
                payload.size > UnsecuredMessaging.PAYLOAD_MAX -> RejectionReason.TooLarge
                !capacity.reserve(payload.size) -> RejectionReason.QueueFull
                else -> null
            }
        if (reason != null) return SendResult.Rejected(reason)
        val expiry = clock.wallClockMillis() / MILLIS_PER_SECOND + lifetimeSeconds
        val status = MutableStateFlow<DeliveryStatus>(DeliveryStatus.Queued)
        val bytes = payload.copyOf()
        // A node that closes before the message reaches the outbox cancels this before it runs.
        val deadline = (expiry + UnsecuredMessaging.CLOCK_SKEW_SECONDS) * MILLIS_PER_SECOND
        val add = { outbox.add(Outbox.Entry(to, end.draft(to, bytes, expiry), bytes.size, status, deadline)) }
        scope.launch { add() }.invokeOnCompletion {
            if (it != null) status.value = DeliveryStatus.Failed(FailureReason.Stopped)
        }
        return SendResult.Accepted(OutgoingMessage(to, status.asStateFlow()))
    }

    /** Returns once the send queue is below its cap. */
    suspend fun awaitCapacity() {
        capacity.await()
    }

    override fun linked(peer: NodeId) {
        lanes.open(peer)
        outbox.linked()
    }

    override fun unlinked(peer: NodeId) {
        lanes.close(peer)
        router.unlinked(peer)
        if (lanes.peers.isEmpty() && !secured) outbox.unreachable()
    }

    override suspend fun next(peer: NodeId): Letter {
        val parcel = lanes.next(peer)
        // A write that failed goes back to the front of the lane, for the peer's next or kept link.
        return Letter(parcel.render()) { if (it == Settled.FAILED) lanes.putBack(peer, parcel) else parcel.settle(it) }
    }

    override fun received(
        peer: NodeId,
        frame: ByteArray,
    ) {
        router.received(peer, frame)
    }

    // Each attempt tags the frame for the epoch it is written in (spec "Origin behavior").
    private fun transmit(entry: Outbox.Entry) {
        if (secured) return
        if (lanes.peers.isEmpty()) return entry.hold()
        router.originate(entry.draft.messageId, end.tag(entry.to), density(), { end.frame(entry.draft, it) }) {
            if (it == Settled.SENT) entry.sent()
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}

private val SECURED_KINDS = setOf(Container.IK_INITIATOR, Container.TRANSPORT)
private val UNSECURED_KINDS = setOf(Container.UNSECURED)

// A node blocked on its identity has no run: start couldn't begin one.
private fun accepting(state: MeshState): Boolean =
    state == MeshState.Running ||
        (state is MeshState.Blocked && Requirement.IdentityUnavailable !in state.requirements)
