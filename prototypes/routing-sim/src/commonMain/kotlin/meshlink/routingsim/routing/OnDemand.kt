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
) : Packet() {
    override val isControl get() = true

    override fun sizeBytes(s: RoutingSizes) = s.headerBytes + 2 * s.tagBytes + 4 + s.hopFieldBytes + 4
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
    private var ownSeq = (port.now() / 1000).toInt()
    private var requestId = 0
    private var started = false

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
        port.after(1_000) { sweepLoop() }
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

    override fun onLinkUp(peer: Int) = start()

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
            is DataPacket -> forward(packet, from)
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
        upsert(q.origin, from, q.hops + 1, q.originSeq, port.params.reverseRouteMs)
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
        for (peer in port.linkedPeers()) if (peer != from) port.send(peer, f)
    }

    private fun onReply(
        from: Int,
        a: RouteReply,
    ) {
        val fwd = upsert(a.dst, from, a.hops + 1, a.dstSeq, 2 * port.params.activeRouteMs)
        if (port.acceptsTag(a.origin)) {
            discoveries.remove(a.dst)
            flush(a.dst)
            return
        }
        val back = usable(a.origin) ?: return
        fwd.precursors.add(back.next)
        back.precursors.add(from)
        port.send(back.next, RouteReply(a.origin, a.dst, a.dstSeq, a.hops + 1))
        // A relay that was itself waiting for this destination can flush now.
        if (pending.has(a.dst)) flush(a.dst)
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
        forward(packet, -1)
    }

    private fun forward(
        p: DataPacket,
        from: Int,
    ) {
        if (port.expired(p)) {
            port.drop(p, DropReason.EXPIRED)
            return
        }
        if (from >= 0 && port.acceptsTag(p.dstTag)) {
            port.deliver(p)
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
        pending.wait(p)
        if (!discoveries.containsKey(p.dstTag) && !retryScheduled.contains(p.dstTag)) discover(p.dstTag)
    }

    private fun sendVia(
        r: Route,
        p: DataPacket,
        from: Int,
    ): Boolean {
        if (!port.send(r.next, p.forwarded())) return false
        val t = port.now()
        r.expires = maxOf(r.expires, t + port.params.activeRouteMs)
        if (from >= 0) {
            r.precursors.add(from)
            routes[p.srcTag]?.let { back ->
                if (back.valid && back.next == from) back.expires = maxOf(back.expires, t + port.params.activeRouteMs)
            }
        }
        return true
    }

    private fun discover(dst: Long) {
        val d = Discovery(port.params.ringStart, 0, 0)
        discoveries[dst] = d
        sendRequest(dst, d)
    }

    private fun sendRequest(
        dst: Long,
        d: Discovery,
    ) {
        val p = port.params
        ownSeq++
        val q = RouteRequest(++requestId, port.ownTag(), ownSeq, dst, routes[dst]?.seq ?: 0, 0, d.radius)
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
        port.after(port.params.holdRetryMs) {
            retryScheduled.remove(dst)
            pending.held.sweep()
            if (pending.has(dst) && !discoveries.containsKey(dst)) {
                if (usable(dst) != null) flush(dst) else discover(dst)
            }
        }
    }

    private fun flush(dst: Long) {
        val r = usable(dst)
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
        forward(DataPacket(p.id, p.srcTag, p.dstTag, p.expiresAt, p.payloadBytes, p.hops - 1, p.hopLimit), -1)
    }

    override fun tableSize() = routes.size

    override fun heldCount() = pending.size
}
