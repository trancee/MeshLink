package meshlink.routingsim.routing

/** U2 announcement: the origin's current tag and up to 10 neighbor tags, flooded within a hop scope. */
class Announcement(
    val origin: Long,
    val seq: Int,
    val neighbors: LongArray,
    val hopsLeft: Int,
) : Packet() {
    override val isControl get() = true
    override val isIdleControl get() = true

    override fun sizeBytes(s: RoutingSizes) =
        s.headerBytes + s.tagBytes + s.seqBytes + s.hopFieldBytes + s.tagBytes * neighbors.size
}

/**
 * U2: scoped topology announcements plus source routing, with flood fallback (BitChat-style).
 *
 * Each node announces its neighbor tags every 15–30 s, and about 1 s after a link change (at
 * most every 4 s). Announcements are flooded [RoutingParams.announceScopeHops] hops. A sender
 * source-routes over a path whose every edge both ends announced (bidirectionally confirmed) and
 * fresh; otherwise it floods. A relay whose next hop is gone re-computes a route or floods.
 */
class ScopedSourceRoute(
    private val port: RoutingPort,
    private val self: Int,
) : Routing {
    private class Entry(
        var seq: Int,
        var neighbors: LongArray,
        var heardAt: Long,
    )

    private val db = HashMap<Long, Entry>()
    private val seen = SeenCache(port)
    private var seq = 0
    private var announceScheduled = false
    private var lastAnnounce = Long.MIN_VALUE / 2
    private var started = false

    private fun start() {
        if (started) return
        started = true
        val p = port.params
        port.after((port.random.nextDouble() * p.announceMinMs).toLong()) { periodic() }
    }

    private fun periodic() {
        announce()
        val p = port.params
        port.after(p.announceMinMs + (port.random.nextDouble() * (p.announceMaxMs - p.announceMinMs)).toLong()) { periodic() }
    }

    private fun triggered() {
        if (announceScheduled) return
        announceScheduled = true
        val wait = maxOf(1_000L, lastAnnounce + 4_000L - port.now())
        port.after(wait) {
            announceScheduled = false
            announce()
        }
    }

    private fun announce() {
        val peers = port.linkedPeers()
        if (peers.isEmpty()) return
        lastAnnounce = port.now()
        val n = minOf(peers.size, port.params.announceMaxNeighbors)
        val tags = LongArray(n) { port.peerTag(peers[it]) }
        val a = Announcement(port.ownTag(), ++seq, tags, port.params.announceScopeHops - 1)
        for (peer in peers) port.send(peer, a)
    }

    override fun onLinkUp(peer: Int) {
        start()
        triggered()
    }

    override fun onLinkDown(peer: Int) = triggered()

    override fun onTagRotated() = triggered()

    override fun onPacket(
        from: Int,
        packet: Packet,
    ) {
        when (packet) {
            is Announcement -> onAnnouncement(from, packet)
            is DataPacket -> onData(from, packet)
        }
    }

    private fun onAnnouncement(
        from: Int,
        a: Announcement,
    ) {
        if (port.acceptsTag(a.origin)) return
        val e = db[a.origin]
        if (e != null && a.seq <= e.seq) return
        if (e == null) {
            db[a.origin] = Entry(a.seq, a.neighbors, port.now())
        } else {
            e.seq = a.seq
            e.neighbors = a.neighbors
            e.heardAt = port.now()
        }
        if (db.size > 64 && db.size % 64 == 0) prune()
        if (a.hopsLeft <= 0) return
        val fwd = Announcement(a.origin, a.seq, a.neighbors, a.hopsLeft - 1)
        for (peer in port.linkedPeers()) if (peer != from) port.send(peer, fwd)
    }

    private fun prune() {
        val t = port.now()
        db.entries.removeAll { t - it.value.heardAt > port.params.topologyFreshMs }
    }

    private fun fresh(tag: Long): Entry? {
        val e = db[tag] ?: return null
        return if (port.now() - e.heardAt <= port.params.topologyFreshMs) e else null
    }

    /** Shortest bidirectionally confirmed path from this node to [dst]: tags of every hop, [dst] last. */
    private fun route(dst: Long): LongArray? {
        val peers = port.linkedPeers()
        val peerTags = LongArray(peers.size) { port.peerTag(peers[it]) }
        for (t in peerTags) if (t == dst) return longArrayOf(dst)
        val me = port.ownTag()
        val parent = HashMap<Long, Long>()
        val queue = ArrayDeque<Long>()
        for (t in peerTags) {
            // A direct neighbor's edge to me is confirmed by the link itself.
            if (!parent.containsKey(t)) {
                parent[t] = me
                queue.addLast(t)
            }
        }
        while (queue.isNotEmpty()) {
            val x = queue.removeFirst()
            val ex = fresh(x) ?: continue
            for (y in ex.neighbors) {
                if (y == me || parent.containsKey(y)) continue
                val ey = fresh(y)
                val confirmed = if (y == dst) ey == null || ey.neighbors.contains(x) else ey != null && ey.neighbors.contains(x)
                if (!confirmed) continue
                parent[y] = x
                if (y == dst) {
                    val path = ArrayList<Long>()
                    var c = y
                    while (c != me) {
                        path.add(c)
                        c = parent.getValue(c)
                    }
                    path.reverse()
                    return path.toLongArray()
                }
                queue.addLast(y)
            }
        }
        return null
    }

    private fun peerWithTag(tag: Long): Int {
        for (p in port.linkedPeers()) if (port.peerTag(p) == tag) return p
        return -1
    }

    override fun originate(packet: DataPacket) {
        seen.add(packet)
        sendOn(packet, -1)
    }

    /** Source-routes [p] (hops already counted) from here, or floods it. */
    private fun sendOn(
        p: DataPacket,
        from: Int,
    ) {
        val path = route(p.dstTag)
        if (path != null) {
            val next = peerWithTag(path[0])
            if (next >= 0 && port.send(next, DataPacket(p.id, p.srcTag, p.dstTag, p.expiresAt, p.payloadBytes, p.hops, p.hopLimit, path, 0))) return
        }
        val flood = DataPacket(p.id, p.srcTag, p.dstTag, p.expiresAt, p.payloadBytes, p.hops, p.hopLimit, null, 0)
        for (peer in port.linkedPeers()) if (peer != from) port.send(peer, flood)
    }

    private fun onData(
        from: Int,
        p: DataPacket,
    ) {
        val routed = p.route != null
        if (!routed && !seen.add(p)) {
            port.duplicate(p)
            return
        }
        if (routed) seen.add(p)
        if (port.expired(p)) {
            port.drop(p, DropReason.EXPIRED)
            return
        }
        if (port.acceptsTag(p.dstTag)) {
            port.deliver(p)
            return
        }
        if (p.hops >= p.hopLimit) {
            port.drop(p, DropReason.HOP_LIMIT)
            return
        }
        val route = p.route
        if (route != null) {
            val nextIndex = p.routeIndex + 1
            if (nextIndex < route.size) {
                val next = peerWithTag(route[nextIndex])
                if (next >= 0 && port.send(next, p.forwarded(route, nextIndex))) return
            }
        }
        sendOn(p.forwarded(null, 0), from)
    }

    override fun onSendFailed(
        peer: Int,
        packet: Packet,
    ) {
        val p = packet as? DataPacket ?: return
        // A lost flood copy is covered by the other copies; only source-routed messages retry.
        if (p.route == null) return
        if (port.expired(p)) {
            port.drop(p, DropReason.EXPIRED)
            return
        }
        // Retry from here: a fresh route if one exists, otherwise flood.
        sendOn(p, peer)
    }

    override fun tableSize() = db.size

    override fun seenSize() = seen.size
}
