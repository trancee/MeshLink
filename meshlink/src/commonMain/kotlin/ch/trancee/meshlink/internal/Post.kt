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
    /** A link to [peer] completed its hello: returns the `CATCH_UP` it opens with if kept (spec "Catch-up"). */
    fun linked(peer: NodeId): LinkFrame

    /** The last link to [peer] closed. */
    fun unlinked(peer: NodeId)

    /** The next frame for [peer], waiting for one. */
    suspend fun next(peer: NodeId): Letter

    /** A link-local `CATCH_UP`, `IHAVE`, `GRAFT` or `PRUNE` arrived from [peer] on a link whose hello completed. */
    fun plumtree(
        peer: NodeId,
        frame: LinkFrame,
    )

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
 * forwarding"), and floods [broadcasts] (spec "Flood forwarding"). [density] is the node's current
 * density class.
 *
 * A [secured] mesh may not carry the unsecured container (spec "End-to-end"), and its own unicast
 * containers come later: it keeps its messages `Queued` without writing them and processes no unicast
 * frame for itself, but relays the secured containers of other nodes. Its broadcasts are signed.
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

    private val routes = Routes()

    /** Messages received for this node. */
    val inbox = Inbox()

    /** The peers this node lists. */
    val roster = Roster()

    private val routed = RoutedPeers(crypto, clock, scope, routes, roster)

    // A found route takes the messages waiting for its destination at once, unless the low class floods them.
    private val end: UnsecuredEnd =
        UnsecuredEnd(crypto, clock, inbox, secured, routed::heard) { tag ->
            routed.update()
            if (density() != DensityClass.LOW) outbox.found(FLUSH_GAP_MILLIS) { end.tag(it.to).contentEquals(tag) }
        }
    private val codec = BroadcastCodec(crypto, secured)
    private val floods = Floods(lanes, codec, Plumtree(lanes, clock, scope, density)) { inbox.offer(it) }
    private val router = Router(clock, lanes, routes, crypto::randomBytes, end, floods)

    /** This node's broadcasts. */
    val broadcasts = Broadcasts(clock, scope, capacity, floods, codec, lifetimeSeconds)

    /** Starts a run of the node with [identity]. */
    fun begin(identity: InstallationIdentity) {
        end.begin(identity.nodeId)
        codec.begin(identity)
        self.value = NodeId(identity.nodeId)
        outbox.begin()
        broadcasts.begin()
    }

    /** Ends the run: unfinished messages and broadcasts fail with `Stopped`, and no routed peer is listed. */
    suspend fun end() {
        outbox.end()
        broadcasts.end()
        routed.end()
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
        val add = {
            if (!secured) routed.sending(to)
            outbox.add(Outbox.Entry(to, end.draft(to, bytes, expiry), bytes.size, status, deadline))
        }
        scope.launch { add() }.invokeOnCompletion {
            if (it != null) status.value = DeliveryStatus.Failed(FailureReason.Stopped)
        }
        return SendResult.Accepted(OutgoingMessage(to, status.asStateFlow()))
    }

    /** Returns once the send queue is below its cap. */
    suspend fun awaitCapacity() {
        capacity.await()
    }

    override fun linked(peer: NodeId): LinkFrame {
        lanes.open(peer)
        outbox.linked()
        return floods.linked(peer)
    }

    override fun unlinked(peer: NodeId) {
        lanes.close(peer)
        floods.unlinked(peer)
        router.unlinked(peer)
        routed.update()
        if (lanes.peers.isEmpty() && !secured) outbox.unreachable()
    }

    override suspend fun next(peer: NodeId): Letter {
        val parcel = lanes.next(peer)
        // A write that failed goes back to the front of the lane, for the peer's next or kept link.
        return Letter(parcel.render()) { if (it == Settled.FAILED) lanes.putBack(peer, parcel) else parcel.settle(it) }
    }

    override fun plumtree(
        peer: NodeId,
        frame: LinkFrame,
    ) {
        floods.plumtree(peer, frame, clock.wallClockMillis())
    }

    override fun received(
        peer: NodeId,
        frame: ByteArray,
    ) {
        router.received(peer, frame)
    }

    // Each attempt tags the frame for the epoch it is written in (spec "Origin behavior"). After its
    // first send on a route, a message is sent once more by a new [search] if it is still unfinished
    // `resend-base` + `resend-per-hop` × the route's hop count later.
    private fun transmit(
        entry: Outbox.Entry,
        search: Boolean = false,
    ) {
        if (secured) return
        if (lanes.peers.isEmpty()) return entry.hold()
        val parcel = { hopLimit: Int ->
            Parcel(entry.draft.messageId, relayed = false, { end.frame(entry.draft, hopLimit) }) {
                if (it == Settled.SENT) entry.sent()
            }
        }
        val hops = router.originate(end.tag(entry.to), density(), search, parcel)
        entry.sentMillis = clock.monotonicMillis()
        if (hops != null && !entry.resent) {
            entry.resent = true
            outbox.after(entry, RESEND_BASE_MILLIS + RESEND_PER_HOP_MILLIS * hops) { transmit(entry, search = true) }
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L

        // resend-base and resend-per-hop.
        const val RESEND_BASE_MILLIS = 1_000L
        const val RESEND_PER_HOP_MILLIS = 200L

        // A message sent on a route just found waits until relays forget its earlier copies: the
        // unicast seen window, and one hop's allowance for their later forwarding.
        const val FLUSH_GAP_MILLIS = Router.SEEN_WINDOW_MILLIS + RESEND_PER_HOP_MILLIS
    }
}

// A node blocked on its identity has no run: start couldn't begin one.
internal fun accepting(state: MeshState): Boolean =
    state == MeshState.Running ||
        (state is MeshState.Blocked && Requirement.IdentityUnavailable !in state.requirements)
