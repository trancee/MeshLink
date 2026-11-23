package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.FailureReason
import ch.trancee.meshlink.MeshState
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.OutgoingMessage
import ch.trancee.meshlink.RejectionReason
import ch.trancee.meshlink.Requirement
import ch.trancee.meshlink.SendOptions
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

/** What [Post] needs to send its own messages: the [Endpoint] of the mesh's profile. */
internal interface Origin : Endpoint {
    /** [entry]'s message drafted for its first send, or `null` while it can't be yet. */
    fun draft(entry: Outbox.Entry): Draft?

    /** [draft]'s destination tag for the current epoch. */
    fun destination(draft: Draft): ByteArray

    /** The tag [draft]'s route requests come from for the current epoch, which their replies go to. */
    fun origin(draft: Draft): ByteArray

    /** [draft]'s frame for the current epoch with [hopLimit]. */
    fun frame(
        draft: Draft,
        hopLimit: Int,
    ): ByteArray
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
 * A secured [profile]'s mesh may not carry the unsecured container (spec "End-to-end"): it sends each
 * message as a first message sealed to its destination's certificate (spec "First messages"), which
 * its [lookups] find for nodes it is not linked to (spec "Certificate lookup"); a message waits
 * `Queued` until the certificate arrives. Its broadcasts are signed.
 *
 * [send] and [awaitCapacity] are thread-safe; everything else runs on [scope]'s serial dispatcher.
 */
internal class Post(
    crypto: CryptoProvider,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val lifetimeSeconds: Int,
    profile: WireProfile,
    private val density: () -> DensityClass,
) : Mail {
    private val secured = profile != WireProfile.UNSECURED
    private val capacity = Capacity()
    private val lanes = Lanes()
    private val outbox = Outbox(clock, scope, capacity, density, ::transmit)
    private val self = MutableStateFlow<NodeId?>(null)

    private val routes = Routes()

    /** Messages received for this node. */
    val inbox = Inbox()

    /** The peers this node lists. */
    val roster = Roster()

    private val certificates = Lookups(crypto, clock, scope, profile, lanes, density)

    /** A secured node's certificate lookups; an `unsecured` node has none. */
    val lookups = certificates.takeIf { secured }

    private val tags = if (secured) SecuredPeerTags(crypto, certificates) else UnsecuredPeerTags(crypto)
    private val routed = RoutedPeers(clock, scope, routes, roster, tags)
    private val releases = Releases(outbox, routed, density) { end.destination(it) }

    // A delivery receipt goes back on the reverse route its messages came on, else like any unicast.
    private val receipts: Receipts =
        Receipts(clock, scope, { to, tombstones, expiry ->
            val acknowledger = unsecuredEnd.acknowledger
            val receipt = acknowledger.receipt(to, tombstones, expiry)
            router.originate(acknowledger.destination(receipt), unsecuredEnd.tag(), density(), search = false) {
                acknowledger.parcel(receipt, it)
            }
        }, outbox::delivered)
    private val unsecuredEnd: UnsecuredEnd =
        UnsecuredEnd(crypto, clock, inbox, receipts, routed::heard, releases::found)
    private val securedEnd = SecuredEnd(crypto, clock, inbox, certificates, routed::heard, releases::found)
    private val end: Origin = if (secured) securedEnd else unsecuredEnd
    private val codec = BroadcastCodec(crypto, secured)
    private val floods = Floods(lanes, codec, Plumtree(lanes, clock, scope, density)) { inbox.offer(it) }
    private val router: Router = Router(clock, lanes, routes, crypto::randomBytes, end, Handoff(floods, lookups))

    /** This node's broadcasts. */
    val broadcasts = Broadcasts(clock, scope, capacity, floods, codec, lifetimeSeconds)

    /** Starts a run of the node with [identity]. */
    fun begin(identity: InstallationIdentity) {
        unsecuredEnd.begin(identity.nodeId)
        codec.begin(identity)
        self.value = NodeId(identity.nodeId)
        outbox.begin()
        receipts.begin()
        broadcasts.begin()
        lookups?.begin(releases::arrived)
    }

    /** A secured node's [keys] for a new session, which its first messages are sealed with and to. */
    fun certify(keys: SecuredKeys) {
        securedEnd.certify(keys)
    }

    /** Ends the run: unfinished messages and broadcasts fail with `Stopped`, and no routed peer is listed. */
    suspend fun end() {
        outbox.end()
        receipts.end()
        broadcasts.end()
        routed.end()
        lookups?.end()
    }

    /** Accepts or rejects a message of [payload] to [to], sent as [options] say, from a node in [state]. */
    fun send(
        to: NodeId,
        payload: ByteArray,
        options: SendOptions,
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
            routed.sending(to)
            outbox.add(Outbox.Entry(to, bytes, expiry, status, deadline, options.directOnly))
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
        outbox.linked(peer)
        return floods.linked(peer)
    }

    override fun unlinked(peer: NodeId) {
        lanes.close(peer)
        floods.unlinked(peer)
        router.unlinked(peer)
        routed.update()
        if (lanes.peers.isEmpty()) outbox.unreachable()
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

    // Each attempt tags the same draft for the epoch it is written in (spec "Origin behavior"). After
    // its first send on a route, a message is sent once more by a new [search] if it is still
    // unfinished `resend-base` + `resend-per-hop` × the route's hop count later.
    private fun transmit(
        entry: Outbox.Entry,
        search: Boolean = false,
    ) {
        val draft = entry.draft ?: end.draft(entry)?.also { entry.draft = it }
        val reachable = if (entry.directOnly) entry.to in lanes.peers else lanes.peers.isNotEmpty()
        if (!reachable) return entry.hold()
        val sealed = draft ?: return
        val parcel = { hopLimit: Int ->
            Parcel(sealed.messageId, relayed = false, { end.frame(sealed, hopLimit) }) {
                if (it == Settled.SENT) entry.sent()
            }
        }
        // A direct-only message goes only to its destination's lane, which no node relays (ADR 0013 decision 8).
        if (entry.directOnly) {
            lanes.put(entry.to, parcel(1))
            entry.sentMillis = clock.monotonicMillis()
        } else {
            val hops = router.originate(end.destination(sealed), end.origin(sealed), density(), search, parcel)
            entry.sentMillis = clock.monotonicMillis()
            if (hops != null && !entry.resent) {
                entry.resent = true
                val resend = RESEND_BASE_MILLIS + RESEND_PER_HOP_MILLIS * hops
                outbox.after(entry, resend) { transmit(entry, search = true) }
            }
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L

        // resend-base.
        const val RESEND_BASE_MILLIS = 1_000L
    }
}

/**
 * Releases the messages waiting in [outbox] as soon as what they wait for arrives: a route to their
 * [destination], or their destination's certificate. Either also updates the [routed] peers.
 */
private class Releases(
    private val outbox: Outbox,
    private val routed: RoutedPeers,
    private val density: () -> DensityClass,
    private val destination: (Draft) -> ByteArray,
) {
    // A found route takes the messages waiting for its destination at once, unless the low class floods them.
    fun found(tag: ByteArray) {
        routed.update()
        if (density() == DensityClass.LOW) return
        outbox.found(FLUSH_GAP_MILLIS) { entry -> entry.draft?.let { destination(it).contentEquals(tag) } == true }
    }

    // A certificate of [peer] arrived: the messages waiting for it are sealed and sent at once.
    fun arrived(peer: NodeId) {
        routed.update()
        outbox.found(0) { it.to == peer }
    }

    private companion object {
        // A message sent on a route just found waits until relays forget its earlier copies: the
        // unicast seen window, and one hop's allowance for their later forwarding.
        const val FLUSH_GAP_MILLIS = Router.SEEN_WINDOW_MILLIS + RESEND_PER_HOP_MILLIS
    }
}

// resend-per-hop.
private const val RESEND_PER_HOP_MILLIS = 200L

// A node blocked on its identity has no run: start couldn't begin one.
internal fun accepting(state: MeshState): Boolean =
    state == MeshState.Running ||
        (state is MeshState.Blocked && Requirement.IdentityUnavailable !in state.requirements)
