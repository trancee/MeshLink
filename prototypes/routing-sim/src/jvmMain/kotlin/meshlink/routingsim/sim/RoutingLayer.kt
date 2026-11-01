package meshlink.routingsim.sim

import meshlink.routingsim.density.DensityClass
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
import meshlink.routingsim.routing.UnicastClassPolicy
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

    /** Stage C adaptive unicast: U1-like flood at low local density, U6 at medium and high. */
    UA("UA", false),

    /** Stage C adaptive unicast: U1-like flood at low, U6 at medium, U4 (tuned holding) at high. */
    UA4("UA4", false),
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
    /** Stage D variant flags (see [StageDVariant]), e.g. "K1+S5". */
    val variant: String = "",
) {
    val label get() =
        "${protocol.label}/life${lifetimeMs / 1000}/hold${minHoldMs / 1000}/skew${skewMs / 1000}/rot${if (rotation) 1 else 0}" +
            (if (pref) "/pref" else "") + (if (variant.isNotEmpty()) "/$variant" else "")

    fun params(base: RoutingParams): RoutingParams = StageDVariant.apply(variant, stageCParams(base))

    private fun stageCParams(base: RoutingParams): RoutingParams =
        base.copy(
            minHoldMs = minHoldMs,
            relayPreference = pref,
            sfTuned = protocol == Protocol.U4SF || protocol == Protocol.U6 || protocol == Protocol.UA || protocol == Protocol.UA4,
            dataRingHops = if (protocol == Protocol.U6) base.u6RingHops else 0,
            classPolicy =
                when (protocol) {
                    Protocol.UA -> adaptivePolicy("UA", base.u6RingHops, base.u6RingHops)
                    Protocol.UA4 -> adaptivePolicy("UA4", base.u6RingHops, 0)
                    else -> null
                },
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
    /** Stage D conversation traffic: the recipient answers with this probability (0 = one-way chat only). */
    val replyProb: Double = 0.0,
    /** Mean reply delay (exponential). */
    val replyMeanDelayMs: Double = 30_000.0,
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

    /** The phone's own current density class (stage C driving classifier). */
    fun densityClass(i: Int): Int

    /** The phone's own smoothed density estimate (driving estimator), −1 before its first scan. */
    fun densityEstimate(i: Int): Double

    val transitionWindowMs: LongRange?
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
    /** Stage C: true peers in the sender's radio range at send, and classes of every phone at send. */
    val srcPeers: Int = -1,
    val inTransition: Boolean = false,
    val classes: ByteArray? = null,
) {
    val srcTrueClass get() = DensityClass.of(srcPeers.toDouble())
    val crossMode get() = dst >= 0 && classes != null && classes[src] != classes[dst]
}

/** Upper bounds of the true-density bins (peers in range) used to confirm the class boundaries. */
val densityBins = intArrayOf(2, 5, 10, 20, 35, 50, 100, Int.MAX_VALUE)

fun densityBin(peers: Int) = densityBins.indexOfFirst { peers < it }

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
    private val replyRng = Random(seed xor 0x4e91eL)
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

        override fun densityClass() = sim.densityClass(i)

        override fun densityEstimate() = sim.densityEstimate(i)
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
                Protocol.U4, Protocol.U4SF, Protocol.U6, Protocol.UA, Protocol.UA4 -> OnDemand(port, i)
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

    /** Conversation traffic: the recipient answers (independent of delivery, so every instance sees the same traffic). */
    private fun maybeReply(
        src: Int,
        dst: Int,
    ) {
        if (tc.replyProb <= 0.0 || replyRng.nextDouble() >= tc.replyProb) return
        val t = sim.now + (-ln(1.0 - replyRng.nextDouble()) * tc.replyMeanDelayMs).toLong()
        if (t > sim.durationMs) return
        sim.at(t) { sendChat(dst, src) }
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
        msgs.add(Msg(id, src, dst, sim.now, measured, eligible, first, pq, dist, null, 0, sim.peersInRange(src), inTransition(), classesNow()))
        maybeReply(src, dst)
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
        msgs.add(Msg(id, src, -1, sim.now, measured, count > 0, false, false, -1, labels, count, sim.peersInRange(src), inTransition(), classesNow()))
        for (x in broadcast) {
            val r = routing[x][src] ?: continue
            val local = localNow(src, x)
            bcastReached[x][id] = BooleanArray(n)
            bcastCount[x][id] = 0
            r.originate(DataPacket(id, ownTag(src, x), BROADCAST, local + inst[x].lifetimeMs, tc.payloadBytes + tc.laterMessageOverhead, 0, tc.hopLimit))
        }
    }

    private val transition = sim.transitionWindowMs

    private fun inTransition() = transition != null && sim.now in transition

    private fun classesNow() = ByteArray(n) { sim.densityClass(it).toByte() }

    /** Broadcast latencies per instance: [0..2] sender's true class, 3 transition window, 4 cross-mode receivers. */
    private val bcastLatGroups = Array(k) { Array(5) { ArrayList<Double>() } }

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
                val lat = (sim.now - m.sentAt).toDouble()
                bcastLatency[x].add(lat)
                val g = bcastLatGroups[x]
                g[m.srcTrueClass].add(lat)
                if (m.inTransition) g[3].add(lat)
                if (m.classes != null && m.classes[i] != m.classes[m.src]) g[4].add(lat)
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

    /** Stage C: delivery and p95 by the sender's true class, density bin, transition window and mode boundary. */
    private fun densityResults(
        x: Int,
        out: LinkedHashMap<String, Double>,
    ) {
        val bcast = inst[x].protocol.broadcast
        val ms = msgs.filter { it.measured && it.eligible && (it.dst < 0) == bcast && it.srcPeers >= 0 }
        if (!bcast) {
            fun group(
                name: String,
                sel: (Msg) -> Boolean,
            ) {
                val g = ms.filter(sel)
                val del = g.map { if (delivered[x][it.id] >= 0) 1.0 else 0.0 }
                val lat = g.filter { delivered[x][it.id] >= 0 }.map { (delivered[x][it.id] - it.sentAt).toDouble() }
                putGroup(out, name, del, g.size, lat)
            }
            for (c in 0 until 3) group(DensityClass.labels[c]) { it.srcTrueClass == c }
            for (b in densityBins.indices) group("bin$b") { densityBin(it.srcPeers) == b }
            group("trans") { it.inTransition }
            group("cross") { it.crossMode }
            group("near") { it.hopDistance in 1..3 }
            group("far") { it.hopDistance > 3 }
            group("repeat") { !it.first }
            for (c in 1..2) group("${DensityClass.labels[c]}Repeat") { it.srcTrueClass == c && !it.first }
        } else {
            val g = bcastLatGroups[x]
            fun cov(m: Msg): Double = (bcastCount[x][m.id] ?: 0).toDouble() / m.eligibleCount
            for (c in 0 until 3) {
                val sel = ms.filter { it.srcTrueClass == c }
                putGroup(out, DensityClass.labels[c], sel.map { cov(it) }, sel.size, g[c])
            }
            for (b in densityBins.indices) {
                val sel = ms.filter { densityBin(it.srcPeers) == b }
                putGroup(out, "bin$b", sel.map { cov(it) }, sel.size, emptyList())
            }
            val tr = ms.filter { it.inTransition }
            putGroup(out, "trans", tr.map { cov(it) }, tr.size, g[3])
            // Cross-mode: share of eligible receivers in another class than the sender that got the message.
            var crossElig = 0
            var crossGot = 0
            for (m in ms) {
                val cl = m.classes ?: continue
                val labels = m.labels!!
                val reached = bcastReached[x][m.id]
                for (j in 0 until n) {
                    if (j == m.src || labels[j] != labels[m.src] || cl[j] == cl[m.src]) continue
                    crossElig++
                    if (reached != null && reached[j]) crossGot++
                }
            }
            out["n_cross"] = crossElig.toDouble()
            out["del_cross"] = if (crossElig == 0) Double.NaN else crossGot.toDouble() / crossElig
            out["p95_cross"] = percentile(g[4], 0.95)
        }
    }

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
                var sw = 0
                for (i in 0 until n) sw += (routing[x][i] as? Plumtree)?.modeSwitches ?: 0
                out["modeSwitchesPerNodeH"] = sw / (honestCount * seconds / 3600.0)
            }
            densityResults(x, out)
            if (DEBUG) System.err.println("${inst[x].label} packets ${s.packetCounts}")
            inst[x] to out
        }
    }
}

private val DEBUG = System.getProperty("rsim.debug") != null

/**
 * Adaptive unicast per local density class. Low: flood every message and keep it until acknowledged,
 * re-trying on every new link with a 2–30 s backoff (eager store-and-forward). Medium: data ring of
 * [mediumRing] hops, same retries. High: data ring of [highRing] hops (0 = route requests only),
 * no re-try on new links (links come and go constantly in a crowd) and a 5–60 s backoff.
 */
fun adaptivePolicy(
    label: String,
    mediumRing: Int,
    highRing: Int,
) = UnicastClassPolicy(
    label,
    ringHops = intArrayOf(UnicastClassPolicy.FLOOD_ALL, mediumRing, highRing),
    linkUpRetry = booleanArrayOf(true, true, false),
    retryMinMs = longArrayOf(2_000, 2_000, 5_000),
    retryMaxMs = longArrayOf(30_000, 30_000, 60_000),
)

private fun putGroup(
    out: LinkedHashMap<String, Double>,
    name: String,
    deliveries: List<Double>,
    count: Int,
    latencies: List<Double>,
) {
    out["n_$name"] = count.toDouble()
    out["del_$name"] = if (count == 0) Double.NaN else deliveries.sum() / count
    out["p95_$name"] = percentile(latencies, 0.95)
}
