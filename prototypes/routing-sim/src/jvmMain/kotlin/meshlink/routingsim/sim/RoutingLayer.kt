package meshlink.routingsim.sim

import meshlink.routingsim.density.DensityClass
import meshlink.routingsim.routing.BROADCAST
import meshlink.routingsim.routing.DataPacket
import meshlink.routingsim.routing.DeliveryAck
import meshlink.routingsim.routing.DistanceVector
import meshlink.routingsim.routing.DropReason
import meshlink.routingsim.routing.Flood
import meshlink.routingsim.routing.Gossipsub
import meshlink.routingsim.routing.IdsControl
import meshlink.routingsim.routing.OnDemand
import meshlink.routingsim.routing.Packet
import meshlink.routingsim.routing.Plumtree
import meshlink.routingsim.routing.RouteReply
import meshlink.routingsim.routing.RouteRequest
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
    /**
     * Stage E: each phone rotates at its own phase of the period [epochMs] (the advertisement phase, or 0 for every
     * phone when aligned) instead of at the shared [epochBoundaryMs].
     */
    val rotationPhased: Boolean = false,
    /** Stage E: a unicast message counts as "after a rotation" if its sender or destination rotated this recently. */
    val postRotationWindowMs: Long = 30_000,
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

    /** Stage E: the phone's rotation phase as a fraction of the period. */
    fun rotationPhaseUnit(i: Int): Double
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

    /** Stage E cut-through: the first fragment arrived; the stream ends at [tailAt]. */
    var headArrived = false
    var tailAt = 0L
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
    /** Stage E: the sender or destination rotated its identifiers within the post-rotation window. */
    val postRot: Boolean = false,
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

    /** Stage S: control bytes per sender, and control bytes on honest–honest links. */
    val ctrlSent = LongArray(n)
    var ctrlHonestLinks = 0L
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
    var rreqBytes = 0L
    var rrepBytes = 0L

    /** Stage E: latency by hops actually travelled (index 1..5). */
    val latByHops = Array(6) { ArrayList<Double>() }

    /** Stage E: first-message latency by [pq] (0 classical, 1 post-quantum) × shortest-path hops 1..3, and × sender class. */
    val firstByHop = Array(2) { Array(4) { ArrayList<Double>() } }
    val firstByClass = Array(2) { Array(3) { ArrayList<Double>() } }
}

internal class RoutingLayer(
    private val sim: SimAccess,
    private val setup: RoutingSetup,
    private val seed: Long,
    private val honest: BooleanArray,
    /** Stage S: nodes that run routing instances (honest phones plus smart fakes); traffic and metrics stay honest. */
    private val relay: BooleanArray = honest,
    /** Stage S: called with (receiver, sender) for each timely delivery an honest phone receives. */
    private val onUseful: ((Int, Int) -> Unit)? = null,
    /** Stage S: extra result columns (kept off so earlier stages' outputs are unchanged). */
    private val sybilMetrics: Boolean = false,
) {
    private val n = sim.n

    /** Stage S usefulness: per node, first-seen time of each delivery key. */
    private val firstSeen = if (onUseful == null) emptyArray() else Array(n) { HashMap<Long, Long>() }
    private val nextPrune = LongArray(if (onUseful == null) 0 else n)
    private val inst = setup.instances
    private val k = inst.size
    private val tc = setup.traffic
    private val sizesOf = inst.map { StageEVariant.sizes(it.variant) }
    private val frag = inst.map { StageEVariant.fragments(it.variant) }
    private val cutThrough = inst.map { StageEVariant.cutThrough(it.variant) }

    /** Cut-through: per instance, (node, message) → when the last fragment of the copy the node took arrives. */
    private val tails = Array(k) { HashMap<Long, Long>() }

    /** Cut-through: per instance, (node, message) whose copy was cut off mid-stream (incomplete). */
    private val broken = Array(k) { HashSet<Long>() }

    /** Cut-through: per instance, (node, message) → onward streams still fed by that node's incoming stream. */
    private val feeds = Array(k) { HashMap<Long, ArrayList<InFlight>>() }
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
        node: Int,
        localMs: Long,
        x: Int,
    ): Long =
        when {
            !inst[x].rotation -> 0
            tc.rotationPhased -> Math.floorDiv(localMs + (sim.rotationPhaseUnit(node) * tc.epochMs).toLong(), tc.epochMs)
            else -> Math.floorDiv(localMs - tc.epochBoundaryMs, tc.epochMs) + 1
        }

    private fun tagOf(
        node: Int,
        epoch: Long,
    ): Long = mix64(seed xor (node.toLong() * 0x9E3779B97F4A7C15uL.toLong()) xor (epoch * 0x632BE59BD9B4E019L)) or 1L

    private fun ownTag(
        i: Int,
        x: Int,
    ) = tagOf(i, epochAt(i, localNow(i, x), x))

    private val acceptEpoch = Array(k) { LongArray(n) { Long.MIN_VALUE } }
    private val acceptTags = Array(k) { LongArray(3 * n) }

    private fun accepts(
        i: Int,
        x: Int,
        tag: Long,
    ): Boolean {
        val e = epochAt(i, localNow(i, x), x)
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
            if (measuring() && honest[i]) stats[x].dupRx++
        }

        override fun ownTag() = ownTag(i, x)

        override fun peerTag(peer: Int) = ownTag(peer, x)

        override fun acceptsTag(tag: Long) = accepts(i, x, tag)

        override fun drop(
            packet: DataPacket,
            reason: DropReason,
        ) {
            if (measuring() && honest[i]) stats[x].drops[reason.ordinal]++
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
                    var b =
                        if (tc.rotationPhased) {
                            val ph = (sim.rotationPhaseUnit(i) * tc.epochMs).toLong()
                            tc.epochMs - ph
                        } else {
                            tc.epochBoundaryMs
                        }
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
        if (!relay[i]) return
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
            if (f.headArrived) {
                // Cut-through stream cut mid-way: the receiver's copy is incomplete (it already relayed the head).
                f.cancelled = true
                val id = (f.packet as DataPacket).id
                if (f.tailAt > t && tails[f.inst][nodeMsg(f.to, id)] == f.tailAt) breakCopy(f.inst, f.to, id)
                if (f.counted) stats[f.inst].lostInFlight++
                routing[f.inst][f.from]?.onSendFailed(f.to, f.packet)
                continue
            }
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
            s.ctrlSent[f.from] -= f.bytes
            if (honest[f.to]) s.ctrlHonestLinks -= f.bytes
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
        // Stage S smart fake: a black hole for unicast data and acks (it claims to have sent them).
        if (!honest[from] && ((packet is DataPacket && !packet.isBroadcast) || packet is DeliveryAck)) return true
        val bytes = packet.sizeBytes(sizesOf[x])
        val slot = 2 * x + if (from == l.a) 0 else 1
        val now = sim.now
        val start = maxOf(now, rq[slot])
        var done = start + ceil(bytes * 1000.0 / l.capacityBps).toLong()
        val r = procRng[x]
        val proc = l.oneWayMs + tc.procMinMs + (r.nextDouble() * (tc.procMaxMs - tc.procMinMs)).toLong()
        val counted = measuring() && honest[from]
        val f = InFlight(x, from, to, packet, start, bytes, counted)
        // Cut-through: forward each fragment as it arrives. The first fragment is processed (and relayed) on arrival;
        // the last one cannot leave before it has arrived from upstream.
        val stream = cutThrough[x] && packet is DataPacket && frag[x] != null && frag[x]!!.first > 1
        var arrive = done + proc
        if (stream) {
            val fragMs = ceil(frag[x]!!.second * 1000.0 / l.capacityBps).toLong()
            val key = nodeMsg(from, (packet as DataPacket).id)
            val upstreamTail = tails[x][key] ?: Long.MIN_VALUE
            if (upstreamTail > start) {
                done = maxOf(done, upstreamTail + fragMs)
                feeds[x].getOrPut(key) { ArrayList(4) }.add(f)
            }
            arrive = start + fragMs + proc
            f.tailAt = done + proc
        }
        rq[slot] = done
        val list = l.inflight ?: ArrayList<Any>(4).also { l.inflight = it }
        list.add(f)
        if (DEBUG) {
            val key = if (packet is meshlink.routingsim.routing.Control) packet.kind else packet::class.simpleName ?: "?"
            stats[x].packetCounts.merge(key, 1L, Long::plus)
        }
        if (counted) {
            val s = stats[x]
            if (packet is meshlink.routingsim.routing.RouteRequest) s.rreqBytes += bytes
            if (packet is meshlink.routingsim.routing.RouteReply) s.rrepBytes += bytes
            s.bytesSent[from] += bytes
            if (packet.isControl) {
                s.ctrlBytes += bytes
                s.ctrlSent[from] += bytes
                if (honest[to]) s.ctrlHonestLinks += bytes
                if (packet.isIdleControl) s.idleCtrlBytes += bytes
            } else {
                s.dataBytes += bytes
                s.dataTx++
            }
        }
        sim.at(arrive) {
            if (!f.cancelled) {
                if (stream) {
                    f.headArrived = true
                    val key = nodeMsg(to, (packet as DataPacket).id)
                    if (key !in tails[x]) tails[x][key] = f.tailAt
                    sim.at(f.tailAt) { l.inflight?.remove(f) }
                } else {
                    l.inflight?.remove(f)
                }
                if (packet is DataPacket && measuring() && honest[to]) stats[x].dataRx++
                if (onUseful != null && honest[to] && routing[x][to] != null) creditUseful(x, from, to, packet)
                routing[x][to]?.onPacket(from, packet)
            }
        }
        return true
    }

    private fun nodeMsg(
        node: Int,
        id: Int,
    ) = (node.toLong() shl 32) or id.toLong()

    /**
     * Stage S usefulness counter: a broadcast data message or IHAVE, route request, route reply or ack whose ID
     * [to] has not seen yet, or first saw less than 1 s ago, earns the link from [from] one credit.
     */
    private fun creditUseful(
        x: Int,
        from: Int,
        to: Int,
        packet: Packet,
    ) {
        val seen = firstSeen[to]
        val now = sim.now
        fun timely(key: Long): Boolean {
            val first = seen.getOrPut(key) { now }
            return now - first < USEFUL_WINDOW_MS
        }
        fun key(
            type: Long,
            v: Long,
        ) = mix64((x.toLong() shl 56) or (type shl 48) or (v and ID_MASK))
        val useful =
            when (packet) {
                is DataPacket -> packet.isBroadcast && timely(key(1, packet.id.toLong()))
                is IdsControl -> {
                    var any = false
                    if (packet.kind == IdsControl.Kind.IHAVE) for (id in packet.ids) if (timely(key(1, id.toLong()))) any = true
                    any
                }
                is RouteRequest -> timely(key(2, mix64(packet.origin) xor packet.requestId.toLong()))
                is RouteReply ->
                    timely(key(3, mix64(mix64(packet.origin) xor packet.dst) xor (packet.dstSeq.toLong() shl 20) xor packet.ackId.toLong()))
                is DeliveryAck -> timely(key(4, packet.msgId.toLong()))
                else -> false
            }
        if (now >= nextPrune[to]) {
            nextPrune[to] = now + USEFUL_PRUNE_MS
            seen.entries.removeAll { now - it.value > USEFUL_MEMORY_MS }
        }
        if (useful) onUseful!!(to, from)
    }

    /** Cut-through: [node]'s copy of message [id] ended incomplete; so does every onward stream it was feeding. */
    private fun breakCopy(
        x: Int,
        node: Int,
        id: Int,
    ) {
        val key = nodeMsg(node, id)
        if (!broken[x].add(key)) return
        val onward = feeds[x].remove(key) ?: return
        val t = sim.now
        for (g in onward) {
            if (g.cancelled || g.tailAt <= t) continue
            g.cancelled = true
            sim.link(g.from, g.to)?.inflight?.remove(g)
            if (g.headArrived && tails[x][nodeMsg(g.to, id)] == g.tailAt) breakCopy(x, g.to, id)
        }
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
        msgs.add(
            Msg(id, src, dst, sim.now, measured, eligible, first, pq, dist, null, 0, sim.peersInRange(src), inTransition(), classesNow(), postRotation(src, dst)),
        )
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
            val dstTag = tagOf(dst, epochAt(dst, local, x))
            val pl = fragmentPayload(x, false) ?: payload
            r.originate(DataPacket(id, ownTag(src, x), dstTag, local + inst[x].lifetimeMs, pl, 0, tc.hopLimit))
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
            val pl = fragmentPayload(x, true) ?: (tc.payloadBytes + tc.laterMessageOverhead)
            r.originate(DataPacket(id, ownTag(src, x), BROADCAST, local + inst[x].lifetimeMs, pl, 0, tc.hopLimit))
        }
    }

    /** Stage E: payload that makes the whole message exactly k fragments of f bytes (null = normal traffic sizes). */
    private fun fragmentPayload(
        x: Int,
        bcast: Boolean,
    ): Int? {
        val (kf, f) = frag[x] ?: return null
        val header = DataPacket(0, 1, if (bcast) BROADCAST else 1, 0, 0, 0, 0).sizeBytes(sizesOf[x])
        return kf * f - header
    }

    /** Stage E: the sender or destination rotated (true time, phased rotation) within the post-rotation window. */
    private fun postRotation(
        src: Int,
        dst: Int,
    ): Boolean {
        val x0 = inst.indexOfFirst { it.rotation }
        if (!tc.rotationPhased || x0 < 0) return false
        fun recent(i: Int): Boolean {
            val ph = (sim.rotationPhaseUnit(i) * tc.epochMs).toLong()
            val local = localNow(i, x0)
            val ago = Math.floorMod(local + ph, tc.epochMs)
            return ago < tc.postRotationWindowMs && local - ago > 0
        }
        return recent(src) || recent(dst)
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
        if (cutThrough[x]) {
            // The message is complete only when its last fragment arrives (and only if the stream was not cut).
            val key = nodeMsg(i, p.id)
            val tail = tails[x][key]
            if (tail != null && tail > sim.now) {
                sim.at(tail) { if (key !in broken[x]) deliverNow(x, i, p) }
                return
            }
            if (key in broken[x]) return
        }
        deliverNow(x, i, p)
    }

    private fun deliverNow(
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
            if (p.hops in 1..5) s.latByHops[p.hops].add(lat)
            if (m.first && m.hopDistance in 1..3) {
                (if (m.pq) s.firstPq else s.firstClassical).add(lat)
                val q = if (m.pq) 1 else 0
                s.firstByHop[q][m.hopDistance].add(lat)
                if (m.srcPeers >= 0) s.firstByClass[q][m.srcTrueClass].add(lat)
            }
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
                if (p.hops in 1..5) stats[x].latByHops[p.hops].add(lat)
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
                if (!honest[i]) continue
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
            if (tc.rotationPhased) {
                group("postrot") { it.postRot }
                group("steady") { !it.postRot }
            }
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

    /** Stage S: routing control bytes each node sent (all instances, measured period). */
    fun ctrlSentByNode(): LongArray {
        val out = LongArray(n)
        for (s in stats) for (i in 0 until n) out[i] += s.ctrlSent[i]
        return out
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
                if (sybilMetrics) {
                    // Arrival among all honest phones active at send (not only the sender's honest group).
                    val all =
                        measuredMsgs.mapNotNull { m ->
                            val reached = bcastReached[x][m.id] ?: return@mapNotNull null
                            val labels = m.labels!!
                            var elig = 0
                            var got = 0
                            for (j in 0 until n) {
                                if (j == m.src || labels[j] < 0) continue
                                elig++
                                if (reached[j]) got++
                            }
                            if (elig == 0) null else got.toDouble() / elig
                        }
                    out["arrivalAllHonest"] = if (all.isEmpty()) Double.NaN else all.average()
                }
            }
            out["dataTxPerDelivery"] = if (deliveries == 0) Double.NaN else s.dataTx.toDouble() / deliveries
            out["dataTxPerNodeS"] = s.dataTx / (honestCount * seconds)
            out["dataBytesPerNodeS"] = s.dataBytes / (honestCount * seconds)
            out["ctrlBytesPerNodeS"] = s.ctrlBytes / (honestCount * seconds)
            out["idleCtrlBytesPerNodeS"] = s.idleCtrlBytes / (honestCount * seconds)
            out["idleCtrlShare"] = if (capacity == 0.0) 0.0 else s.idleCtrlBytes / capacity
            if (sybilMetrics) out["ctrlShareHonestLinks"] = if (capacity == 0.0) 0.0 else s.ctrlHonestLinks / capacity
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
            out["rreqBytesPerNodeS"] = s.rreqBytes / (honestCount * seconds)
            out["rrepBytesPerNodeS"] = s.rrepBytes / (honestCount * seconds)
            for (h in 1..5) {
                out["latH${h}P50Ms"] = percentile(s.latByHops[h], 0.5)
                out["latH${h}P95Ms"] = percentile(s.latByHops[h], 0.95)
                out["latH${h}N"] = s.latByHops[h].size.toDouble()
            }
            if (!bcast) {
                for (q in 0..1) {
                    val name = if (q == 0) "C" else "P"
                    val budget = if (q == 0) 1_000.0 else 1_500.0
                    for (h in 1..3) {
                        val l = s.firstByHop[q][h]
                        out["first${name}H${h}P95Ms"] = percentile(l, 0.95)
                        out["first${name}H${h}N"] = l.size.toDouble()
                        out["first${name}H${h}Ok"] = l.count { it <= budget }.toDouble()
                    }
                    for (c in 0 until 3) {
                        val l = s.firstByClass[q][c]
                        val cl = DensityClass.labels[c]
                        out["first${name}_${cl}P95Ms"] = percentile(l, 0.95)
                        out["first${name}_${cl}N"] = l.size.toDouble()
                        out["first${name}_${cl}Ok"] = l.count { it <= budget }.toDouble()
                    }
                }
            }
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
                for (i in 0 until n) if (honest[i]) sw += (routing[x][i] as? Plumtree)?.modeSwitches ?: 0
                out["modeSwitchesPerNodeH"] = sw / (honestCount * seconds / 3600.0)
            }
            densityResults(x, out)
            if (DEBUG) System.err.println("${inst[x].label} packets ${s.packetCounts}")
            inst[x] to out
        }
    }
}

private val DEBUG = System.getProperty("rsim.debug") != null

private const val USEFUL_WINDOW_MS = 1_000L
private const val USEFUL_MEMORY_MS = 600_000L
private const val USEFUL_PRUNE_MS = 30_000L
private const val ID_MASK = (1L shl 48) - 1

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
