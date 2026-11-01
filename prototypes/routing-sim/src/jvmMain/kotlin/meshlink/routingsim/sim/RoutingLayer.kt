package meshlink.routingsim.sim

import meshlink.routingsim.routing.BROADCAST
import meshlink.routingsim.routing.DataPacket
import meshlink.routingsim.routing.DistanceVector
import meshlink.routingsim.routing.DropReason
import meshlink.routingsim.routing.Flood
import meshlink.routingsim.routing.Gossipsub
import meshlink.routingsim.routing.OnDemand
import meshlink.routingsim.routing.Packet
import meshlink.routingsim.routing.Plumtree
import meshlink.routingsim.routing.Routing
import meshlink.routingsim.routing.RoutingParams
import meshlink.routingsim.routing.RoutingPort
import meshlink.routingsim.routing.RoutingSizes
import meshlink.routingsim.routing.ScopedSourceRoute
import meshlink.routingsim.strategy.Randomness
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.random.Random

enum class Protocol(
    val label: String,
    val broadcast: Boolean,
) {
    U1("U1", false),
    U2("U2", false),
    U3("U3", false),
    U4("U4", false),
    B1("B1", true),
    B2("B2", true),
    B3("B3", true),
    GS("gossipsub", true),

    /** Stage C: U4 with tuned store-and-forward. */
    U4SF("U4sf", false),

    /** Stage C: hybrid, route when known, else data ring flood, then route requests and holding. */
    U6("U6", false),

    /** Stage C: density-adaptive broadcast, B1 flooding at low local density, B3 Plumtree above. */
    BA("B1B3", true),
}

/** One protocol instance multiplexed over the shared topology (isolated per-instance link queues). */
data class InstanceConfig(
    val protocol: Protocol,
    val lifetimeMs: Long = 300_000,
    val minHoldMs: Long = 10_000,
    val skewMs: Long = 30_000,
    val rotation: Boolean = false,
    /** Stage C relay preference (awake relays first). */
    val pref: Boolean = false,
) {
    val label get() =
        "${protocol.label}/life${lifetimeMs / 1000}/hold${minHoldMs / 1000}/skew${skewMs / 1000}/rot${if (rotation) 1 else 0}" +
            if (pref) "/pref" else ""

    fun params(base: RoutingParams): RoutingParams =
        base.copy(
            minHoldMs = minHoldMs,
            relayPreference = pref,
            sfTuned = protocol == Protocol.U4SF || protocol == Protocol.U6,
            dataRingHops = if (protocol == Protocol.U6) base.u6RingHops else 0,
        )
}

/** Offered traffic. Times in milliseconds of simulated time. */
data class TrafficConfig(
    /** Mean gap between chat messages per honest node (Poisson). */
    val chatMeanGapMs: Double = 240_000.0,
    val nearContacts: Int = 2,
    val farContacts: Int = 1,
    val nearRadiusM: Double = 60.0,
    val payloadBytes: Int = 256,
    /** End-to-end encryption overhead of a later message (AEAD tag, counters). */
    val laterMessageOverhead: Int = 24,
    /** First message of a pair: Noise IK message 1 with 0-RTT payload (ADR 0002), classical. */
    val firstMessageOverheadClassical: Int = 96,
    /** Post-quantum first message adds about 2.3 KB (ADR 0002). */
    val firstMessageOverheadPq: Int = 96 + 2_300,
    /** Mean gap between mesh broadcasts in the whole mesh (Poisson, random honest source). */
    val broadcastMeanGapMs: Double = 15_000.0,
    val trafficStartMs: Long = 60_000,
    /** Messages sent after (duration − this) are not measured (they may not have finished). */
    val tailMs: Long = 60_000,
    val hopLimit: Int = 255,
    val procMinMs: Long = 2,
    val procMaxMs: Long = 10,
    val epochMs: Long = 900_000,
    /** Tag epoch boundary falls at this simulated time (inside the measurement window). */
    val epochBoundaryMs: Long = 500_000,
)

class RoutingSetup(
    val instances: List<InstanceConfig>,
    val traffic: TrafficConfig = TrafficConfig(),
    val params: RoutingParams = RoutingParams(),
)

/** What the routing layer needs from the topology simulation. */
internal interface SimAccess {
    val n: Int
    val now: Long
    val warmupMs: Long
    val durationMs: Long

    fun active(i: Int): Boolean

    fun online(i: Int): Boolean

    fun incarnation(i: Int): Int

    fun link(
        a: Int,
        b: Int,
    ): Link?

    fun peers(i: Int): IntArray

    fun at(
        t: Long,
        block: () -> Unit,
    )

    /** Component labels of honest active nodes (−1 otherwise), recomputed now. */
    fun componentLabels(): IntArray

    /** Hop distances from [s] over honest live links (−1 unreachable), recomputed now. */
    fun distancesFrom(s: Int): IntArray

    fun x(i: Int): Double

    fun y(i: Int): Double

    fun capacityByteSeconds(): Double

    /** Android or foreground iOS (not woken only in short background windows). */
    fun stable(i: Int): Boolean

    /** Ground truth for the density estimator: active honest peers in radio range now. */
    fun peersInRange(i: Int): Int
}

private class InFlight(
    val inst: Int,
    val from: Int,
    val to: Int,
    val packet: Packet,
    val start: Long,
    val bytes: Int,
    val counted: Boolean,
) {
    var cancelled = false
}

private class Msg(
    val id: Int,
    val src: Int,
    val dst: Int,
    val sentAt: Long,
    val measured: Boolean,
    val eligible: Boolean,
    val first: Boolean,
    val pq: Boolean,
    val hopDistance: Int,
    val labels: IntArray?,
    val eligibleCount: Int,
)

private class Stats(
    n: Int,
) {
    val bytesSent = LongArray(n)
    var dataTx = 0L
    var dataBytes = 0L
    var ctrlBytes = 0L
    var idleCtrlBytes = 0L
    var dataRx = 0L
    var dupRx = 0L
    var lostInFlight = 0L
    val drops = LongArray(DropReason.entries.size)
    val hops = IntArray(257)
    val latencies = ArrayList<Double>()
    val firstClassical = ArrayList<Double>()
    val firstPq = ArrayList<Double>()
    var stateSamples = 0
    var tableSum = 0.0
    var seenSum = 0.0
    var heldSum = 0.0
    var seenMax = 0.0
    val packetCounts = HashMap<String, Long>()
}

internal class RoutingLayer(
    private val sim: SimAccess,
    private val setup: RoutingSetup,
    private val seed: Long,
    private val honest: BooleanArray,
) {
    private val n = sim.n
    private val inst = setup.instances
    private val k = inst.size
    private val tc = setup.traffic
    private val sizes = RoutingSizes()
    private val routing = Array(k) { arrayOfNulls<Routing>(n) }
    private val stats = Array(k) { Stats(n) }
    private val procRng = Array(k) { Random(seed xor mix64(1_000L + it)) }
    private val trafficRng = Random(seed xor 0x7a11cL)
    private val broadcastRng = Random(seed xor 0xb0adL)
    private val skewUnit = DoubleArray(n) { Random(seed xor mix64(5_000L + it)).nextDouble() * 2 - 1 }
    private val params = inst.map { it.params(setup.params) }
    private val msgs = ArrayList<Msg>()
    private val delivered = Array(k) { LongArray(1024) { -1 } }
    private val bcastReached = Array(k) { HashMap<Int, BooleanArray>() }
    private val bcastCount = Array(k) { HashMap<Int, Int>() }
    private val bcastLatency = Array(k) { ArrayList<Double>() }
    private val contacts = Array(n) { IntArray(0) }
    private val pairSeen = HashSet<Long>()
    private val unicast = (0 until k).filter { !inst[it].protocol.broadcast }
    private val broadcast = (0 until k).filter { inst[it].protocol.broadcast }

    private fun measuring() = sim.now >= sim.warmupMs

    // ---- clocks and tags ----

    private fun skew(
        i: Int,
        x: Int,
    ) = (skewUnit[i] * inst[x].skewMs).toLong()

    private fun localNow(
        i: Int,
        x: Int,
    ) = sim.now + skew(i, x)

    private fun epochAt(
        localMs: Long,
        x: Int,
    ): Long = if (!inst[x].rotation) 0 else Math.floorDiv(localMs - tc.epochBoundaryMs, tc.epochMs) + 1

    private fun tagOf(
        node: Int,
        epoch: Long,
    ): Long = mix64(seed xor (node.toLong() * 0x9E3779B97F4A7C15uL.toLong()) xor (epoch * 0x632BE59BD9B4E019L)) or 1L

    private fun ownTag(
        i: Int,
        x: Int,
    ) = tagOf(i, epochAt(localNow(i, x), x))

    private val acceptEpoch = Array(k) { LongArray(n) { Long.MIN_VALUE } }
    private val acceptTags = Array(k) { LongArray(3 * n) }

    private fun accepts(
        i: Int,
        x: Int,
        tag: Long,
    ): Boolean {
        val e = epochAt(localNow(i, x), x)
        val cache = acceptTags[x]
        if (acceptEpoch[x][i] != e) {
            acceptEpoch[x][i] = e
            cache[3 * i] = tagOf(i, e)
            cache[3 * i + 1] = if (inst[x].rotation) tagOf(i, e - 1) else cache[3 * i]
            cache[3 * i + 2] = if (inst[x].rotation) tagOf(i, e + 1) else cache[3 * i]
        }
        return tag == cache[3 * i] || tag == cache[3 * i + 1] || tag == cache[3 * i + 2]
    }

    // ---- per-node port ----

    private inner class Port(
        val x: Int,
        val i: Int,
        val inc: Int,
    ) : RoutingPort,
        Randomness {
        private val rng = Random(seed xor mix64(x * 1_000_003L + i * 7_919L + inc))
        lateinit var self: Routing

        override val params get() = this@RoutingLayer.params[x]
        override val random: Randomness get() = this

        override fun nextDouble() = rng.nextDouble()

        override fun nextInt(bound: Int) = rng.nextInt(bound)

        override fun now() = localNow(i, x)

        override fun linkedPeers() = sim.peers(i)

        override fun isLinked(peer: Int) = sim.link(i, peer) != null

        override fun send(
            peer: Int,
            packet: Packet,
        ) = transmit(x, i, peer, packet)

        override fun after(
            delayMs: Long,
            action: () -> Unit,
        ) {
            sim.at(sim.now + delayMs.coerceAtLeast(0)) {
                if (sim.online(i) && sim.incarnation(i) == inc && routing[x][i] === self) action()
            }
        }

        override fun deliver(packet: DataPacket) = onDeliver(x, i, packet)

        override fun duplicate(packet: DataPacket) {
            if (measuring()) stats[x].dupRx++
        }

        override fun ownTag() = ownTag(i, x)

        override fun peerTag(peer: Int) = ownTag(peer, x)

        override fun acceptsTag(tag: Long) = accepts(i, x, tag)

        override fun drop(
            packet: DataPacket,
            reason: DropReason,
        ) {
            if (measuring()) stats[x].drops[reason.ordinal]++
        }

        override fun selfStable() = sim.stable(i)

        override fun peerStable(peer: Int) = sim.stable(peer)

        override fun peersHeard() = heardCount(i)
    }

    // ---- local density estimate (distinct advertisers heard recently) ----

    private val heard = arrayOfNulls<HashMap<Int, Long>>(n)

    fun onScan(
        i: Int,
        results: List<meshlink.routingsim.strategy.ScanResult>,
    ) {
        if (!needsDensity) return
        val m = heard[i] ?: HashMap<Int, Long>().also { heard[i] = it }
        val t = sim.now
        for (r in results) m[r.peer] = t
    }

    private fun heardCount(i: Int): Int {
        val m = heard[i] ?: return 0
        val cutoff = sim.now - setup.params.densityWindowMs
        m.entries.removeAll { it.value < cutoff }
        return m.size
    }

    private val needsDensity = inst.any { it.protocol == Protocol.BA }

    /** Estimator check: confusion of (true class, estimated class) with low < 10 ≤ medium < 50 ≤ high. */
    private val densConfusion = LongArray(9)
    private var densRatioSum = 0.0
    private var densRatioN = 0L

    private fun densClass(peers: Int) = if (peers < 10) 0 else if (peers < 50) 1 else 2

    private fun sampleDensity() {
        sim.at(sim.now + 5_000) { sampleDensity() }
        if (!measuring()) return
        for (i in 0 until n) {
            if (!honest[i] || !sim.active(i)) continue
            val truth = sim.peersInRange(i)
            val est = heardCount(i)
            densConfusion[3 * densClass(truth) + densClass(est)]++
            if (truth > 0) {
                densRatioSum += est.toDouble() / truth
                densRatioN++
            }
        }
    }

    private fun create(
        x: Int,
        i: Int,
    ): Routing {
        val port = Port(x, i, sim.incarnation(i))
        val r =
            when (inst[x].protocol) {
                Protocol.U1, Protocol.B1 -> Flood(port, i)
                Protocol.B2 -> Flood(port, i, fanout = true)
                Protocol.U2 -> ScopedSourceRoute(port, i)
                Protocol.U3 -> DistanceVector(port, i)
                Protocol.U4, Protocol.U4SF, Protocol.U6 -> OnDemand(port, i)
                Protocol.BA -> Plumtree(port, i, adaptive = true)
                Protocol.B3 -> Plumtree(port, i)
                Protocol.GS -> Gossipsub(port, i)
            }
        port.self = r
        return r
    }

    // ---- hooks from the topology simulation ----

    fun start() {
        chooseContacts()
        for (i in 0 until n) {
            if (!honest[i]) continue
            scheduleChat(i, tc.trafficStartMs)
        }
        if (broadcast.isNotEmpty()) scheduleBroadcast(tc.trafficStartMs)
        if (inst.any { it.rotation }) {
            for (i in 0 until n) {
                for (x in 0 until k) {
                    if (!inst[x].rotation) continue
                    // The node's local clock reaches the epoch boundary at true time boundary − skew.
                    var b = tc.epochBoundaryMs
                    while (b - skew(i, x) <= sim.durationMs) {
                        sim.at(b - skew(i, x)) { routing[x][i]?.onTagRotated() }
                        b += tc.epochMs
                    }
                }
            }
        }
        sim.at(sim.warmupMs) { sampleState() }
        if (needsDensity) sim.at(sim.warmupMs) { sampleDensity() }
    }

    fun onJoin(i: Int) {
        if (!honest[i]) return
        for (x in 0 until k) routing[x][i] = create(x, i)
    }

    fun onLeave(i: Int) {
        for (x in 0 until k) routing[x][i] = null
    }

    fun onLinkUp(l: Link) {
        l.rq = LongArray(2 * k)
        for (x in 0 until k) {
            routing[x][l.a]?.onLinkUp(l.b)
            if (l.alive) routing[x][l.b]?.onLinkUp(l.a)
        }
    }

    fun onLinkDown(l: Link) {
        for (x in 0 until k) {
            routing[x][l.a]?.onLinkDown(l.b)
            routing[x][l.b]?.onLinkDown(l.a)
        }
        val flights = l.inflight ?: return
        l.inflight = null
        val t = sim.now
        // Links are acknowledged (GATT write with response / L2CAP credits): the sender learns that
        // every packet not yet acknowledged failed. Queued packets cost nothing; packets on the air
        // used airtime and are counted as lost in flight. Either way the protocol may re-send.
        for (any in flights) {
            val f = any as InFlight
            f.cancelled = true
            if (f.start >= t) {
                if (f.counted) refund(f)
            } else if (f.packet is DataPacket && f.counted) {
                stats[f.inst].lostInFlight++
            }
            routing[f.inst][f.from]?.onSendFailed(f.to, f.packet)
        }
    }

    private fun refund(f: InFlight) {
        val s = stats[f.inst]
        s.bytesSent[f.from] -= f.bytes
        if (f.packet.isControl) {
            s.ctrlBytes -= f.bytes
            if (f.packet.isIdleControl) s.idleCtrlBytes -= f.bytes
        } else {
            s.dataBytes -= f.bytes
            s.dataTx--
        }
    }

    // ---- transmission ----

    private fun transmit(
        x: Int,
        from: Int,
        to: Int,
        packet: Packet,
    ): Boolean {
        val l = sim.link(from, to) ?: return false
        if (!l.alive) return false
        val rq = l.rq ?: return false
        val bytes = packet.sizeBytes(sizes)
        val slot = 2 * x + if (from == l.a) 0 else 1
        val now = sim.now
        val start = maxOf(now, rq[slot])
        val done = start + ceil(bytes * 1000.0 / l.capacityBps).toLong()
        rq[slot] = done
        val r = procRng[x]
        val arrive = done + l.oneWayMs + tc.procMinMs + (r.nextDouble() * (tc.procMaxMs - tc.procMinMs)).toLong()
        val counted = measuring()
        val f = InFlight(x, from, to, packet, start, bytes, counted)
        val list = l.inflight ?: ArrayList<Any>(4).also { l.inflight = it }
        list.add(f)
        if (DEBUG) {
            val key = if (packet is meshlink.routingsim.routing.Control) packet.kind else packet::class.simpleName ?: "?"
            stats[x].packetCounts.merge(key, 1L, Long::plus)
        }
        if (counted) {
            val s = stats[x]
            s.bytesSent[from] += bytes
            if (packet.isControl) {
                s.ctrlBytes += bytes
                if (packet.isIdleControl) s.idleCtrlBytes += bytes
            } else {
                s.dataBytes += bytes
                s.dataTx++
            }
        }
        sim.at(arrive) {
            if (!f.cancelled) {
                l.inflight?.remove(f)
                if (packet is DataPacket && measuring()) stats[x].dataRx++
                routing[x][to]?.onPacket(from, packet)
            }
        }
        return true
    }

    // ---- traffic ----

    private fun chooseContacts() {
        val honestIds = (0 until n).filter { honest[it] }
        for (i in honestIds) {
            val near = ArrayList<Int>()
            for (j in honestIds) {
                if (j == i) continue
                val dx = sim.x(i) - sim.x(j)
                val dy = sim.y(i) - sim.y(j)
                if (dx * dx + dy * dy <= tc.nearRadiusM * tc.nearRadiusM) near.add(j)
            }
            val chosen = LinkedHashSet<Int>()
            near.shuffle(trafficRng)
            for (j in near) if (chosen.size < tc.nearContacts) chosen.add(j)
            var guard = 0
            while (chosen.size < tc.nearContacts + tc.farContacts && guard++ < 100 && honestIds.size > 1) {
                val j = honestIds[trafficRng.nextInt(honestIds.size)]
                if (j != i) chosen.add(j)
            }
            contacts[i] = chosen.toIntArray()
        }
    }

    private fun exp(mean: Double) = (-ln(1.0 - trafficRng.nextDouble()) * mean).toLong()

    private fun scheduleChat(
        i: Int,
        after: Long,
    ) {
        val t = after + exp(tc.chatMeanGapMs)
        if (t > sim.durationMs) return
        sim.at(t) {
            if (unicast.isNotEmpty() && contacts[i].isNotEmpty()) sendChat(i, contacts[i][trafficRng.nextInt(contacts[i].size)])
            scheduleChat(i, t)
        }
    }

    private fun scheduleBroadcast(after: Long) {
        val t = after + (-ln(1.0 - broadcastRng.nextDouble()) * tc.broadcastMeanGapMs).toLong()
        if (t > sim.durationMs) return
        sim.at(t) {
            val src = broadcastRng.nextInt(n)
            if (honest[src] && sim.active(src)) sendBroadcast(src)
            scheduleBroadcast(t)
        }
    }

    private fun grow(id: Int) {
        for (x in 0 until k) {
            if (id >= delivered[x].size) {
                val old = delivered[x]
                delivered[x] = old.copyOf(old.size * 2).also { it.fill(-1, old.size) }
            }
        }
    }

    private fun sendChat(
        src: Int,
        dst: Int,
    ) {
        if (!sim.active(src)) return
        val id = msgs.size
        grow(id)
        val measured = sim.now >= sim.warmupMs && sim.now <= sim.durationMs - tc.tailMs
        val labels = sim.componentLabels()
        val eligible = sim.active(dst) && labels[src] >= 0 && labels[src] == labels[dst]
        val dist = if (eligible) sim.distancesFrom(src)[dst] else -1
        val first = pairSeen.add((src.toLong() shl 32) or dst.toLong())
        val pq = (mix64(seed xor ((src.toLong() shl 32) or dst.toLong())) and 1L) == 1L
        msgs.add(Msg(id, src, dst, sim.now, measured, eligible, first, pq, dist, null, 0))
        val payload =
            tc.payloadBytes +
                when {
                    !first -> tc.laterMessageOverhead
                    pq -> tc.firstMessageOverheadPq
                    else -> tc.firstMessageOverheadClassical
                }
        for (x in unicast) {
            val r = routing[x][src] ?: continue
            val local = localNow(src, x)
            val dstTag = tagOf(dst, epochAt(local, x))
            r.originate(DataPacket(id, ownTag(src, x), dstTag, local + inst[x].lifetimeMs, payload, 0, tc.hopLimit))
        }
    }

    private fun sendBroadcast(src: Int) {
        val id = msgs.size
        grow(id)
        val measured = sim.now >= sim.warmupMs && sim.now <= sim.durationMs - tc.tailMs
        val labels = sim.componentLabels()
        var count = 0
        for (j in 0 until n) if (j != src && labels[j] == labels[src] && labels[src] >= 0) count++
        msgs.add(Msg(id, src, -1, sim.now, measured, count > 0, false, false, -1, labels, count))
        for (x in broadcast) {
            val r = routing[x][src] ?: continue
            val local = localNow(src, x)
            bcastReached[x][id] = BooleanArray(n)
            bcastCount[x][id] = 0
            r.originate(DataPacket(id, ownTag(src, x), BROADCAST, local + inst[x].lifetimeMs, tc.payloadBytes + tc.laterMessageOverhead, 0, tc.hopLimit))
        }
    }

    private fun onDeliver(
        x: Int,
        i: Int,
        p: DataPacket,
    ) {
        val m = msgs[p.id]
        if (m.dst >= 0) {
            if (m.dst != i || delivered[x][p.id] >= 0) return
            delivered[x][p.id] = sim.now
            if (!m.measured || !m.eligible) return
            val s = stats[x]
            val lat = (sim.now - m.sentAt).toDouble()
            s.latencies.add(lat)
            s.hops[minOf(p.hops, 256)]++
            if (m.first && m.hopDistance in 1..3) (if (m.pq) s.firstPq else s.firstClassical).add(lat)
        } else {
            val reached = bcastReached[x][p.id] ?: return
            if (i == m.src || reached[i]) return
            reached[i] = true
            val labels = m.labels!!
            if (labels[i] != labels[m.src]) return
            bcastCount[x][p.id] = (bcastCount[x][p.id] ?: 0) + 1
            if (m.measured) {
                bcastLatency[x].add((sim.now - m.sentAt).toDouble())
                stats[x].hops[minOf(p.hops, 256)]++
            }
        }
    }

    private fun sampleState() {
        sim.at(sim.now + 30_000) { sampleState() }
        for (x in 0 until k) {
            var table = 0.0
            var seen = 0.0
            var held = 0.0
            var nodes = 0
            var seenMax = 0.0
            for (i in 0 until n) {
                val r = routing[x][i] ?: continue
                nodes++
                table += r.tableSize()
                val sz = r.seenSize().toDouble()
                seen += sz
                seenMax = maxOf(seenMax, sz)
                held += r.heldCount()
            }
            if (nodes == 0) continue
            val s = stats[x]
            s.stateSamples++
            s.tableSum += table / nodes
            s.seenSum += seen / nodes
            s.heldSum += held / nodes
            s.seenMax = maxOf(s.seenMax, seenMax)
        }
    }

    // ---- results ----

    fun results(): List<Pair<InstanceConfig, LinkedHashMap<String, Double>>> {
        val seconds = (sim.durationMs - sim.warmupMs) / 1000.0
        val honestCount = honest.count { it }
        val capacity = sim.capacityByteSeconds()
        return (0 until k).map { x ->
            val s = stats[x]
            val out = LinkedHashMap<String, Double>()
            val bcast = inst[x].protocol.broadcast
            val measuredMsgs = msgs.filter { it.measured && (it.dst < 0) == bcast }
            val eligible = measuredMsgs.filter { it.eligible }
            var deliveries: Int
            if (!bcast) {
                val del = eligible.count { delivered[x][it.id] >= 0 }
                deliveries = measuredMsgs.count { delivered[x][it.id] >= 0 }
                out["sent"] = measuredMsgs.size.toDouble()
                out["eligible"] = eligible.size.toDouble()
                out["delivery"] = if (eligible.isEmpty()) Double.NaN else del.toDouble() / eligible.size
                out["deliveryAll"] = if (measuredMsgs.isEmpty()) Double.NaN else deliveries.toDouble() / measuredMsgs.size
                out["latP50Ms"] = percentile(s.latencies, 0.5)
                out["latP95Ms"] = percentile(s.latencies, 0.95)
                out["latP99Ms"] = percentile(s.latencies, 0.99)
                out["firstP95ClassicalMs"] = percentile(s.firstClassical, 0.95)
                out["firstP95PqMs"] = percentile(s.firstPq, 0.95)
                out["firstN"] = (s.firstClassical.size + s.firstPq.size).toDouble()
                out["coverageP10"] = Double.NaN
                out["fullCoverShare"] = Double.NaN
            } else {
                val cov = eligible.mapNotNull { m -> bcastCount[x][m.id]?.let { it.toDouble() / m.eligibleCount } }
                deliveries = eligible.sumOf { bcastCount[x][it.id] ?: 0 }
                out["sent"] = measuredMsgs.size.toDouble()
                out["eligible"] = eligible.size.toDouble()
                out["delivery"] = if (cov.isEmpty()) Double.NaN else cov.average()
                out["deliveryAll"] = Double.NaN
                out["latP50Ms"] = percentile(bcastLatency[x], 0.5)
                out["latP95Ms"] = percentile(bcastLatency[x], 0.95)
                out["latP99Ms"] = percentile(bcastLatency[x], 0.99)
                out["firstP95ClassicalMs"] = Double.NaN
                out["firstP95PqMs"] = Double.NaN
                out["firstN"] = Double.NaN
                out["coverageP10"] = percentile(cov, 0.1)
                out["fullCoverShare"] = if (cov.isEmpty()) Double.NaN else cov.count { it >= 0.99 }.toDouble() / cov.size
            }
            out["dataTxPerDelivery"] = if (deliveries == 0) Double.NaN else s.dataTx.toDouble() / deliveries
            out["dataTxPerNodeS"] = s.dataTx / (honestCount * seconds)
            out["dataBytesPerNodeS"] = s.dataBytes / (honestCount * seconds)
            out["ctrlBytesPerNodeS"] = s.ctrlBytes / (honestCount * seconds)
            out["idleCtrlBytesPerNodeS"] = s.idleCtrlBytes / (honestCount * seconds)
            out["idleCtrlShare"] = if (capacity == 0.0) 0.0 else s.idleCtrlBytes / capacity
            val loads = (0 until n).filter { honest[it] }.map { s.bytesSent[it] / seconds }
            out["loadMeanBps"] = loads.average()
            out["loadP99Bps"] = percentile(loads, 0.99)
            out["loadMaxBps"] = loads.maxOrNull() ?: Double.NaN
            out["dupRate"] = if (s.dataRx == 0L) Double.NaN else s.dupRx.toDouble() / s.dataRx
            out["lostInFlightPerSent"] = if (measuredMsgs.isEmpty()) Double.NaN else s.lostInFlight.toDouble() / measuredMsgs.size
            for (r in DropReason.entries) {
                out["drop${r.name.lowercase().replaceFirstChar { it.uppercase() }}PerSent"] =
                    if (measuredMsgs.isEmpty()) Double.NaN else s.drops[r.ordinal].toDouble() / measuredMsgs.size
            }
            val hopList = ArrayList<Double>()
            for (h in s.hops.indices) repeat(s.hops[h]) { hopList.add(h.toDouble()) }
            out["hopsP50"] = percentile(hopList, 0.5)
            out["hopsP95"] = percentile(hopList, 0.95)
            out["hopsMax"] = hopList.maxOrNull() ?: Double.NaN
            val samples = s.stateSamples.coerceAtLeast(1)
            out["tableMean"] = s.tableSum / samples
            out["seenMean"] = s.seenSum / samples
            out["seenMax"] = s.seenMax
            out["heldMean"] = s.heldSum / samples
            if (inst[x].protocol == Protocol.BA) {
                val total = densConfusion.sum().toDouble()
                out["densEstRatio"] = if (densRatioN == 0L) Double.NaN else densRatioSum / densRatioN
                out["densClassAgree"] = if (total == 0.0) Double.NaN else (densConfusion[0] + densConfusion[4] + densConfusion[8]) / total
                val names = listOf("low", "med", "high")
                for (a in 0 until 3) for (b in 0 until 3) out["dens_${names[a]}_as_${names[b]}"] = densConfusion[3 * a + b] / maxOf(total, 1.0)
                var sw = 0
                for (i in 0 until n) sw += (routing[x][i] as? Plumtree)?.modeSwitches ?: 0
                out["modeSwitchesPerNodeMin"] = sw / (honestCount * seconds / 60.0)
            }
            if (DEBUG) System.err.println("${inst[x].label} packets ${s.packetCounts}")
            inst[x] to out
        }
    }
}

private val DEBUG = System.getProperty("rsim.debug") != null
