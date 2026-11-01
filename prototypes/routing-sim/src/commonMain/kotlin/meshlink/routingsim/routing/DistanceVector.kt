package meshlink.routingsim.routing

/** U3 route update: (destination tag, sequence number, metric) entries; 12 B each. */
class RouteUpdate(
    val tags: LongArray,
    val seqs: IntArray,
    val metrics: IntArray,
) : Packet() {
    override val isControl get() = true
    override val isIdleControl get() = true

    override fun sizeBytes(s: RoutingSizes) = s.headerBytes + s.seqBytes + tags.size * (s.tagBytes + s.seqBytes + s.metricBytes)
}

/** U3 sequence-number request, forwarded towards the destination along selected routes. */
class SeqRequest(
    val dst: Long,
    val seq: Int,
    val hopsLeft: Int,
) : Packet() {
    override val isControl get() = true
    override val isIdleControl get() = true

    override fun sizeBytes(s: RoutingSizes) = s.headerBytes + s.tagBytes + s.seqBytes + s.hopFieldBytes
}

/**
 * U3: Babel-like proactive distance vector (RFC 8966, simplified).
 *
 * Kept from Babel: Hello every 4 s on every link, full route table every 16 s, feasibility
 * distance per destination (sequence number + metric) for loop avoidance, retraction and
 * sequence-number requests when a node is starved, full update to a new neighbor, triggered
 * (batched, ≥1 s apart) updates on new routes, retractions and sequence-number changes, route
 * expiry 56 s; sequence numbers only increase on request; a starved node sends one unicast seqno
 * request to the neighbor advertising an unfeasible route, at most 3 times per sequence number
 * (babeld behaviour). Simplified: hop-count metric (no ETX), link sensing from the link layer, no
 * source-table garbage collection, sequence numbers seeded from the node clock.
 */
class DistanceVector(
    private val port: RoutingPort,
    private val self: Int,
) : Routing {
    private class Dest(
        val tag: Long,
    ) {
        var nbr = IntArray(4)
        var seq = IntArray(4)
        var metric = IntArray(4)
        var exp = LongArray(4)
        var count = 0
        var sel = -1
        var selMetric = INF
        var selSeq = 0
        var hasFd = false
        var fdSeq = 0
        var fdMetric = INF
        var reqSeq = Int.MIN_VALUE
        var reqAt = Long.MIN_VALUE / 2
        var reqTries = 0
        var dirty = false
        var slot = -1

        fun indexOf(peer: Int): Int {
            for (i in 0 until count) if (nbr[i] == peer) return i
            return -1
        }

        fun put(
            peer: Int,
            s: Int,
            m: Int,
            e: Long,
        ) {
            var i = indexOf(peer)
            if (i < 0) {
                if (count == nbr.size) {
                    nbr = nbr.copyOf(count * 2)
                    seq = seq.copyOf(count * 2)
                    metric = metric.copyOf(count * 2)
                    exp = exp.copyOf(count * 2)
                }
                i = count++
                nbr[i] = peer
            }
            seq[i] = s
            metric[i] = m
            exp[i] = e
        }

        fun remove(peer: Int): Boolean {
            val i = indexOf(peer)
            if (i < 0) return false
            count--
            nbr[i] = nbr[count]
            seq[i] = seq[count]
            metric[i] = metric[count]
            exp[i] = exp[count]
            return true
        }
    }

    /** Route table: tag → slot in [table] (open addressing; this is a hot path of the simulator). */
    private val index = LongIntMap()
    private val table = ArrayList<Dest?>()
    private val freeSlots = ArrayList<Int>()
    private var destCount = 0
    private val pending = PendingRoutes(port)
    private val changed = ArrayList<Dest>()
    private var ownChanged = false

    private fun dest(tag: Long): Dest? {
        val i = index[tag]
        return if (i < 0) null else table[i]
    }

    private fun addDest(tag: Long): Dest {
        val d = Dest(tag)
        val i = if (freeSlots.isEmpty()) table.size.also { table.add(null) } else freeSlots.removeAt(freeSlots.size - 1)
        table[i] = d
        d.slot = i
        index[tag] = i
        destCount++
        return d
    }

    private fun removeDest(d: Dest) {
        index.remove(d.tag)
        table[d.slot] = null
        freeSlots.add(d.slot)
        destCount--
    }
    private var triggerScheduled = false
    private var lastTriggered = Long.MIN_VALUE / 2
    private var started = false
    private var ownTag = port.ownTag()
    private var ownSeq = (port.now() / 1000).toInt()

    private fun start() {
        if (started) return
        started = true
        val p = port.params
        port.after((port.random.nextDouble() * p.helloMs).toLong()) { helloLoop() }
        port.after((port.random.nextDouble() * p.updateMs).toLong()) { updateLoop() }
        port.after(1_000) { sweepLoop() }
    }

    private fun helloLoop() {
        val hello = Control("hello", 20, isIdleControl = true)
        for (peer in port.linkedPeers()) port.send(peer, hello)
        port.after(port.params.helloMs) { helloLoop() }
    }

    private fun updateLoop() {
        expireRoutes()
        val u = fullUpdate()
        for (peer in port.linkedPeers()) port.send(peer, u)
        // Babel jitters periodic updates; keep the mean at the update interval.
        port.after((port.params.updateMs * (0.75 + 0.5 * port.random.nextDouble())).toLong()) { updateLoop() }
    }

    private fun sweepLoop() {
        pending.sweep()
        port.after(1_000) { sweepLoop() }
    }

    private fun valid(d: Dest): Boolean {
        if (d.sel < 0) return false
        val i = d.indexOf(d.sel)
        return i >= 0 && d.exp[i] >= port.now()
    }

    private fun expireRoutes() {
        val t = port.now()
        val toReselect = ArrayList<Dest>()
        for (slot in table.indices) {
            val d = table[slot] ?: continue
            var i = 0
            var removed = false
            while (i < d.count) {
                if (d.exp[i] < t) {
                    d.remove(d.nbr[i])
                    removed = true
                } else {
                    i++
                }
            }
            if (d.count == 0 && d.sel < 0 && !pending.has(d.tag) && t - d.reqAt > 60_000) {
                removeDest(d)
            } else if (removed) {
                toReselect.add(d)
            }
        }
        for (d in toReselect) reselect(d)
    }

    private fun fullUpdate(): RouteUpdate {
        var n = 1
        for (d in table) if (d != null && d.sel >= 0) n++
        val tags = LongArray(n)
        val seqs = IntArray(n)
        val metrics = IntArray(n)
        tags[0] = ownTag
        seqs[0] = ownSeq
        metrics[0] = 0
        var k = 1
        for (d in table) {
            if (d == null || d.sel < 0) continue
            tags[k] = d.tag
            seqs[k] = d.selSeq
            metrics[k] = d.selMetric
            k++
        }
        return RouteUpdate(tags, seqs, metrics)
    }

    private fun markChanged(d: Dest) {
        if (!d.dirty) {
            d.dirty = true
            changed.add(d)
        }
        scheduleTriggered()
    }

    private fun markOwnChanged() {
        ownChanged = true
        scheduleTriggered()
    }

    private fun scheduleTriggered() {
        if (triggerScheduled) return
        triggerScheduled = true
        port.after(maxOf(0L, lastTriggered + port.params.triggeredMinGapMs - port.now())) { flushTriggered() }
    }

    private fun flushTriggered() {
        triggerScheduled = false
        lastTriggered = port.now()
        if (changed.isEmpty() && !ownChanged) return
        val n = changed.size + if (ownChanged) 1 else 0
        val tags = LongArray(n)
        val seqs = IntArray(n)
        val metrics = IntArray(n)
        var k = 0
        if (ownChanged) {
            tags[0] = ownTag
            seqs[0] = ownSeq
            metrics[0] = 0
            k = 1
        }
        for (d in changed) {
            d.dirty = false
            tags[k] = d.tag
            seqs[k] = d.selSeq
            metrics[k] = if (d.sel >= 0) d.selMetric else INF
            k++
        }
        changed.clear()
        ownChanged = false
        val u = RouteUpdate(tags, seqs, metrics)
        for (peer in port.linkedPeers()) port.send(peer, u)
    }

    private fun feasible(
        d: Dest,
        s: Int,
        m: Int,
    ) = !d.hasFd || s > d.fdSeq || (s == d.fdSeq && m < d.fdMetric)

    private fun reselect(d: Dest) {
        val t = port.now()
        var best = -1
        var bestM = INF
        for (i in 0 until d.count) {
            val m = d.metric[i]
            if (m >= INF || d.exp[i] < t || !feasible(d, d.seq[i], m)) continue
            if (m + 1 < bestM || (m + 1 == bestM && d.nbr[i] == d.sel)) {
                best = i
                bestM = m + 1
            }
        }
        val old = d.sel
        val oldSeq = d.selSeq
        if (best >= 0) {
            d.sel = d.nbr[best]
            d.selMetric = bestM
            d.selSeq = d.seq[best]
            if (!d.hasFd || d.selSeq > d.fdSeq || (d.selSeq == d.fdSeq && bestM < d.fdMetric)) {
                d.hasFd = true
                d.fdSeq = d.selSeq
                d.fdMetric = bestM
            }
            if (old < 0 || d.selSeq != oldSeq) markChanged(d)
            if (old < 0 && pending.has(d.tag)) flush(d)
        } else {
            d.sel = -1
            d.selMetric = INF
            if (old >= 0) markChanged(d)
            // As babeld: when starved but an unfeasible route exists, send a unicast seqno request to
            // the neighbor advertising it (otherwise the destination is simply gone).
            if (d.hasFd) {
                val via = unfeasibleNeighbor(d, t)
                if (via >= 0) request(d, d.fdSeq + 1, via)
            }
        }
    }

    /** Neighbor advertising the best unfeasible route, or −1. */
    private fun unfeasibleNeighbor(
        d: Dest,
        t: Long,
    ): Int {
        var best = -1
        var bestM = INF
        for (i in 0 until d.count) {
            if (d.metric[i] < bestM && d.exp[i] >= t) {
                best = d.nbr[i]
                bestM = d.metric[i]
            }
        }
        return best
    }

    private fun request(
        d: Dest,
        s: Int,
        via: Int,
    ) {
        val t = port.now()
        if (d.reqSeq >= s && (d.reqTries >= REQUEST_TRIES || t - d.reqAt < 2_000)) return
        d.reqTries = if (d.reqSeq >= s) d.reqTries + 1 else 1
        d.reqSeq = s
        d.reqAt = t
        port.send(via, SeqRequest(d.tag, s, REQUEST_HOPS))
    }

    override fun onLinkUp(peer: Int) {
        start()
        port.send(peer, Control("hello", 20, isIdleControl = true))
        port.send(peer, fullUpdate())
    }

    override fun onLinkDown(peer: Int) {
        val affected = ArrayList<Dest>()
        for (d in table) if (d != null && d.remove(peer) && d.sel == peer) affected.add(d)
        for (d in affected) reselect(d)
    }

    override fun onTagRotated() {
        ownTag = port.ownTag()
        ownSeq = (port.now() / 1000).toInt()
        markOwnChanged()
    }

    override fun onPacket(
        from: Int,
        packet: Packet,
    ) {
        when (packet) {
            is RouteUpdate -> onUpdate(from, packet)
            is SeqRequest -> onRequest(from, packet)
            is DataPacket -> forward(packet, from)
            else -> {}
        }
    }

    private fun onUpdate(
        from: Int,
        u: RouteUpdate,
    ) {
        if (!port.isLinked(from)) return
        val expiry = port.now() + port.params.routeExpiryMs
        for (k in u.tags.indices) {
            val tag = u.tags[k]
            if (port.acceptsTag(tag)) continue
            val m = u.metrics[k]
            var d = dest(tag)
            if (d == null) {
                if (m >= INF) continue
                d = addDest(tag)
            }
            d.put(from, u.seqs[k], m, expiry)
            if (d.sel == from || d.sel < 0 || m + 1 < d.selMetric || u.seqs[k] > d.selSeq) reselect(d)
            if (d.reqSeq != Int.MIN_VALUE && d.sel >= 0 && d.selSeq >= d.reqSeq) {
                d.reqSeq = Int.MIN_VALUE
                markChanged(d)
            }
        }
    }

    private fun onRequest(
        from: Int,
        r: SeqRequest,
    ) {
        if (r.dst == ownTag) {
            if (ownSeq < r.seq) ownSeq = r.seq
            markOwnChanged()
            return
        }
        if (port.acceptsTag(r.dst)) return
        val d = dest(r.dst) ?: return
        if (d.sel < 0) return
        if (d.selSeq >= r.seq) {
            port.send(from, RouteUpdate(longArrayOf(d.tag), intArrayOf(d.selSeq), intArrayOf(d.selMetric)))
            return
        }
        if (d.sel == from || r.hopsLeft <= 1) return
        val t = port.now()
        if (d.reqSeq >= r.seq && (d.reqTries >= REQUEST_TRIES || t - d.reqAt < 2_000)) return
        d.reqTries = if (d.reqSeq >= r.seq) d.reqTries + 1 else 1
        d.reqSeq = r.seq
        d.reqAt = t
        port.send(d.sel, SeqRequest(r.dst, r.seq, r.hopsLeft - 1))
    }

    override fun originate(packet: DataPacket) = forward(packet, -1)

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
        val d = dest(p.dstTag)
        if (d != null && valid(d)) {
            if (p.hops >= p.hopLimit) {
                port.drop(p, DropReason.HOP_LIMIT)
                return
            }
            if (port.send(d.sel, p.forwarded())) return
        }
        if (pending.wait(p)) port.after(ROUTE_WAIT_MS) { pending.promote(p.dstTag) }
    }

    private fun flush(d: Dest) {
        for (p in pending.take(d.tag)) {
            if (p.hops >= p.hopLimit) {
                port.drop(p, DropReason.HOP_LIMIT)
                continue
            }
            if (valid(d) && port.send(d.sel, p.forwarded())) continue
            if (pending.wait(p)) port.after(ROUTE_WAIT_MS) { pending.promote(p.dstTag) }
        }
    }

    override fun onSendFailed(
        peer: Int,
        packet: Packet,
    ) {
        val p = packet as? DataPacket ?: return
        // The copy already counted this hop; route it again from here.
        forward(DataPacket(p.id, p.srcTag, p.dstTag, p.expiresAt, p.payloadBytes, p.hops - 1, p.hopLimit), -1)
    }

    override fun tableSize() = destCount

    override fun heldCount() = pending.size

    companion object {
        const val INF = 0xFFFF
        const val ROUTE_WAIT_MS = 2_000L
        const val REQUEST_HOPS = 64

        /** Sends (or forwards) of a request for one sequence number, ≥2 s apart (babeld resends a few times). */
        const val REQUEST_TRIES = 3
    }
}
