package meshlink.routingsim.routing

/** Control packet naming message IDs (IHAVE, IWANT, GRAFT, IDONTWANT). */
class IdsControl(
    val kind: Kind,
    val ids: IntArray,
    override val isIdleControl: Boolean = false,
) : Packet() {
    enum class Kind { IHAVE, IWANT, GRAFT, PRUNE, IDONTWANT }

    override val isControl get() = true

    override fun sizeBytes(s: RoutingSizes) = s.headerBytes + 1 + ids.size * s.messageIdBytes
}

/** Recently received messages, kept while unexpired and within the cache window (repair source). */
class MessageCache(
    private val port: RoutingPort,
) {
    private val entries = LinkedHashMap<Int, DataPacket>()
    private val until = HashMap<Int, Long>()
    private val putAt = HashMap<Int, Long>()

    fun put(p: DataPacket) {
        prune()
        entries[p.id] = p
        putAt[p.id] = port.now()
        until[p.id] = minOf(p.expiresAt, port.now() + port.params.cacheWindowMs)
    }

    /** The cached message, unless it has expired (never forward an expired message). */
    fun get(id: Int): DataPacket? {
        val p = entries[id] ?: return null
        return if (port.expired(p) || (until[id] ?: 0) < port.now()) null else p
    }

    private fun prune() {
        val t = port.now()
        val it = entries.keys.iterator()
        while (it.hasNext()) {
            val id = it.next()
            if ((until[id] ?: 0) >= t) break
            it.remove()
            until.remove(id)
            putAt.remove(id)
        }
    }

    /** Unexpired cached messages received within the last [windowMs]. */
    fun recentIds(windowMs: Long): IntArray {
        val t = port.now()
        return entries.keys.filter { (putAt[it] ?: Long.MIN_VALUE) >= t - windowMs && get(it) != null }.toIntArray()
    }

    val size get() = entries.size
}

/**
 * B3 Plumtree (Leitão et al. 2007): eager push of the payload on tree links, lazy IHAVE on the
 * others. A duplicate turns its link lazy (PRUNE); a message announced by IHAVE but not received
 * within the timeout is pulled with GRAFT, which also makes that link eager. New links start eager.
 *
 * Stage C:
 * - [adaptive] (B1/B3 adaptive): a phone whose own local density class is low floods (B1: payload to
 *   every link, never PRUNE); in medium and high it runs Plumtree. Both modes share one wire format,
 *   so neighbors in different modes interoperate.
 * - Relay preference: in Plumtree mode a node that sleeps delays its eager push, so its neighbors
 *   receive first from awake peers and PRUNE the sleeping one (the tree forms over awake nodes).
 *
 * Stage D catch-up ([RoutingParams.catchUpMs] > 0): when a link comes up, each side announces (IHAVE) the
 * unexpired messages it received within the window, so a phone that slept through a message (background
 * iOS wake windows) or just joined pulls it with GRAFT. Same wire format; costs one IHAVE per new link.
 */
class Plumtree(
    private val port: RoutingPort,
    private val self: Int,
    private val adaptive: Boolean = false,
) : Routing {
    private var flooding = adaptive

    /** Current mode from the phone's local density class (hysteresis and dwell live in the classifier). */
    private fun floodMode(): Boolean {
        if (!adaptive) return false
        val now = port.params.pinFlood || port.densityClass() == 0
        if (now != flooding) modeSwitches++
        flooding = now
        return flooding
    }

    var modeSwitches = 0
        private set
    private class Missing {
        val announcers = ArrayList<Int>(2)
        var armed = false
        var catchUp = false
    }

    /** Stage F A1: broadcasts held until expiry under the shared quota (own ones outside it). */
    private val held = LinkedHashMap<Int, DataPacket>()
    private val holdEvictor = HoldBudget.Evictor { id -> held.remove(id) }

    private fun hold(
        p: DataPacket,
        from: Int,
    ) {
        if (!port.params.holdForOthers || held.containsKey(p.id)) return
        val b = port.holdBudget()
        if (from >= 0 && b != null && !b.admit(p.id, p.payloadBytes, from, p.expiresAt, port.now(), holdEvictor)) return
        held[p.id] = p
    }

    private fun pruneHeld() {
        val b = port.holdBudget()
        val it = held.values.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (port.expired(p)) {
                it.remove()
                b?.release(p.id)
            }
        }
    }

    private fun stored(id: Int): DataPacket? = cache.get(id) ?: held[id]?.takeIf { !port.expired(it) }

    private val eager = LinkedHashSet<Int>()
    private val lazy = LinkedHashSet<Int>()
    private val seen = SeenCache(port)
    private val cache = MessageCache(port)
    private val missing = HashMap<Int, Missing>()

    override fun onLinkUp(peer: Int) {
        lazy.remove(peer)
        eager.add(peer)
        val w = port.params.catchUpMs
        if (w > 0) {
            // Stage F: catch-up announcements are idle control in every arm.
            var ids = cache.recentIds(w)
            if (port.params.holdForOthers) {
                pruneHeld()
                ids = (ids.asList() + held.keys).distinct().toIntArray()
            }
            if (ids.isNotEmpty()) {
                port.count("summaryBroadcastIds", ids.size.toLong())
                port.send(peer, IdsControl(IdsControl.Kind.IHAVE, ids, isIdleControl = true))
            }
        }
    }

    override fun onLinkDown(peer: Int) {
        eager.remove(peer)
        lazy.remove(peer)
    }

    override fun originate(packet: DataPacket) {
        seen.add(packet)
        cache.put(packet)
        hold(packet, -1)
        push(packet, -1)
    }

    private fun push(
        p: DataPacket,
        from: Int,
    ) {
        val out = p.forwarded()
        if (floodMode()) {
            for (peer in port.linkedPeers()) if (peer != from) port.send(peer, out)
            return
        }
        if (port.params.relayPreference && !port.selfStable() && from >= 0) {
            port.after(port.params.sleepyRelayDelayMs) { pushTree(p.id, out, from) }
            return
        }
        pushTree(p.id, out, from)
    }

    private fun pushTree(
        id: Int,
        out: DataPacket,
        from: Int,
    ) {
        if (port.expired(out)) return
        for (peer in eager.toIntArray()) if (peer != from) port.send(peer, out)
        if (lazy.isEmpty()) return
        val ihave = IdsControl(IdsControl.Kind.IHAVE, intArrayOf(id))
        for (peer in lazy.toIntArray()) if (peer != from) port.send(peer, ihave)
    }

    override fun onPacket(
        from: Int,
        packet: Packet,
    ) {
        when (packet) {
            is DataPacket -> onGossip(from, packet)
            is IdsControl ->
                when (packet.kind) {
                    IdsControl.Kind.PRUNE -> {
                        eager.remove(from)
                        if (port.isLinked(from)) lazy.add(from)
                    }
                    IdsControl.Kind.IHAVE -> for (id in packet.ids) onIhave(from, id, packet.isIdleControl)
                    IdsControl.Kind.GRAFT -> {
                        if (!port.isLinked(from)) return
                        lazy.remove(from)
                        eager.add(from)
                        for (id in packet.ids) stored(id)?.let { if (!port.expired(it)) port.send(from, it.forwarded()) }
                    }
                    else -> {}
                }
            else -> {}
        }
    }

    private fun onGossip(
        from: Int,
        p: DataPacket,
    ) {
        if (!seen.add(p)) {
            port.duplicate(p)
            if (!(adaptive && flooding) && eager.remove(from)) {
                lazy.add(from)
                port.send(from, IdsControl(IdsControl.Kind.PRUNE, IntArray(0)))
            }
            return
        }
        missing.remove(p.id)
        if (port.expired(p)) {
            port.drop(p, DropReason.EXPIRED)
            return
        }
        port.deliver(p)
        if (port.isLinked(from)) {
            lazy.remove(from)
            eager.add(from)
        }
        cache.put(p)
        hold(p, from)
        if (p.hops >= p.hopLimit) {
            port.drop(p, DropReason.HOP_LIMIT)
            return
        }
        push(p, from)
    }

    private fun onIhave(
        from: Int,
        id: Int,
        catchUp: Boolean = false,
    ) {
        if (seen.contains(id)) return
        val m = missing.getOrPut(id) { Missing() }
        if (catchUp) m.catchUp = true
        m.announcers.add(from)
        if (!m.armed) {
            m.armed = true
            port.after(port.params.graftTimeoutMs) { onTimer(id) }
        }
    }

    private fun onTimer(id: Int) {
        val m = missing[id] ?: return
        if (seen.contains(id)) {
            missing.remove(id)
            return
        }
        while (m.announcers.isNotEmpty()) {
            val q = m.announcers.removeAt(0)
            if (!port.isLinked(q)) continue
            lazy.remove(q)
            eager.add(q)
            port.send(q, IdsControl(IdsControl.Kind.GRAFT, intArrayOf(id), isIdleControl = m.catchUp))
            port.after(port.params.graftRetryMs) { onTimer(id) }
            return
        }
        m.armed = false
        if (m.announcers.isEmpty()) missing.remove(id)
    }

    override fun seenSize() = seen.size

    override fun tableSize() = cache.size
}

/**
 * Gossipsub v1.1/v1.2 for one topic, without peer scoring: a mesh of D links per node maintained
 * by a 1 s heartbeat (GRAFT below D_lo, PRUNE above D_hi, prune backoff), flood publishing by the
 * originator, mesh forwarding by relays, IHAVE gossip of the last 3 heartbeat windows to D_lazy
 * non-mesh links, IWANT pulls, and IDONTWANT for large messages. D is scaled to BLE degree budgets.
 */
class Gossipsub(
    private val port: RoutingPort,
    private val self: Int,
) : Routing {
    private val mesh = LinkedHashSet<Int>()
    private val backoff = HashMap<Int, Long>()
    private val seen = SeenCache(port)
    private val cache = MessageCache(port)
    private val windows = ArrayDeque<IntArray>()
    private var current = ArrayList<Int>()
    private val requested = HashMap<Int, Long>()
    private val dontWant = HashMap<Int, HashSet<Int>>()
    private var started = false

    private fun start() {
        if (started) return
        started = true
        port.after((port.random.nextDouble() * port.params.gsHeartbeatMs).toLong()) { heartbeat() }
    }

    override fun onLinkUp(peer: Int) = start()

    override fun onLinkDown(peer: Int) {
        mesh.remove(peer)
        dontWant.remove(peer)
    }

    private fun shuffled(a: IntArray): IntArray {
        for (i in a.size - 1 downTo 1) {
            val j = port.random.nextInt(i + 1)
            val t = a[i]
            a[i] = a[j]
            a[j] = t
        }
        return a
    }

    private fun heartbeat() {
        val p = port.params
        val t = port.now()
        val linked = port.linkedPeers()
        mesh.retainAll(linked.toSet())
        if (mesh.size < p.gsDlo) {
            for (peer in shuffled(linked.filter { it !in mesh && (backoff[it] ?: Long.MIN_VALUE) < t }.toIntArray())) {
                if (mesh.size >= p.gsD) break
                mesh.add(peer)
                port.send(peer, IdsControl(IdsControl.Kind.GRAFT, IntArray(0), isIdleControl = true))
            }
        }
        if (mesh.size > p.gsDhi) {
            for (peer in shuffled(mesh.toIntArray())) {
                if (mesh.size <= p.gsD) break
                mesh.remove(peer)
                backoff[peer] = t + p.gsPruneBackoffMs
                port.send(peer, IdsControl(IdsControl.Kind.PRUNE, IntArray(0), isIdleControl = true))
            }
        }
        val ids = ArrayList<Int>()
        for ((k, w) in windows.withIndex()) {
            if (k >= p.gsGossipWindows) break
            for (id in w) if (cache.get(id) != null) ids.add(id)
        }
        if (ids.isNotEmpty()) {
            val ihave = IdsControl(IdsControl.Kind.IHAVE, ids.toIntArray())
            val others = shuffled(linked.filter { it !in mesh }.toIntArray())
            for (k in 0 until minOf(p.gsDlazy, others.size)) port.send(others[k], ihave)
        }
        windows.addFirst(current.toIntArray())
        current = ArrayList()
        while (windows.size > p.gsHistory) windows.removeLast()
        if (backoff.size > 32) backoff.entries.removeAll { it.value < t }
        if (requested.size > 64) requested.entries.removeAll { it.value < t }
        port.after(p.gsHeartbeatMs) { heartbeat() }
    }

    override fun originate(packet: DataPacket) {
        start()
        seen.add(packet)
        cache.put(packet)
        current.add(packet.id)
        val out = packet.forwarded()
        val targets = if (port.params.gsFloodPublish) port.linkedPeers() else mesh.toIntArray()
        for (peer in targets) port.send(peer, out)
    }

    override fun onPacket(
        from: Int,
        packet: Packet,
    ) {
        when (packet) {
            is DataPacket -> onMessage(from, packet)
            is IdsControl ->
                when (packet.kind) {
                    IdsControl.Kind.GRAFT -> {
                        if (!port.isLinked(from)) return
                        if ((backoff[from] ?: Long.MIN_VALUE) >= port.now()) {
                            port.send(from, IdsControl(IdsControl.Kind.PRUNE, IntArray(0), isIdleControl = true))
                        } else {
                            mesh.add(from)
                        }
                    }
                    IdsControl.Kind.PRUNE -> {
                        mesh.remove(from)
                        backoff[from] = port.now() + port.params.gsPruneBackoffMs
                    }
                    IdsControl.Kind.IHAVE -> {
                        val t = port.now()
                        val want = packet.ids.filter { !seen.contains(it) && (requested[it] ?: Long.MIN_VALUE) < t }
                        if (want.isEmpty()) return
                        for (id in want) requested[id] = t + 3 * port.params.gsHeartbeatMs
                        port.send(from, IdsControl(IdsControl.Kind.IWANT, want.toIntArray()))
                    }
                    IdsControl.Kind.IWANT -> for (id in packet.ids) cache.get(id)?.let { if (!port.expired(it)) port.send(from, it.forwarded()) }
                    IdsControl.Kind.IDONTWANT -> dontWant.getOrPut(from) { HashSet() }.addAll(packet.ids.toList())
                }
            else -> {}
        }
    }

    private fun onMessage(
        from: Int,
        p: DataPacket,
    ) {
        if (!seen.add(p)) {
            port.duplicate(p)
            return
        }
        if (port.expired(p)) {
            port.drop(p, DropReason.EXPIRED)
            return
        }
        port.deliver(p)
        cache.put(p)
        current.add(p.id)
        if (p.hops >= p.hopLimit) {
            port.drop(p, DropReason.HOP_LIMIT)
            return
        }
        val targets = mesh.filter { it != from && dontWant[it]?.contains(p.id) != true }
        if (p.sizeBytes(SIZES) >= port.params.gsIdontwantThresholdBytes) {
            val idw = IdsControl(IdsControl.Kind.IDONTWANT, intArrayOf(p.id))
            for (peer in mesh) if (peer != from) port.send(peer, idw)
        }
        val out = p.forwarded()
        for (peer in targets) port.send(peer, out)
    }

    override fun seenSize() = seen.size

    override fun tableSize() = cache.size

    private companion object {
        val SIZES = RoutingSizes()
    }
}
