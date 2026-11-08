package meshlink.routingsim.routing

/** U4 route request, flooded within [radius] hops. */
class RouteRequest(
    val requestId: Int,
    val origin: Long,
    val originSeq: Int,
    val dst: Long,
    val dstSeq: Int,
    val hops: Int,
    val radius: Int,
) : Packet() {
    override val isControl get() = true

    override fun sizeBytes(s: RoutingSizes) = s.headerBytes + 4 + 2 * s.tagBytes + 4 + 4 + s.hopFieldBytes + 1
}

/** U4 route reply, unicast back along the reverse route. */
class RouteReply(
    val origin: Long,
    val dst: Long,
    val dstSeq: Int,
    val hops: Int,
    /** U6: the message that reached the destination through the data ring (−1 = none). */
    val ackId: Int = -1,
    /** Stage F A1: expiry of the acknowledged message (simulation metadata for the tombstone; not on the wire). */
    val ackExpiresAt: Long = 0,
) : Packet() {
    override val isControl get() = true

    override fun sizeBytes(s: RoutingSizes) =
        s.headerBytes + 2 * s.tagBytes + 4 + s.hopFieldBytes + 4 + if (ackId >= 0) s.messageIdBytes + s.tombstoneKeyBytes else 0
}

/** Stage D K2: end-to-end delivery ack of a routed message, retracing the message's path hop by hop. */
class DeliveryAck(
    val origin: Long,
    val dst: Long,
    val msgId: Int,
    /** Stage F A1: expiry of the acknowledged message (simulation metadata for the tombstone; not on the wire). */
    val expiresAt: Long = 0,
) : Packet() {
    override val isControl get() = true

    override fun sizeBytes(s: RoutingSizes) = s.headerBytes + 2 * s.tagBytes + s.messageIdBytes + s.tombstoneKeyBytes
}

/** U4 route error listing unreachable destinations. */
class RouteError(
    val dsts: LongArray,
    val seqs: IntArray,
) : Packet() {
    override val isControl get() = true

    override fun sizeBytes(s: RoutingSizes) = s.headerBytes + 1 + dsts.size * (s.tagBytes + 4)
}

/**
 * U4: AODV-like on-demand routing (RFC 3561, simplified).
 *
 * Expanding-ring route requests (radius 1, 3, 5, 7, then the network diameter, with 2 retries at
 * full radius), ring traversal time 2·node traversal·(radius + 2), route reply along the reverse
 * route, destination sequence numbers, intermediate replies from fresh routes, active route
 * timeout 30 s refreshed by use, route errors to precursors on link loss. Link sensing comes
 * from the link layer (no Hello messages), so idle control traffic is zero.
 * A relay that loses its next hop buffers the message and starts its own discovery (local
 * repair). Messages wait without the hold gate while a discovery runs; when it fails they enter
 * store-and-forward holding (gated by the minimum hold time) and re-discover every 10 s.
 *
 * Stage C variants, switched by [RoutingParams]:
 * - U4sf ([RoutingParams.sfTuned]): held messages re-discover with exponential backoff (2 s doubling
 *   to 30 s) instead of every 10 s, on every new link (at most every 5 s per destination), and
 *   flush at once when a route request from the destination itself passes by.
 * - U6 ([RoutingParams.dataRingHops] > 0, plus sfTuned): with no route, the first waiting message is
 *   flooded [RoutingParams.dataRingHops] hops as a data ring. Ring relays learn the way back; the
 *   destination delivers and answers with a route reply that acknowledges the message, so later
 *   messages are unicast. A ring relay with a fresh route forwards the copy along it. If no reply
 *   comes, the usual expanding-ring route requests continue beyond the data ring, then holding.
 * - Relay preference ([RoutingParams.relayPreference]): nodes that sleep (background iOS) delay
 *   relaying flooded requests and ring copies, so the first copy, and so the route, uses awake relays.
 *
 * Stage D variants:
 * - K1 conversation lifetime ([RoutingParams.convKeepMs]): a route a message uses stays valid this long
 *   after the last message (both directions at every hop) instead of the 30 s active route time.
 * - K2 delivery ack ([RoutingParams.deliveryAck]): the destination acknowledges each routed message; the
 *   ack retraces the message's path (each relay remembers the previous hop for a few seconds) and re-arms
 *   the routes to both ends at every hop. A sender with no ack in time drops the route and re-sends once.
 * - K3 cached reverse path ([RoutingParams.reverseCacheMs]): every phone that a routed message, ring copy
 *   or route request passes keeps the previous hop as the route back to the source for this long.
 * - S sparse sender ([RoutingParams.sparseFloodBelow]): a phone whose own density estimate is below the
 *   threshold floods its own messages (as the low class does), whatever its class.
 * - F full-range discovery ([RoutingParams.fullRangeDiscovery]): with no route, a route request for the whole
 *   mesh goes out at once, alongside the 3-hop data ring, instead of the expanding ring 1, 3, 5, 7, then all.
 */
class OnDemand(
    private val port: RoutingPort,
    private val self: Int,
) : Routing {
    private class Route(
        var next: Int,
        var hops: Int,
        var seq: Int,
        var expires: Long,
        var valid: Boolean,
    ) {
        val precursors = HashSet<Int>(4)
    }

    private class Discovery(
        var radius: Int,
        var retries: Int,
        var generation: Int,
    )

    private val routes = HashMap<Long, Route>()
    private val requestsSeen = HashMap<Long, Long>()
    private val discoveries = HashMap<Long, Discovery>()
    private val pending = PendingRoutes(port)
    private val retryScheduled = HashSet<Long>()
    private val retryLevel = HashMap<Long, Int>()
    private val lastLinkUpRetry = HashMap<Long, Long>()
    private val ringSeen = SeenCache(port)
    private val ringed = HashSet<Int>()

    /** K2: previous hop of a relayed routed message (for the ack), by message id, with its expiry. */
    private val ackBack = HashMap<Int, Pair<Int, Long>>()

    /** K2: routed messages this phone sent and has not seen acknowledged. */
    private val unacked = HashMap<Int, DataPacket>()
    private var ownSeq = (port.now() / 1000).toInt()
    private var requestId = 0
    private var started = false

    // Stage F state.

    /** A1/A4: messages this phone originated (its outbox), with their expiry. */
    private val ownIds = HashMap<Int, Long>()

    /** A1: tombstones {message ID, k} by message ID, with the message's expiry; oldest first. */
    private val tombstones = LinkedHashMap<Int, Long>()

    /** A1: message IDs this phone claimed at catch-up and has not received yet. */
    private val claimed = HashSet<Int>()
    private var nextRetag = 0L

    /** RL1: per incoming link, token count and last refill time. */
    private val buckets = HashMap<Int, DoubleArray>()

    /** A3: outstanding lookups by target NodeId. */
    private class Lookup(
        val startedAt: Long,
    ) {
        val salts = HashSet<Long>()
        var attempts = 0
        var level = 0
        var gen = 0
    }

    private val lookups = HashMap<Int, Lookup>()
    private val lookupSeen = HashMap<Long, Long>()
    private val lookupBack = HashMap<Long, Pair<Int, Long>>()
    private val holdEvictor = HoldBudget.Evictor { id -> pending.removeId(id) }

    private fun isLarge(p: DataPacket) = port.params.largeMessages && p.payloadBytes > port.params.holdMaxMsgBytes

    private fun own(id: Int) = ownIds.containsKey(id)

    private fun start() {
        if (started) return
        started = true
        port.after(1_000) { sweepLoop() }
    }

    private fun sweepLoop() {
        pending.sweep()
        val t = port.now()
        if (requestsSeen.size > 256) requestsSeen.entries.removeAll { it.value < t }
        if (routes.size > 64) routes.entries.removeAll { !it.value.valid && t - it.value.expires > 30_000 }
        if (ackBack.size > 64) ackBack.entries.removeAll { it.value.second < t }
        if (ownIds.size > 256) ownIds.entries.removeAll { it.value < t }
        if (lookupSeen.size > 256) lookupSeen.entries.removeAll { it.value < t }
        if (lookupBack.size > 256) lookupBack.entries.removeAll { it.value.second < t }
        if (port.params.holdForOthers) {
            if (tombstones.size > 64) tombstones.entries.removeAll { it.value < t }
            if (t >= nextRetag) {
                nextRetag = t + port.params.retagCheckMs
                retagOwn()
            }
        }
        port.after(1_000) { sweepLoop() }
    }

    /** A1: the origin re-tags its own held copies when the destination's tag (or its own) has rotated. */
    private fun retagOwn() {
        val moved = ArrayList<DataPacket>()
        for (p in pending.all()) {
            if (!own(p.id)) continue
            val q = port.retag(p) ?: continue
            pending.removeId(p.id)
            moved.add(q)
        }
        for (q in moved) {
            port.count("retag")
            forward(q, -1)
        }
    }

    // ---- stage F A1: catch-up, claims, tombstones ----

    private fun addTombstone(
        id: Int,
        expiresAt: Long,
    ) {
        if (tombstones.containsKey(id)) return
        tombstones[id] = expiresAt
        port.count("tombStored")
        while (tombstones.size > port.params.tombstoneMax) {
            val it = tombstones.keys.iterator()
            it.next()
            it.remove()
        }
    }

    /** A tombstone for [id] reached this node: drop any held copy (and remember the tombstone if it mattered here). */
    private fun onTombstone(
        id: Int,
        expiresAt: Long,
        onPath: Boolean,
    ) {
        val held = pending.removeId(id)
        port.holdBudget()?.release(id)
        if (held) {
            port.count(if (onPath) "tombPurgePath" else "tombPurgeCatchUp")
        }
        unacked.remove(id)
        if (held || onPath) addTombstone(id, expiresAt)
    }

    private fun sendSummary(peer: Int) {
        val t = port.now()
        val held = pending.all().filter { it.expiresAt >= t }.distinctBy { it.id }
        val tombs = tombstones.entries.filter { it.value >= t }
        if (held.isEmpty() && tombs.isEmpty()) return
        port.count("summaryUnicastIds", held.size.toLong())
        port.count("summaryTombstones", tombs.size.toLong())
        port.send(
            peer,
            CatchUpSummary(
                IntArray(held.size) { held[it].id },
                LongArray(held.size) { held[it].dstTag },
                IntArray(tombs.size) { tombs[it].key },
                LongArray(tombs.size) { tombs[it].value },
            ),
        )
    }

    private fun onSummary(
        from: Int,
        s: CatchUpSummary,
    ) {
        for (k in s.tombIds.indices) onTombstone(s.tombIds[k], s.tombExpiry[k], onPath = false)
        val want = ArrayList<Int>()
        for (k in s.ids.indices) {
            val id = s.ids[k]
            if (tombstones.containsKey(id) || claimed.contains(id)) continue
            if (port.claimsTag(s.dstTags[k])) want.add(id)
        }
        if (want.isEmpty()) return
        claimed.addAll(want)
        if (claimed.size > 256) claimed.clear()
        port.count("claimIds", want.size.toLong())
        port.send(from, Claim(want.toIntArray()))
    }

    private fun onClaim(
        from: Int,
        c: Claim,
    ) {
        for (id in c.ids) {
            val p = pending.find(id) ?: continue
            if (port.expired(p)) continue
            if (port.send(from, DataPacket(p.id, p.srcTag, p.dstTag, p.expiresAt, p.payloadBytes, p.hops + 1, p.hopLimit))) {
                port.count("claimServed")
            }
        }
    }

    /** Delivery at the destination: A1 keeps a tombstone; K2 acknowledges back to [from] with the tombstone. */
    private fun deliverHere(
        p: DataPacket,
        from: Int,
        viaClaim: Boolean,
    ) {
        if (viaClaim) port.deliverClaimed(p) else port.deliver(p)
        if (port.params.holdForOthers) addTombstone(p.id, p.expiresAt)
        if (port.params.deliveryAck && from >= 0) port.send(from, DeliveryAck(p.srcTag, p.dstTag, p.id, p.expiresAt))
    }

    /** A1: a message held for others must fit the shared quota; [from] is the incoming link (−1 unknown). */
    private fun admitHeld(
        p: DataPacket,
        from: Int,
    ): Boolean {
        val b = port.holdBudget() ?: return true
        if (b.admit(p.id, p.payloadBytes, from, p.expiresAt, port.now(), holdEvictor)) return true
        port.drop(p, DropReason.HOLD_FULL)
        return false
    }

    // ---- stage F RL1 ----

    private fun allowRequest(from: Int): Boolean {
        val rate = port.params.rateLimitPerS
        if (rate <= 0.0 || from < 0) return true
        val t = port.now()
        val b = buckets.getOrPut(from) { doubleArrayOf(port.params.rateLimitBurst, t.toDouble()) }
        b[0] = minOf(port.params.rateLimitBurst, b[0] + (t - b[1]) / 1000.0 * rate)
        b[1] = t.toDouble()
        if (b[0] >= 1.0) {
            b[0] -= 1.0
            return true
        }
        port.count("rlDropped")
        return false
    }

    // ---- stage F A3: certificate lookups ----

    private fun lookupWaitMs() = 2 * port.params.nodeTraversalMs * port.params.netDiameter

    override fun lookup(target: Int) {
        start()
        if (lookups.containsKey(target)) return
        val l = Lookup(port.now())
        lookups[target] = l
        port.count("lookupEpisodes")
        sendLookup(target, l)
    }

    private fun newSalt() = (port.random.nextDouble() * 9.0E15).toLong() xor (self.toLong() shl 53)

    private fun sendLookup(
        target: Int,
        l: Lookup,
    ) {
        val salt = newSalt()
        l.salts.add(salt)
        l.attempts++
        lookupSeen[salt] = port.now() + lookupWaitMs()
        port.count("lookupTx")
        port.lookupSent(target)
        for (peer in port.linkedPeers()) port.send(peer, LookupRequest(salt, target, self, 0))
        val gen = ++l.gen
        port.after(lookupWaitMs()) { onLookupTimeout(target, l, gen) }
    }

    private fun onLookupTimeout(
        target: Int,
        l: Lookup,
        gen: Int,
    ) {
        if (lookups[target] !== l || l.gen != gen) return
        if (!port.lookupWanted(target)) {
            lookups.remove(target)
            return
        }
        val p = port.params
        // ADR 0008 unicast schedule: the request and its retries at full range, then held retries with backoff.
        if (l.attempts % (p.rreqRetries + 1) != 0) {
            sendLookup(target, l)
            return
        }
        val c = cls()
        val lo = p.classPolicy?.retryMinMs?.get(c) ?: p.sfRetryMinMs
        val hi = p.classPolicy?.retryMaxMs?.get(c) ?: p.sfRetryMaxMs
        val delay = minOf(hi, lo shl minOf(l.level, 20))
        l.level++
        val g = ++l.gen
        port.after(delay) {
            if (lookups[target] !== l || l.gen != g) return@after
            if (!port.lookupWanted(target)) {
                lookups.remove(target)
                return@after
            }
            sendLookup(target, l)
        }
    }

    override fun attackLookup(target: Int) {
        start()
        val salt = newSalt()
        lookupSeen[salt] = port.now() + lookupWaitMs()
        port.count("attackLookups")
        for (peer in port.linkedPeers()) port.send(peer, LookupRequest(salt, target, self, 0))
    }

    private fun onLookupRequest(
        from: Int,
        q: LookupRequest,
    ) {
        val t = port.now()
        if ((lookupSeen[q.salt] ?: Long.MIN_VALUE) >= t) return
        lookupSeen[q.salt] = t + lookupWaitMs()
        if (!allowRequest(from)) return
        lookupBack[q.salt] = from to t + lookupWaitMs()
        if (q.target == self || port.hasCert(q.target)) {
            port.count(if (q.target == self) "lookupAnsSelf" else "lookupAnsCache")
            port.send(from, LookupReply(q.salt, q.target, port.certBytes(q.requester, q.target), q.hops + 1, q.requester))
            return
        }
        if (q.hops + 1 >= port.params.netDiameter) return
        relayAll(LookupRequest(q.salt, q.target, q.requester, q.hops + 1), from)
    }

    private fun onLookupReply(
        from: Int,
        a: LookupReply,
    ) {
        if (a.requester == self) port.count("lookupReplyAtReq")
        val l = lookups[a.target]
        if (l != null && a.salt in l.salts) {
            lookups.remove(a.target)
            port.lookupDone(a.target, port.now() - l.startedAt, a.answerHops)
            return
        }
        val (back, until) = (if (port.params.lookupReplyDedupe) lookupBack.remove(a.salt) else lookupBack[a.salt]) ?: return
        if (until < port.now() || !port.isLinked(back)) return
        port.send(back, a)
    }

    private fun usable(dst: Long): Route? {
        val r = routes[dst] ?: return null
        if (!r.valid) return null
        if (r.expires < port.now()) {
            r.valid = false
            return null
        }
        return if (port.isLinked(r.next)) r else null
    }

    private fun upsert(
        dst: Long,
        next: Int,
        hops: Int,
        seq: Int,
        lifetimeMs: Long,
    ): Route {
        val t = port.now()
        val r = routes[dst]
        if (r == null) {
            val n = Route(next, hops, seq, t + lifetimeMs, true)
            routes[dst] = n
            return n
        }
        val usable = r.valid && r.expires >= t && port.isLinked(r.next)
        if (!usable || seq > r.seq || (seq == r.seq && hops < r.hops)) {
            r.next = next
            r.hops = hops
            r.seq = seq
            r.valid = true
            r.expires = t + lifetimeMs
        } else if (r.next == next) {
            r.expires = maxOf(r.expires, t + lifetimeMs)
        }
        return r
    }

    private fun cls() = port.densityClass()

    /** Lifetime a route gets from being used (K1 extends it). */
    private fun keepMs() = maxOf(port.params.activeRouteMs, port.params.convKeepMs)

    /** Lifetime of a learned way back to a message's or request's source (K3 extends it). */
    private fun reverseMs() = maxOf(port.params.reverseRouteMs, port.params.reverseCacheMs)

    /** Data ring radius for this phone now (adaptive policy by local class, else the fixed parameter). */
    private fun ringHops() = port.params.classPolicy?.ringHops?.get(cls()) ?: port.params.dataRingHops

    private fun floodAll() = ringHops() >= UnicastClassPolicy.FLOOD_ALL

    override fun onLinkUp(peer: Int) {
        start()
        if (port.params.holdForOthers) sendSummary(peer)
        val retry = port.params.classPolicy?.linkUpRetry?.get(cls()) ?: port.params.sfTuned
        if (!retry) return
        // A new link may connect a held destination: re-discover (rate-limited per destination).
        val t = port.now()
        for (dst in pending.held.destinations()) {
            if (discoveries.containsKey(dst)) continue
            if (t - (lastLinkUpRetry[dst] ?: Long.MIN_VALUE / 2) < port.params.sfLinkUpMinGapMs) continue
            lastLinkUpRetry[dst] = t
            if (usable(dst) != null) flush(dst) else discover(dst)
        }
    }

    private fun sleepy() = port.params.relayPreference && !port.selfStable()

    /** Relays a flooded packet to every link but [from]; sleeping-prone relays wait so awake paths win. */
    private fun relayAll(
        p: Packet,
        from: Int,
    ) {
        if (sleepy()) {
            port.after(port.params.sleepyRelayDelayMs) { for (peer in port.linkedPeers()) if (peer != from) port.send(peer, p) }
        } else {
            for (peer in port.linkedPeers()) if (peer != from) port.send(peer, p)
        }
    }
    override fun onLinkDown(peer: Int) {
        val dsts = ArrayList<Long>()
        val seqs = ArrayList<Int>()
        val notify = HashSet<Int>()
        for ((dst, r) in routes) {
            r.precursors.remove(peer)
            if (r.valid && r.next == peer) {
                r.valid = false
                r.seq++
                dsts.add(dst)
                seqs.add(r.seq)
                notify.addAll(r.precursors)
            }
        }
        sendErrors(dsts, seqs, notify)
    }

    private fun sendErrors(
        dsts: List<Long>,
        seqs: List<Int>,
        notify: Set<Int>,
    ) {
        if (dsts.isEmpty() || notify.isEmpty()) return
        val e = RouteError(dsts.toLongArray(), seqs.toIntArray())
        for (p in notify) port.send(p, e)
    }

    override fun onPacket(
        from: Int,
        packet: Packet,
    ) {
        when (packet) {
            is RouteRequest -> onRequest(from, packet)
            is RouteReply -> onReply(from, packet)
            is RouteError -> onError(from, packet)
            is DeliveryAck -> onAck(from, packet)
            is CatchUpSummary -> onSummary(from, packet)
            is Claim -> onClaim(from, packet)
            is LookupRequest -> onLookupRequest(from, packet)
            is LookupReply -> onLookupReply(from, packet)
            is DataPacket -> if (packet.scope >= 0) onRing(from, packet) else forward(packet, from)
            else -> {}
        }
    }

    private fun requestKey(
        origin: Long,
        id: Int,
    ) = origin * 31 + id

    private fun onRequest(
        from: Int,
        q: RouteRequest,
    ) {
        if (port.acceptsTag(q.origin)) return
        val key = requestKey(q.origin, q.requestId)
        val t = port.now()
        if ((requestsSeen[key] ?: Long.MIN_VALUE) >= t) return
        requestsSeen[key] = t + 2 * port.params.nodeTraversalMs * port.params.netDiameter
        if (!allowRequest(from)) return
        upsert(q.origin, from, q.hops + 1, q.originSeq, reverseMs())
        // Tuned holding: the destination's own request just gave a route to it.
        if (port.params.sfTuned && pending.has(q.origin)) flush(q.origin)
        if (port.acceptsTag(q.dst)) {
            ownSeq = maxOf(ownSeq + 1, q.dstSeq)
            port.send(from, RouteReply(q.origin, q.dst, ownSeq, 0))
            return
        }
        val r = usable(q.dst)
        if (r != null && r.seq >= q.dstSeq) {
            r.precursors.add(from)
            routes[q.origin]?.precursors?.add(r.next)
            port.send(from, RouteReply(q.origin, q.dst, r.seq, r.hops))
            return
        }
        if (q.radius <= 1) return
        val f = RouteRequest(q.requestId, q.origin, q.originSeq, q.dst, maxOf(q.dstSeq, routes[q.dst]?.seq ?: 0), q.hops + 1, q.radius - 1)
        relayAll(f, from)
    }

    private fun onReply(
        from: Int,
        a: RouteReply,
    ) {
        val fwd = upsert(a.dst, from, a.hops + 1, a.dstSeq, maxOf(2 * port.params.activeRouteMs, port.params.convKeepMs))
        if (a.ackId >= 0) {
            pending.remove(a.dst, a.ackId)
            if (port.params.holdForOthers) onTombstone(a.ackId, a.ackExpiresAt, onPath = true)
        }
        if (port.acceptsTag(a.origin)) {
            discoveries.remove(a.dst)
            flush(a.dst)
            return
        }
        // A relay that was itself waiting for this destination can flush now.
        if (pending.has(a.dst)) flush(a.dst)
        val back = usable(a.origin) ?: return
        fwd.precursors.add(back.next)
        back.precursors.add(from)
        port.send(back.next, RouteReply(a.origin, a.dst, a.dstSeq, a.hops + 1, a.ackId, a.ackExpiresAt))
    }

    private fun onError(
        from: Int,
        e: RouteError,
    ) {
        val dsts = ArrayList<Long>()
        val seqs = ArrayList<Int>()
        val notify = HashSet<Int>()
        for (k in e.dsts.indices) {
            val r = routes[e.dsts[k]] ?: continue
            if (r.valid && r.next == from) {
                r.valid = false
                r.seq = maxOf(r.seq, e.seqs[k])
                dsts.add(e.dsts[k])
                seqs.add(r.seq)
                notify.addAll(r.precursors)
            }
        }
        sendErrors(dsts, seqs, notify)
    }

    override fun originate(packet: DataPacket) {
        start()
        if (port.params.holdForOthers || port.params.largeMessages) ownIds[packet.id] = packet.expiresAt
        if (isLarge(packet)) {
            // ADR 0015: never flooded, in any class: a direct link or a route found by route request.
            forward(packet, -1)
            return
        }
        val sparse = port.params.sparseFloodBelow
        val est = port.densityEstimate()
        if (floodAll() || (sparse > 0 && est >= 0 && est < sparse)) floodOut(packet) else forward(packet, -1)
    }

    /** Adaptive low class: flood the message itself (U1-like) and keep it until the destination acknowledges. */
    private fun floodOut(p: DataPacket) {
        if (port.expired(p)) {
            port.drop(p, DropReason.EXPIRED)
            return
        }
        ringSeen.add(p)
        ringed.add(p.id)
        val copy = p.forwarded(null, 0, p.hopLimit)
        for (peer in port.linkedPeers()) port.send(peer, copy)
        pending.wait(p)
        val dst = p.dstTag
        if (discoveries.containsKey(dst) || retryScheduled.contains(dst)) return
        val params = port.params
        val d = Discovery(params.netDiameter, params.rreqRetries, 0)
        discoveries[dst] = d
        val gen = ++d.generation
        port.after(2 * params.dataNodeTraversalMs * (params.netDiameter + 2)) { onDiscoveryTimeout(dst, d, gen) }
    }

    private fun forward(
        p: DataPacket,
        from: Int,
    ) {
        if (port.expired(p)) {
            port.drop(p, DropReason.EXPIRED)
            return
        }
        if (from >= 0) {
            learnBack(p, from)
            if (port.params.deliveryAck) ackBack[p.id] = from to port.now() + 10_000
        }
        if (from >= 0 && claimed.remove(p.id)) {
            deliverHere(p, from, viaClaim = true)
            return
        }
        if (from >= 0 && port.acceptsTag(p.dstTag)) {
            deliverHere(p, from, viaClaim = false)
            return
        }
        val r = usable(p.dstTag)
        if (r != null) {
            if (p.hops >= p.hopLimit) {
                port.drop(p, DropReason.HOP_LIMIT)
                return
            }
            if (sendVia(r, p, from)) return
        }
        if (from >= 0 && p.hops >= p.hopLimit) {
            port.drop(p, DropReason.HOP_LIMIT)
            return
        }
        if (!own(p.id)) {
            // A4: relays forward large messages but never hold them; only the origin holds and retries.
            if (isLarge(p)) {
                port.count("lgRelayDrop")
                port.drop(p, DropReason.NO_ROUTE)
                return
            }
            if (port.params.holdForOthers && !admitHeld(p, from)) return
        }
        pending.wait(p)
        if (!discoveries.containsKey(p.dstTag) && !retryScheduled.contains(p.dstTag)) discover(p.dstTag)
    }

    private fun sendVia(
        r: Route,
        p: DataPacket,
        from: Int,
    ): Boolean {
        if (!port.send(r.next, p.forwarded())) return false
        if (port.params.holdForOthers) port.holdBudget()?.release(p.id)
        val t = port.now()
        val keep = keepMs()
        r.expires = maxOf(r.expires, t + keep)
        if (from >= 0) {
            r.precursors.add(from)
            routes[p.srcTag]?.let { back ->
                if (back.valid && back.next == from) back.expires = maxOf(back.expires, t + keep)
            }
        } else if (port.params.deliveryAck && p.scope < 0 && port.acceptsTag(p.srcTag)) {
            awaitAck(p, r.hops)
        }
        return true
    }

    /** K3 (and K1 at the destination): remember the previous hop as the way back to the source. */
    private fun learnBack(
        p: DataPacket,
        from: Int,
    ) {
        val params = port.params
        if (params.reverseCacheMs > 0) {
            upsert(p.srcTag, from, p.hops, routes[p.srcTag]?.seq ?: 0, params.reverseCacheMs)
        } else if (params.convKeepMs > 0) {
            routes[p.srcTag]?.let { back ->
                if (back.valid && back.next == from) back.expires = maxOf(back.expires, port.now() + keepMs())
            }
        }
    }

    /** K2: re-send once along a fresh discovery if no ack arrives in time. */
    private fun awaitAck(
        p: DataPacket,
        hops: Int,
    ) {
        if (unacked.containsKey(p.id)) return
        unacked[p.id] = p
        val bulk = if (isLarge(p)) (hops * p.payloadBytes / port.params.largeAckBytesPerMs).toLong() else 0L
        val wait = port.params.ackWaitMs + 2 * port.params.dataNodeTraversalMs * hops + bulk
        port.after(wait) {
            val q = unacked.remove(p.id) ?: return@after
            if (port.expired(q)) return@after
            if (isLarge(q)) port.count("lgResend")
            routes[q.dstTag]?.let { if (it.valid) it.valid = false }
            forward(DataPacket(q.id, q.srcTag, q.dstTag, q.expiresAt, q.payloadBytes, 0, q.hopLimit), -1)
        }
    }

    private fun onAck(
        from: Int,
        a: DeliveryAck,
    ) {
        val t = port.now()
        routes[a.dst]?.let { fwd -> if (fwd.valid && fwd.next == from) fwd.expires = maxOf(fwd.expires, t + keepMs()) }
        if (port.params.holdForOthers) onTombstone(a.msgId, a.expiresAt, onPath = true)
        if (port.acceptsTag(a.origin)) {
            unacked.remove(a.msgId)
            return
        }
        val (back, until) = ackBack.remove(a.msgId) ?: return
        if (until < t || !port.isLinked(back)) return
        routes[a.origin]?.let { r -> if (r.valid && r.next == back) r.expires = maxOf(r.expires, t + keepMs()) }
        port.send(back, a)
    }

    private fun discover(dst: Long) {
        val p = port.params
        val hops = minOf(ringHops(), p.netDiameter)
        val head = if (hops > 0) pending.waiting.peek(dst) ?: pending.held.peek(dst) else null
        if (head != null && !isLarge(head) && ringed.add(head.id) && !port.expired(head)) {
            // U6: no route, so flood the message itself a few hops; it doubles as the route request.
            val d = Discovery(hops, if (hops >= p.netDiameter) p.rreqRetries else 0, 0)
            discoveries[dst] = d
            ringSeen.add(head)
            val copy = head.forwarded(null, 0, minOf(hops, head.hopLimit))
            for (peer in port.linkedPeers()) port.send(peer, copy)
            if (ringed.size > 4_096) ringed.clear()
            if (p.fullRangeDiscovery && hops < p.netDiameter) {
                // F: the full-range request runs alongside the ring; its timer replaces the ring's.
                d.radius = p.netDiameter
                d.retries = 0
                sendRequest(dst, d)
                return
            }
            val gen = ++d.generation
            port.after(2 * p.dataNodeTraversalMs * (hops + 2)) { onDiscoveryTimeout(dst, d, gen) }
            return
        }
        val d = Discovery(if (p.fullRangeDiscovery) p.netDiameter else p.ringStart, 0, 0)
        discoveries[dst] = d
        sendRequest(dst, d)
    }

    /** U6 data ring copy: learn the way back, deliver and acknowledge, take a known route, or flood on. */
    private fun onRing(
        from: Int,
        p: DataPacket,
    ) {
        if (!ringSeen.add(p)) {
            port.duplicate(p)
            return
        }
        if (port.expired(p)) {
            port.drop(p, DropReason.EXPIRED)
            return
        }
        upsert(p.srcTag, from, p.hops, routes[p.srcTag]?.seq ?: 0, reverseMs())
        if (port.acceptsTag(p.dstTag)) {
            port.deliver(p)
            if (port.params.holdForOthers) addTombstone(p.id, p.expiresAt)
            ownSeq++
            val me = if (port.params.convTags) p.dstTag else port.ownTag()
            port.send(from, RouteReply(p.srcTag, me, ownSeq, 0, p.id, p.expiresAt))
            return
        }
        if (p.hops >= p.hopLimit) {
            port.drop(p, DropReason.HOP_LIMIT)
            return
        }
        val r = usable(p.dstTag)
        // Adaptive: a low-density relay keeps flooding (routes are short-lived there); others take a known route.
        if (r != null && !(port.params.classPolicy != null && floodAll())) {
            sendVia(r, DataPacket(p.id, p.srcTag, p.dstTag, p.expiresAt, p.payloadBytes, p.hops, p.hopLimit), from)
            return
        }
        if (p.scope <= 1) return
        relayAll(p.forwarded(null, 0, p.scope - 1), from)
    }

    private fun sendRequest(
        dst: Long,
        d: Discovery,
    ) {
        val p = port.params
        ownSeq++
        val q = RouteRequest(++requestId, port.ownTag(), ownSeq, dst, routes[dst]?.seq ?: 0, 0, d.radius)
        port.count("rreqOrig")
        if (port.isConvTag(dst)) port.count("rreqConv")
        requestsSeen[requestKey(q.origin, q.requestId)] = port.now() + 2 * p.nodeTraversalMs * p.netDiameter
        for (peer in port.linkedPeers()) port.send(peer, q)
        val wait =
            if (d.radius >= p.netDiameter) 2 * p.nodeTraversalMs * p.netDiameter else 2 * p.nodeTraversalMs * (d.radius + 2)
        val gen = ++d.generation
        port.after(wait) { onDiscoveryTimeout(dst, d, gen) }
    }

    private fun onDiscoveryTimeout(
        dst: Long,
        d: Discovery,
        gen: Int,
    ) {
        if (discoveries[dst] !== d || d.generation != gen) return
        if (usable(dst) != null) {
            discoveries.remove(dst)
            flush(dst)
            return
        }
        val p = port.params
        if (d.radius >= p.netDiameter) {
            if (d.retries >= p.rreqRetries) {
                discoveries.remove(dst)
                failed(dst)
                return
            }
            d.retries++
        } else {
            d.radius += p.ringIncrement
            if (d.radius > p.ringThreshold) d.radius = p.netDiameter
        }
        sendRequest(dst, d)
    }

    private fun failed(dst: Long) {
        pending.promote(dst)
        if (!pending.has(dst) || !retryScheduled.add(dst)) return
        val p = port.params
        val delay =
            if (!p.sfTuned) {
                p.holdRetryMs
            } else {
                val level = retryLevel[dst] ?: 0
                retryLevel[dst] = level + 1
                val c = cls()
                val lo = p.classPolicy?.retryMinMs?.get(c) ?: p.sfRetryMinMs
                val hi = p.classPolicy?.retryMaxMs?.get(c) ?: p.sfRetryMaxMs
                minOf(hi, lo shl minOf(level, 20))
            }
        port.after(delay) {
            retryScheduled.remove(dst)
            pending.held.sweep()
            if (pending.has(dst) && !discoveries.containsKey(dst)) {
                if (usable(dst) != null) flush(dst) else discover(dst)
            }
        }
    }

    private fun flush(dst: Long) {
        val r = usable(dst)
        if (r != null) retryLevel.remove(dst)
        for (p in pending.take(dst)) {
            if (r != null && p.hops < p.hopLimit && sendVia(r, p, -1)) continue
            pending.wait(p)
        }
        if (pending.has(dst) && !discoveries.containsKey(dst) && !retryScheduled.contains(dst)) discover(dst)
    }

    override fun onSendFailed(
        peer: Int,
        packet: Packet,
    ) {
        val p = packet as? DataPacket ?: return
        // A lost data ring copy is covered by the other copies and the route discovery that follows.
        if (p.scope >= 0) return
        forward(DataPacket(p.id, p.srcTag, p.dstTag, p.expiresAt, p.payloadBytes, p.hops - 1, p.hopLimit), -1)
    }

    override fun tableSize() = routes.size

    override fun heldCount() = pending.size

    override fun hasRoute(tag: Long) = usable(tag) != null

    override fun prefetch(tag: Long) {
        start()
        if (usable(tag) != null || discoveries.containsKey(tag) || retryScheduled.contains(tag)) return
        port.count("prefetch")
        discover(tag)
    }
}
