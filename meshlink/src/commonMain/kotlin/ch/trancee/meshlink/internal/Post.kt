package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.DensityClass
import ch.trancee.meshlink.DiagnosticEvent
import ch.trancee.meshlink.FailureReason
import ch.trancee.meshlink.MeshLinkConfig
import ch.trancee.meshlink.MeshState
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.OutgoingMessage
import ch.trancee.meshlink.ReadReceiptRequest
import ch.trancee.meshlink.RejectionReason
import ch.trancee.meshlink.Requirement
import ch.trancee.meshlink.SendOptions
import ch.trancee.meshlink.SendResult
import ch.trancee.meshlink.wireProfile
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
 * density class. Sends and takes read receipts as [config] allows (spec "Read receipts"), and
 * reports to [diagnostics] each sent message it stops tracking for one.
 *
 * A secured profile's mesh may not carry the unsecured container (spec "End-to-end"): it sends each
 * message with its end-to-end session to the destination, else as a first message sealed to the
 * destination's certificate (spec "First messages" and "Sessions"), which its [lookups] find for nodes
 * it is not linked to (spec "Certificate lookup"); a message waits `Queued` until the certificate
 * arrives or the session opens. Its broadcasts are signed.
 *
 * [send] and [awaitCapacity] are thread-safe; everything else runs on [scope]'s serial dispatcher.
 */
internal class Post(
    crypto: CryptoProvider,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val config: MeshLinkConfig,
    private val density: () -> DensityClass,
    diagnostics: DiagnosticsHub,
) : Mail {
    private val lifetimeSeconds = config.messageLifetimeSeconds
    private val profile = config.securityProfile.wireProfile
    private val secured = profile != WireProfile.UNSECURED
    private val capacity = Capacity()
    private val lanes = Lanes(config.broadcastPriority)
    private val reading = ReadTracking { diagnostics.report(DiagnosticEvent.ReadTrackingEvicted(it)) }
    private val outbox =
        Outbox(clock, scope, capacity, density, reading) { entry, retry ->
            // A large message's timed retry searches its route anew (D-58).
            transmit(entry, search = retry && entry.large)
        }
    private val posting = ReceiptPost(clock, scope, config, outbox, { router }, density)
    private val self = MutableStateFlow<NodeId?>(null)

    private val routes = Routes()

    /** Messages received for this node. */
    val inbox = Inbox(diagnostics::report)

    /** The peers this node lists. */
    val roster = Roster()

    private val certificates = Lookups(crypto, Pace(clock, density), scope, profile, lanes, diagnostics)

    /** A secured node's certificate lookups; an `unsecured` node has none. */
    val lookups = certificates.takeIf { secured }

    private val tags = if (secured) SecuredPeerTags(crypto, certificates) else UnsecuredPeerTags(crypto)
    private val routed = RoutedPeers(clock, scope, routes, roster, tags, diagnostics)
    private val releases = Releases(outbox, routed, density) { end.destination(it) }

    // A delivery receipt goes back on the reverse route its messages came on, else like any unicast.
    private val receipts: Receipts<NodeId> =
        Receipts(
            clock,
            scope,
            { to, tombstones, expiry ->
                val acknowledger = unsecuredEnd.acknowledger
                val receipt = acknowledger.receipt(to, tombstones, expiry)
                val reach = Reach.receipt(density())
                router.originate(acknowledger.destination(receipt), unsecuredEnd.tag(), reach, false) {
                    acknowledger.parcel(receipt, it)
                }
            },
            outbox::delivered,
            posting.reads { to, ids, expiry ->
                posting.originate(unsecuredEnd.acknowledger.readReceipt(to, ids, expiry))
            },
        )
    private val unsecuredEnd: UnsecuredEnd =
        UnsecuredEnd(crypto, clock, inbox, receipts, routed::heard, releases::found)

    // A first message's answer goes alone at once; session receipts are batched like any.
    private val securedReceipts: Receipts<Reply> =
        Receipts(
            clock,
            scope,
            { reply, tombstones, expiry ->
                securedEnd.acknowledger.reply(reply, tombstones, expiry)
            },
            outbox::delivered,
            posting.reads { to, ids, expiry ->
                securedEnd.acknowledger.readReceipt(to, ids, expiry)
            },
        ) {
            it is Reply.First
        }
    private val securedEnd: SecuredEnd =
        SecuredEnd(
            crypto,
            clock,
            inbox,
            certificates,
            securedReceipts,
            SecuredHooks(routed::heard, releases::found, releases::arrived, posting::originate),
        )
    private val end: Origin = if (secured) securedEnd else unsecuredEnd
    private val codec = BroadcastCodec(crypto, secured, diagnostics::report)
    private val floods =
        Floods(
            lanes,
            codec,
            Plumtree(lanes, clock, scope, density),
            Tombstones(Holding(config.holding, diagnostics::report), Derivations(crypto)::tombstoneCommitment),
            diagnostics::report,
        ) {
            inbox.offer(it)
        }
    private val router: Router =
        Router(clock, lanes, routes, crypto::randomBytes, end, Handoff(floods, lookups, diagnostics::report))
    private val origins = Origins(clock, lanes, routes, router, outbox, end)

    /** This node's broadcasts. */
    val broadcasts =
        Broadcasts(clock, scope, capacity, floods, codec, config)

    /** Starts a run of the node with [identity]. */
    fun begin(identity: InstallationIdentity) {
        unsecuredEnd.begin(identity.nodeId)
        codec.begin(identity)
        self.value = NodeId(identity.nodeId)
        outbox.begin()
        receipts.begin()
        securedReceipts.begin()
        broadcasts.begin()
        lookups?.begin(releases::arrived)
    }

    /** A secured node's static keys, which its first messages are sealed with and to, and their rotation. */
    val keys: KeyRing get() = securedEnd.keys

    /**
     * Ends the run: unfinished messages and broadcasts fail with `Stopped`, no routed peer is listed,
     * and the frames held for others are dropped.
     */
    suspend fun end() {
        outbox.end()
        receipts.end()
        securedReceipts.end()
        securedEnd.sessions.clear()
        broadcasts.end()
        routed.end()
        lookups?.end()
        floods.holding.clear()
        floods.tombstones.clear()
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
                payload.size > config.limits.maxUnicastPayload -> RejectionReason.TooLarge
                !capacity.reserve(payload.size) -> RejectionReason.QueueFull
                else -> null
            }
        if (reason != null) return SendResult.Rejected(reason)
        val expiry = clock.wallClockMillis() / MILLIS_PER_SECOND + lifetimeSeconds
        val status = MutableStateFlow<DeliveryStatus>(DeliveryStatus.Queued)
        val bytes = payload.copyOf()
        // A node that closes before the message reaches the outbox cancels this before it runs.
        val deadline = (expiry + UnsecuredMessaging.CLOCK_SKEW_SECONDS) * MILLIS_PER_SECOND
        val readReceipt =
            when (options.readReceipt) {
                ReadReceiptRequest.Default -> {
                    if (config.readReceipts.requestByDefault) {
                        ReadReceiptRequest.Request
                    } else {
                        ReadReceiptRequest.DoNotRequest
                    }
                }

                else -> {
                    options.readReceipt
                }
            }
        val resolved = SendOptions(options.directOnly || !config.routedUnicast, readReceipt)
        val add = {
            routed.sending(to)
            outbox.add(Outbox.Entry(to, bytes, expiry, status, deadline, resolved))
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
        return floods.linked(peer, clock.wallClockMillis())
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
        return Letter(parcel.render()) {
            lanes.written(peer)
            if (it == Settled.FAILED) lanes.putBack(peer, parcel) else parcel.settle(it)
        }
    }

    override fun plumtree(
        peer: NodeId,
        frame: LinkFrame,
    ) {
        floods.plumtree(peer, frame, clock.wallClockMillis())
        if (frame is LinkFrame.CatchUp) router.handOver.caughtUp(peer, frame.held)
    }

    override fun received(
        peer: NodeId,
        frame: ByteArray,
    ) {
        router.received(peer, frame)
    }

    // Each attempt tags the same draft for the epoch it is written in (spec "Origin behavior"), until
    // the draft lapses; [origins] places it, after a new [search] if this is its resend or a large
    // message's timed retry.
    private fun transmit(
        entry: Outbox.Entry,
        search: Boolean,
    ) {
        // Only a secured draft lapses: an `unsecured` one has no session to end.
        val draft = entry.drafted(end) { secured && securedEnd.lapsed(it, entry.retries - entry.draftedAt) }
        val reachable = if (entry.directOnly) entry.to in lanes.peers else lanes.peers.isNotEmpty()
        if (!reachable) return entry.held()
        // A copy still being written makes another one pointless (D-57); one still waiting is replaced.
        val sealed = draft?.takeUnless { lanes.writing(it.messageId) } ?: return
        origins.place(entry, sealed, Reach.of(density(), entry.large), search) { transmit(entry, search = true) }
    }
}

/**
 * Places this node's drafted messages, made into frames by [end] (spec "Origin behavior" and "Size
 * classes"). A direct-only message, and a large one whose destination is linked, goes only to its
 * destination's lane; any other goes by [router]. After its first send on a route, a message is sent
 * once more by a new search if it is still unfinished `resend-base` + `resend-per-hop` × the route's
 * hop count later. A flooded small message in the medium and high classes, and a large message, which
 * is never flooded and waits for a route (D-55), are `Held` if no route turns up within `resend-base`
 * + `resend-per-hop` × `u6-flood-radius`. A frame of a finished message is not written (D-57).
 */
private class Origins(
    private val clock: Clock,
    private val lanes: Lanes,
    private val routes: Routes,
    private val router: Router,
    private val outbox: Outbox,
    private val end: Origin,
) {
    /** Places [entry]'s [sealed] draft as [reach] says; [resend] sends it again by a new search. */
    fun place(
        entry: Outbox.Entry,
        sealed: Draft,
        reach: Reach,
        search: Boolean,
        resend: () -> Unit,
    ) {
        val parcel = { hopLimit: Int ->
            Parcel(sealed.messageId, false, { end.frame(sealed, hopLimit) }, { !entry.quiet }, Parcel.Kind.MESSAGE) {
                if (it == Settled.SENT) entry.sent()
            }
        }
        // A direct-only message goes only to its destination's lane, which no node relays (ADR 0013
        // decision 8); so does a large message whose destination is linked.
        if (entry.directOnly || (entry.large && entry.to in lanes.peers)) {
            lanes.put(entry.to, parcel(1))
            entry.sentMillis = clock.monotonicMillis()
        } else {
            val destination = end.destination(sealed)
            val hops = router.originate(destination, end.origin(sealed), reach, search, parcel)
            if (hops == null) searching(entry, destination, reach) else routed(entry, hops, resend)
        }
    }

    // A message sent on a route is sent once more by a new search if it is still unfinished in time.
    private fun routed(
        entry: Outbox.Entry,
        hops: Int,
        resend: () -> Unit,
    ) {
        entry.sentMillis = clock.monotonicMillis()
        if (entry.resent) return
        entry.resent = true
        outbox.after(entry, RESEND_BASE_MILLIS + RESEND_PER_HOP_MILLIS * hops) { resend() }
    }

    // A flooded message is sent now, a large one is not (D-55); in the medium and high classes either
    // is Held if no route, a large one's found by route request, turns up in time.
    private fun searching(
        entry: Outbox.Entry,
        destination: ByteArray,
        reach: Reach,
    ) {
        if (reach != Reach.ROUTE) entry.sentMillis = clock.monotonicMillis()
        if (reach == Reach.FLOOD) return
        outbox.after(entry, FLOODED_RESEND_MILLIS) {
            val route = routes.active(destination, clock.monotonicMillis(), discovered = reach == Reach.ROUTE)
            if (route == null) entry.held()
        }
    }

    private companion object {
        // resend-base.
        const val RESEND_BASE_MILLIS = 1_000L

        // How long a flooded send waits for a route before its message is Held.
        const val FLOODED_RESEND_MILLIS = RESEND_BASE_MILLIS + RESEND_PER_HOP_MILLIS * Router.FLOOD_RADIUS
    }
}

/**
 * Sends this node's receipts that go without a route discovery of their own: on the reverse route their
 * messages came on, else like any unicast, by [router]. Makes the read receipts [config] allows, each
 * expiring the default message lifetime after it is made (spec "Read receipts"), and hands those that
 * arrive to [outbox].
 */
private class ReceiptPost(
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val config: MeshLinkConfig,
    private val outbox: Outbox,
    private val router: () -> Router,
    private val density: () -> DensityClass,
) {
    /** Sends [outgoing]. */
    fun originate(outgoing: Outgoing) {
        router().originate(outgoing.destination, outgoing.origin, Reach.of(density()), search = false) { hopLimit ->
            Parcel(outgoing.messageId, relayed = false, { outgoing.frame(hopLimit) }) {}
        }
    }

    /** This node's read receipts, each of which [send] sends to its node for its messages with its expiry. */
    fun reads(send: (to: NodeId, messageIds: List<ByteArray>, expiry: UInt) -> Unit): Reads =
        Reads(scope, config.readReceipts.send, { to, ids -> send(to, ids, expiry()) }) { from, ids ->
            outbox.delivered(from, ids, read = true)
        }

    private fun expiry(): UInt = (clock.wallClockMillis() / MILLIS_PER_SECOND + config.messageLifetimeSeconds).toUInt()
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
    // A found route takes the messages waiting for its destination at once; in the low class, which
    // floods small messages, only the large ones.
    fun found(tag: ByteArray) {
        routed.update()
        val low = density() == DensityClass.Low
        outbox.found(FLUSH_GAP_MILLIS) { entry ->
            (entry.large || !low) && entry.draft?.let { destination(it).contentEquals(tag) } == true
        }
    }

    // A certificate of [peer] arrived: the messages waiting for it are sealed and sent at once.
    fun arrived(peer: NodeId) {
        routed.update()
        outbox.found(0) { it.to == peer }
    }

    private companion object {
        // route-flush-gap: a message sent on a route just found waits until its earlier flooded copy
        // has settled (D-61).
        const val FLUSH_GAP_MILLIS = 1_200L
    }
}

// resend-per-hop.
private const val RESEND_PER_HOP_MILLIS = 200L

private const val MILLIS_PER_SECOND = 1_000L

// A node blocked on its identity has no run: start couldn't begin one.
// This entry's draft for this attempt: its current one, or a new one from [end] before its first send
// and after its draft [lapsed]. A new draft keeps the message's tombstone (D-51).
private fun Outbox.Entry.drafted(
    end: Origin,
    lapsed: (Draft) -> Boolean,
): Draft? {
    if (draft?.let(lapsed) == true) draft = null
    return draft ?: end.draft(this)?.also {
        draft = it
        tombstone = it.tombstone
        draftedAt = retries
    }
}

internal fun accepting(state: MeshState): Boolean =
    state is MeshState.Running ||
        (state is MeshState.Blocked && Requirement.IdentityUnavailable !in state.requirements)
