package meshlink.routingsim.sim

import meshlink.routingsim.density.DensityClass
import meshlink.routingsim.routing.BROADCAST
import meshlink.routingsim.routing.DataPacket
import meshlink.routingsim.routing.DistanceVector
import meshlink.routingsim.routing.DropReason
import meshlink.routingsim.routing.Flood
import meshlink.routingsim.routing.Gossipsub
import meshlink.routingsim.routing.HoldBudget
import meshlink.routingsim.routing.OnDemand
import meshlink.routingsim.routing.Packet
import meshlink.routingsim.routing.RouteReply
import meshlink.routingsim.routing.RouteRequest
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

    fun ios(i: Int): Boolean

    /** Stage F: the phone is backgrounded (iOS wake windows, or Android background scanning in bg50 cells). */
    fun backgrounded(i: Int): Boolean

    /** Stage F: (online ms, scan ms, link ms, topology bytes tx+rx) over the measured window. */
    fun energyBase(i: Int): LongArray
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
    /** Stage F A4: a large message (payload [largeBytes]) in instances with large messages. */
    val large: Boolean = false,
    val largeBytes: Int = 0,
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
    var rreqBytes = 0L
    var rrepBytes = 0L

    /** Stage E: latency by hops actually travelled (index 1..5). */
    val latByHops = Array(6) { ArrayList<Double>() }

    /** Stage E: first-message latency by [pq] (0 classical, 1 post-quantum) × shortest-path hops 1..3, and × sender class. */
    val firstByHop = Array(2) { Array(4) { ArrayList<Double>() } }
    val firstByClass = Array(2) { Array(3) { ArrayList<Double>() } }

    // Stage F.
    val bytesRecv = LongArray(n)
    val bulkTx = LongArray(n)
    val bulkRx = LongArray(n)
    val counters = HashMap<String, Long>()

    /** First messages (≤ 3 hops) that needed a certificate lookup first, by suite. */
    val firstLookup = Array(2) { ArrayList<Double>() }
    val lookupLat = ArrayList<Double>()
    val largeLat = ArrayList<Double>()
    var rreqTx = 0L
    var lookupReqTx = 0L

    /** Stage F2: hops from requester to the node whose reply completed each lookup (measured window). */
    val lookupHops = ArrayList<Int>()

    /** Stage F2: non-bulk bytes sent per node by [SentKind]. */
    val kindSent = Array(SentKind.N) { LongArray(n) }
}

/** Stage F2 busiest-phone breakdown of sent bytes (bulk excluded). */
private object SentKind {
    const val DATA = 0
    const val RREQ = 1
    const val RREP = 2
    const val LOOKUP_REQ = 3
    const val LOOKUP_REP = 4
    const val IDLE = 5
    const val OTHER = 6
    const val BROADCAST = 7
    const val N = 8
    val labels = listOf("data", "rreq", "rrep", "lkReq", "lkRep", "idle", "other", "bcast")
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

    // ---- stage F state ----

    private fun flag(f: String) = inst.map { StageFVariant.has(it.variant, f) }

    private val fH = flag("H")
    private val fCVL = inst.map { StageFVariant.has(it.variant, "CVL") }
    private val fCV = inst.map { StageFVariant.has(it.variant, "CV") || StageFVariant.has(it.variant, "CVL") }
    private val fLK = flag("LK")
    private val fLG = flag("LG")
    private val fATK = flag("ATK")
    private val groupName = inst.map { StageFVariant.group(it.variant) }
    private val groups = groupName.filter { it.isNotEmpty() }.distinct()
    private val gIdx = IntArray(k) { groups.indexOf(groupName[it]) }
    private val groupHold = BooleanArray(groups.size) { g -> (0 until k).any { gIdx[it] == g && fH[it] } }
    private val budgets = Array(groups.size) { arrayOfNulls<HoldBudget>(n) }
    private val budgetCounters = Array(groups.size) { LongArray(5) }
    private val holdSum = DoubleArray(groups.size)
    private val holdBytesSum = DoubleArray(groups.size)
    private val holdMax = DoubleArray(groups.size)
    private var holdSamples = 0
    private var linkUpsMeasured = 0L

    /** A2: per instance, established sessions by unordered pair → incarnations of both ends when established. */
    private val sessions = Array(k) { HashMap<Long, Long>() }

    /** A2: conversation tag → (sender, receiver, incarnations, epoch). Shared by every instance (tags are equal). */
    private class ConvInfo(
        val from: Int,
        val to: Int,
        val sig: Long,
        val epoch: Long,
    )

    private val convReg = HashMap<Long, ConvInfo>()

    /** A3: per instance, per node certificate cache (LRU, 64), kept across churn (persisted). */
    private val certs = Array(k) { arrayOfNulls<LinkedHashMap<Int, Boolean>>(n) }

    private class Queued(
        val id: Int,
        val dst: Int,
        val expiresAt: Long,
        val payload: Int,
    )

    /** A3: messages waiting for a certificate, per instance and node. */
    private val lkQueue = Array(k) { arrayOfNulls<ArrayList<Queued>>(n) }

    /** A3: messages that needed a lookup, per instance. */
    private val lookupMsg = Array(k) { HashSet<Int>() }

    /** Diagnosis trace (env MLSIM_TRACE_LK=<ms threshold>): lookup timeline of queued messages; no effect on behavior. */
    private class LkTrace(
        val queuedAt: Long,
        val state: String,
    ) {
        val tx = ArrayList<Long>()
        var doneAt = -1L
        var answerHops = -1
        var releasedAt = -1L
        var releaseState = ""
    }

    private val lkTraceMs = System.getenv("MLSIM_TRACE_LK")?.toLongOrNull()

    /** Diagnosis trace (env MLSIM_TRACE_MSG=id:src:dst:fromMs:toMs,...): sends of that data packet and RREQ/RREP by src or dst. */
    private val msgTrace =
        System.getenv("MLSIM_TRACE_MSG")?.split(',')?.map { it.split(':').map(String::toLong) } ?: emptyList()

    private fun traceSend(
        x: Int,
        i: Int,
        peer: Int,
        p: Packet,
    ) {
        if (msgTrace.isEmpty() || !inst[x].label.endsWith("GA4D") || inst[x].protocol != Protocol.UA) return
        for ((id, src, dst, from, to) in msgTrace) {
            if (sim.now !in from..to) continue
            val hit =
                when (p) {
                    is DataPacket -> p.id.toLong() == id
                    is RouteRequest, is RouteReply -> i.toLong() == src || i.toLong() == dst
                    else -> false
                }
            if (!hit) continue
            val what =
                when (p) {
                    is DataPacket -> "DATA hops ${p.hops}"
                    is RouteRequest -> "RREQ id ${p.requestId} hops ${p.hops} radius ${p.radius}"
                    is RouteReply -> "RREP hops ${p.hops} ack ${p.ackId}"
                    else -> ""
                }
            println("MSGTRACE $id t+${sim.now - from} $i->$peer $what | node ${nodeState(i)} peer ${nodeState(peer)} | src ${nodeState(src.toInt())} dst ${nodeState(dst.toInt())}")
        }
    }
    private val lkTrace = Array(k) { HashMap<Int, LkTrace>() }

    private fun nodeState(j: Int) =
        "${if (sim.ios(j)) "iOS" else "And"}${if (sim.backgrounded(j)) "/bg" else ""}${if (!sim.online(j)) "/offline" else if (!sim.active(j)) "/asleep" else ""}" +
            "/inc${sim.incarnation(j)}/links${sim.peers(j).size}"

    private fun pairState(
        a: Int,
        b: Int,
    ) = "src ${nodeState(a)} dst ${nodeState(b)} dist ${if (sim.active(a) && sim.active(b)) sim.distancesFrom(a)[b] else -1}"

    private fun traceQueued(
        x: Int,
        i: Int,
        target: Int,
        f: (LkTrace) -> Unit,
    ) {
        if (lkTraceMs == null) return
        lkQueue[x][i]?.forEach { q -> if (q.dst == target) lkTrace[x][q.id]?.let(f) }
    }

    /** Rate-limit test: the misbehaving phone (−1 none). */
    private val attacker: Int =
        if (fATK.none { it }) {
            -1
        } else {
            val ids = (0 until n).filter { honest[it] }
            ids[Random(seed xor 0xa77acL).nextInt(ids.size)]
        }
    private var attackCounter = 0

    private fun lifetimeEpochs(x: Int) = ceil(inst[x].lifetimeMs.toDouble() / tc.epochMs).toLong()

    private fun pairU(
        a: Int,
        b: Int,
    ) = (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()

    private fun incSig(
        a: Int,
        b: Int,
    ): Long {
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        return (sim.incarnation(lo).toLong() shl 32) or sim.incarnation(hi).toLong()
    }

    private fun sessionValid(
        x: Int,
        a: Int,
        b: Int,
    ) = sessions[x][pairU(a, b)] == incSig(a, b)

    /** Each pair's conversation epochs share one random phase (both directions rotate together). */
    private fun convEpoch(
        node: Int,
        x: Int,
        a: Int,
        b: Int,
    ): Long {
        val phase = Math.floorMod(mix64(seed xor 0x9a5eL xor pairU(a, b) * 0x2545F4914F6CDD1DL), tc.epochMs)
        return Math.floorDiv(localNow(node, x) + phase, tc.epochMs)
    }

    private fun convTag(
        from: Int,
        to: Int,
        epoch: Long,
    ): Long {
        val sig = incSig(from, to)
        val dir = if (from < to) 0x51L else 0xa3L
        val t =
            mix64(seed xor 0xc0417L xor pairU(from, to) * 0x9E3779B97F4A7C15uL.toLong() xor sig * 0x632BE59BD9B4E019L xor dir xor epoch * 0x7FB5D329728EA185L) or 1L
        if (!convReg.containsKey(t)) {
            convReg[t] = ConvInfo(from, to, sig, epoch)
            if (measuring()) convNew++
        }
        return t
    }

    private var convNew = 0L
    private val lgSig = HashMap<Int, Long>()

    /** A2: [tag] is a conversation tag addressed to [i] within [back] epochs before to one epoch after now. */
    private fun convAccepts(
        i: Int,
        x: Int,
        tag: Long,
        back: Long,
    ): Boolean {
        val c = convReg[tag] ?: return false
        if (c.to != i || c.sig != incSig(c.from, c.to)) return false
        val e = convEpoch(i, x, c.from, c.to)
        return c.epoch in e - back..e + 1
    }

    /** The tags message [id] from [src] to [dst] carries if sent now in instance [x] (source tag, destination tag). */
    private fun tagsFor(
        x: Int,
        src: Int,
        dst: Int,
    ): Pair<Long, Long> {
        if (fCV[x] && sessionValid(x, src, dst)) {
            val e = convEpoch(src, x, src, dst)
            if (fCVL[x]) {
                // A2L: keep the previous epoch's tag while its route works; find the new tag's route in the background.
                val r = routing[x][src]
                val old = convTag(src, dst, e - 1)
                if (r != null && r.hasRoute(old)) {
                    r.prefetch(convTag(src, dst, e))
                    countF(x, "convOldUsed")
                    return convTag(dst, src, e - 1) to old
                }
                countF(x, "convOldMiss")
            }
            return convTag(dst, src, e) to convTag(src, dst, e)
        }
        val local = localNow(src, x)
        return ownTag(src, x) to tagOf(dst, epochAt(dst, local, x))
    }

    private fun claims(
        i: Int,
        x: Int,
        tag: Long,
    ): Boolean {
        val l = lifetimeEpochs(x)
        val e = epochAt(i, localNow(i, x), x)
        for (ep in e - l - 1..e + 1) if (tagOf(i, ep) == tag) return true
        return fCV[x] && convAccepts(i, x, tag, l + 1)
    }

    private fun certCache(
        x: Int,
        i: Int,
    ): LinkedHashMap<Int, Boolean>? {
        if (!fLK[x]) return null
        return certs[x][i] ?: LinkedHashMap<Int, Boolean>(16, 0.75f, true).also { certs[x][i] = it }
    }

    private fun addCert(
        x: Int,
        i: Int,
        target: Int,
    ) {
        val c = certCache(x, i) ?: return
        if (target == i) return
        c[target] = true
        if (c.size > CERT_CACHE) c.remove(c.keys.first())
    }

    private fun hasCert(
        x: Int,
        i: Int,
        target: Int,
    ) = target == i || certCache(x, i)?.get(target) == true

    private fun pqPair(
        a: Int,
        b: Int,
    ) = (mix64(seed xor ((a.toLong() shl 32) or b.toLong())) and 1L) == 1L

    private fun countF(
        x: Int,
        key: String,
        v: Long = 1,
    ) {
        if (measuring()) stats[x].counters.merge(key, v, Long::plus)
    }

    /** A3: the certificate arrived (or was there): send every message that waited for it. */
    private fun releaseQueued(
        x: Int,
        i: Int,
        target: Int,
    ) {
        val q = lkQueue[x][i] ?: return
        val r = routing[x][i] ?: return
        val ready = q.filter { it.dst == target }
        if (ready.isEmpty()) return
        if (lkTraceMs != null) {
            for (m in ready) {
                lkTrace[x][m.id]?.let {
                    it.releasedAt = sim.now
                    it.releaseState = pairState(i, target)
                }
            }
        }
        q.removeAll { it.dst == target }
        val now = localNow(i, x)
        for (m in ready) {
            if (m.expiresAt < now) {
                countF(x, "lookupExpiredMsgs")
                continue
            }
            val (st, dt) = tagsFor(x, i, target)
            r.originate(DataPacket(m.id, st, dt, m.expiresAt, m.payload, 0, tc.hopLimit))
        }
    }

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
        ): Boolean {
            traceSend(x, i, peer, packet)
            return transmit(x, i, peer, packet)
        }

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

        override fun acceptsTag(tag: Long) = accepts(i, x, tag) || (fCV[x] && convAccepts(i, x, tag, 1))

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

        override fun holdBudget(): HoldBudget? = if (params.holdForOthers && gIdx[x] >= 0) budgets[gIdx[x]][i] else null

        override fun claimsTag(tag: Long) = claims(i, x, tag)

        override fun retag(p: DataPacket): DataPacket? {
            val m = msgs[p.id]
            if (m.dst < 0 || m.src != i) return null
            val (st, dt) = tagsFor(x, i, m.dst)
            if (st == p.srcTag && dt == p.dstTag) return null
            return DataPacket(p.id, st, dt, p.expiresAt, p.payloadBytes, 0, p.hopLimit)
        }

        override fun deliverClaimed(p: DataPacket) {
            if (msgs[p.id].dst == i && delivered[x][p.id] < 0) countF(x, "claimDelivered")
            onDeliver(x, i, p)
        }

        override fun hasCert(target: Int) = hasCert(x, i, target)

        override fun certBytes(
            requester: Int,
            target: Int,
        ) = if (pqPair(requester, target)) CERT_PQ else CERT_CLASSICAL

        override fun lookupWanted(target: Int): Boolean {
            val q = lkQueue[x][i] ?: return false
            val now = now()
            val gone = q.filter { it.dst == target && it.expiresAt < now }
            if (gone.isNotEmpty()) {
                countF(x, "lookupExpiredMsgs", gone.size.toLong())
                q.removeAll(gone.toSet())
            }
            return q.any { it.dst == target }
        }

        override fun lookupDone(
            target: Int,
            elapsedMs: Long,
            answerHops: Int,
        ) {
            traceQueued(x, i, target) {
                it.doneAt = sim.now
                it.answerHops = answerHops
            }
            addCert(x, i, target)
            if (now() - elapsedMs >= sim.warmupMs) {
                countF(x, "lookupOk")
                stats[x].lookupLat.add(elapsedMs.toDouble())
                stats[x].lookupHops.add(answerHops)
            }
            releaseQueued(x, i, target)
        }

        override fun count(
            key: String,
            n: Long,
        ) = countF(x, key, n)

        override fun lookupSent(target: Int) = traceQueued(x, i, target) { it.tx.add(sim.now) }

        override fun isConvTag(tag: Long) = fCV[x] && convReg.containsKey(tag)
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
        if (attacker >= 0) sim.at(tc.trafficStartMs) { attackLoop() }
    }

    /** Rate-limit test: the misbehaving phone floods one lookup per second for a NodeId that does not exist. */
    private fun attackLoop() {
        sim.at(sim.now + 1_000) { attackLoop() }
        val target = n + 1 + attackCounter++
        for (x in 0 until k) if (fATK[x]) routing[x][attacker]?.attackLookup(target)
    }

    fun onJoin(i: Int) {
        if (!honest[i]) return
        for (g in groups.indices) {
            if (!groupHold[g]) continue
            val p = params[(0 until k).first { gIdx[it] == g && fH[it] }]
            budgets[g][i] = HoldBudget(p.holdQuotaMsgs, p.holdQuotaBytes, p.holdMaxMsgBytes, budgetCounters[g]) { measuring() }
        }
        for (x in 0 until k) {
            lkQueue[x][i] = null
            routing[x][i] = create(x, i)
        }
    }

    fun onLeave(i: Int) {
        for (x in 0 until k) routing[x][i] = null
    }

    fun onLinkUp(l: Link) {
        l.rq = LongArray(2 * k)
        if (measuring()) linkUpsMeasured++
        for (x in 0 until k) {
            if (fLK[x] && honest[l.a] && honest[l.b]) {
                // Each link exchanges certificates (covered by the link handshake bytes).
                addCert(x, l.a, l.b)
                addCert(x, l.b, l.a)
                releaseQueued(x, l.a, l.b)
                releaseQueued(x, l.b, l.a)
            }
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
        if (isBulk(f.inst, f.packet)) s.bulkTx[f.from] -= f.bytes
        sentKind(f.inst, f.packet)?.let { s.kindSent[it][f.from] -= f.bytes }
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
        val bytes = packet.sizeBytes(sizesOf[x])
        val slot = 2 * x + if (from == l.a) 0 else 1
        val now = sim.now
        val start = maxOf(now, rq[slot])
        var done = start + ceil(bytes * 1000.0 / l.capacityBps).toLong()
        val r = procRng[x]
        val proc = l.oneWayMs + tc.procMinMs + (r.nextDouble() * (tc.procMaxMs - tc.procMinMs)).toLong()
        val counted = measuring()
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
            if (packet is RouteRequest) s.rreqBytes += bytes
            if (packet is RouteReply) s.rrepBytes += bytes
            if (packet is RouteRequest) s.rreqTx++
            if (packet is meshlink.routingsim.routing.LookupRequest) s.lookupReqTx++
            s.bytesSent[from] += bytes
            if (isBulk(x, packet)) s.bulkTx[from] += bytes
            sentKind(x, packet)?.let { s.kindSent[it][from] += bytes }
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
                if (stream) {
                    f.headArrived = true
                    val key = nodeMsg(to, (packet as DataPacket).id)
                    if (key !in tails[x]) tails[x][key] = f.tailAt
                    sim.at(f.tailAt) { l.inflight?.remove(f) }
                } else {
                    l.inflight?.remove(f)
                }
                if (packet is DataPacket && measuring()) stats[x].dataRx++
                if (counted) {
                    if (isBulk(x, packet)) stats[x].bulkRx[to] += bytes else stats[x].bytesRecv[to] += bytes
                }
                routing[x][to]?.onPacket(from, packet)
            }
        }
        return true
    }

    private fun nodeMsg(
        node: Int,
        id: Int,
    ) = (node.toLong() shl 32) or id.toLong()

    /** Stage F2: the [SentKind] of a non-bulk packet (null for bulk). */
    private fun sentKind(
        x: Int,
        p: Packet,
    ): Int? =
        when {
            isBulk(x, p) -> null
            inst[x].protocol.broadcast -> if (p.isIdleControl) SentKind.IDLE else SentKind.BROADCAST
            p is RouteRequest -> SentKind.RREQ
            p is RouteReply -> SentKind.RREP
            p is meshlink.routingsim.routing.LookupRequest -> SentKind.LOOKUP_REQ
            p is meshlink.routingsim.routing.LookupReply -> SentKind.LOOKUP_REP
            p.isIdleControl -> SentKind.IDLE
            p.isControl -> SentKind.OTHER
            else -> SentKind.DATA
        }

    /** A4: bulk bytes of a large message (energy at 0.8 mJ/KiB instead of the per-byte routing cost). */
    private fun isBulk(
        x: Int,
        p: Packet,
    ) = fLG[x] && p is DataPacket && p.payloadBytes > params[x].holdMaxMsgBytes

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
        // A session (and so a first message) per pair of incarnations: a churn rejoin starts a fresh session.
        val first = pairSeen.add(mix64((src.toLong() shl 32) or dst.toLong()) xor mix64(incSig(src, dst) + 0x5e55L))
        val pq = pqPair(src, dst)
        val h = mix64(seed xor 0x1a46eL xor id.toLong() * 0x9E3779B97F4A7C15uL.toLong())
        val large = ((h ushr 1) % 10_000) < LARGE_PER_10K
        val largeBytes = (LARGE_MIN * Math.pow(LARGE_MAX / LARGE_MIN, ((h ushr 24) % 1_000_000) / 1_000_000.0)).toInt()
        msgs.add(
            Msg(
                id, src, dst, sim.now, measured, eligible, first, pq, dist, null, 0, sim.peersInRange(src), inTransition(), classesNow(),
                postRotation(src, dst), large, largeBytes,
            ),
        )
        if (large) lgSig[id] = incSig(src, dst)
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
            val pl = if (fLG[x] && large) largeBytes else fragmentPayload(x, false) ?: payload
            if (fLK[x] && !(fCV[x] && sessionValid(x, src, dst)) && !hasCert(x, src, dst)) {
                // ADR 0018: queue the message and look up the destination's certificate.
                val q = lkQueue[x][src] ?: ArrayList<Queued>().also { lkQueue[x][src] = it }
                q.add(Queued(id, dst, local + inst[x].lifetimeMs, pl))
                lookupMsg[x].add(id)
                if (lkTraceMs != null) lkTrace[x][id] = LkTrace(sim.now, pairState(src, dst))
                r.lookup(dst)
                continue
            }
            val (st, dt) = tagsFor(x, src, dst)
            r.originate(DataPacket(id, st, dt, local + inst[x].lifetimeMs, pl, 0, tc.hopLimit))
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
            if (fCV[x]) sessions[x][pairU(m.src, i)] = incSig(m.src, i)
            if (fLK[x] && m.first) addCert(x, i, m.src)
            if (!m.measured || !m.eligible) return
            val s = stats[x]
            val lat = (sim.now - m.sentAt).toDouble()
            if (fLG[x] && m.large) {
                s.largeLat.add(lat)
                return
            }
            val looked = p.id in lookupMsg[x]
            if (m.first && m.hopDistance in 1..3 && looked) {
                s.firstLookup[if (m.pq) 1 else 0].add(lat)
                val t = lkTrace[x][p.id]
                if (lkTraceMs != null && t != null && lat >= lkTraceMs) {
                    fun r(v: Long) = if (v < 0) "-" else "+${v - t.queuedAt}"
                    println(
                        "LKTRACE ${inst[x].label} msg ${p.id} ${if (m.pq) "PQ" else "C"} ${m.src}->${m.dst} lat ${lat.toLong()} " +
                            "hopDist@send ${m.hopDistance} dataHops ${p.hops} queuedAt ${t.queuedAt} [${t.state}] " +
                            "lookupTx ${t.tx.joinToString(",") { r(it) }} answered ${r(t.doneAt)} ansHops ${t.answerHops} " +
                            "released ${r(t.releasedAt)} [${t.releaseState}] delivered +${sim.now - t.queuedAt} [${pairState(m.src, m.dst)}]",
                    )
                }
            }
            s.latencies.add(lat)
            s.hops[minOf(p.hops, 256)]++
            if (p.hops in 1..5) s.latByHops[p.hops].add(lat)
            if (m.first && m.hopDistance in 1..3 && !looked) {
                if (lkTraceMs != null && lat >= lkTraceMs && inst[x].label.endsWith("GA4D")) {
                    println("CACHEDSLOW msg ${p.id} ${if (m.pq) "PQ" else "C"} ${m.src}->${m.dst} lat ${lat.toLong()} hops ${p.hops} [${pairState(m.src, m.dst)}]")
                }
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
        if (groups.isNotEmpty()) {
            holdSamples++
            for (g in groups.indices) {
                if (!groupHold[g]) continue
                var c = 0
                var sz = 0.0
                var by = 0.0
                for (i in 0 until n) {
                    val b = budgets[g][i] ?: continue
                    if (!sim.active(i)) continue
                    c++
                    sz += b.size
                    by += b.bytes
                    holdMax[g] = maxOf(holdMax[g], b.size.toDouble())
                }
                if (c > 0) {
                    holdSum[g] += sz / c
                    holdBytesSum[g] += by / c
                }
            }
        }
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
        val ms = msgs.filter { it.measured && it.eligible && (it.dst < 0) == bcast && it.srcPeers >= 0 && small(x, it) }
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

    /** A4: small-message criteria exclude large messages in instances that send them large. */
    private fun small(
        x: Int,
        m: Msg,
    ) = !(fLG[x] && m.large)

    /** Stage F per-arm metrics: every row carries them (its group, or itself when it has none). */
    private fun stageFResults(
        x: Int,
        out: LinkedHashMap<String, Double>,
        seconds: Double,
    ) {
        val members = if (gIdx[x] < 0) listOf(x) else (0 until k).filter { gIdx[it] == gIdx[x] }
        val honestIds = (0 until n).filter { honest[it] }
        val nodes = honestIds.filter { it != attacker }
        val capacity = sim.capacityByteSeconds()
        val minutes = seconds / 60.0
        val sent = LongArray(n)
        val bulk = LongArray(n)
        val recv = LongArray(n)
        val bulkRx = LongArray(n)
        val kinds = Array(SentKind.N) { LongArray(n) }
        var idle = 0L
        var ctrl = 0L
        for (y in members) {
            val s = stats[y]
            for (i in 0 until n) {
                sent[i] += s.bytesSent[i]
                bulk[i] += s.bulkTx[i]
                recv[i] += s.bytesRecv[i]
                bulkRx[i] += s.bulkRx[i]
                for (kd in 0 until SentKind.N) kinds[kd][i] += s.kindSent[kd][i]
            }
            idle += s.idleCtrlBytes
            ctrl += s.ctrlBytes
        }
        out["grpLoadMaxBps"] = nodes.maxOfOrNull { sent[it] / seconds } ?: Double.NaN
        out["grpLoadMaxNoBulkBps"] = nodes.maxOfOrNull { (sent[it] - bulk[it]) / seconds } ?: Double.NaN
        out["grpLoadMeanBps"] = nodes.map { sent[it] / seconds }.average()
        out["grpIdleCtrlShare"] = if (capacity == 0.0) 0.0 else idle / capacity
        out["grpCtrlBytesPerNodeS"] = ctrl / (honestIds.size * seconds)
        out["grpIdleBytesPerLinkUp"] = if (linkUpsMeasured == 0L) Double.NaN else idle.toDouble() / linkUpsMeasured
        // Energy per phone (mW above baseline) over the time it was online in the measured window.
        val busiest = nodes.maxByOrNull { sent[it] - bulk[it] } ?: -1
        val mw = ArrayList<Double>()
        val parts = DoubleArray(5)
        var busyMw = Double.NaN
        val byPlat = Array(2) { ArrayList<Double>() }
        val byBg = Array(2) { ArrayList<Double>() }
        var dutySum = 0.0
        for (i in nodes) {
            val b = sim.energyBase(i)
            if (b[0] < 60_000) continue
            val sec = b[0] / 1000.0
            val scan = SCAN_MW * b[1] / 1000.0
            val link = LINK_MW * b[2] / 1000.0
            val topo = MJ_PER_BYTE * b[3]
            val route = MJ_PER_BYTE * ((sent[i] - bulk[i]) + recv[i])
            val bk = BULK_MJ_PER_KIB * (bulk[i] + bulkRx[i]) / 1024.0
            val e = (scan + link + topo + route + bk) / sec
            mw.add(e)
            if (i == busiest) busyMw = e
            parts[0] += scan / sec
            parts[1] += link / sec
            parts[2] += topo / sec
            parts[3] += route / sec
            parts[4] += bk / sec
            byPlat[if (sim.ios(i)) 1 else 0].add(e)
            byBg[if (sim.backgrounded(i)) 1 else 0].add(e)
            dutySum += b[1].toDouble() / b[0]
        }
        val cnt = mw.size.coerceAtLeast(1)
        out["eMeanMw"] = if (mw.isEmpty()) Double.NaN else mw.average()
        out["eMaxMw"] = mw.maxOrNull() ?: Double.NaN
        out["eP95Mw"] = percentile(mw, 0.95)
        val names = listOf("eScanMw", "eLinkMw", "eTopoMw", "eRouteMw", "eBulkMw")
        for (j in names.indices) out[names[j]] = parts[j] / cnt
        out["scanDuty"] = dutySum / cnt
        out["eMeanMwAndroid"] = if (byPlat[0].isEmpty()) Double.NaN else byPlat[0].average()
        out["eMeanMwIos"] = if (byPlat[1].isEmpty()) Double.NaN else byPlat[1].average()
        out["eMeanMwFg"] = if (byBg[0].isEmpty()) Double.NaN else byBg[0].average()
        out["eMeanMwBg"] = if (byBg[1].isEmpty()) Double.NaN else byBg[1].average()
        // Stage F2: the busiest-bytes phone (criterion 3 amended): its mW and what it sent, by kind.
        out["busyMw"] = busyMw
        out["busyIos"] = if (busiest < 0) Double.NaN else if (sim.ios(busiest)) 1.0 else 0.0
        out["busyBulkBps"] = if (busiest < 0) Double.NaN else bulk[busiest] / seconds
        for (kd in 0 until SentKind.N) out["busy_${SentKind.labels[kd]}"] = if (busiest < 0) Double.NaN else kinds[kd][busiest] / seconds
        for (kd in 0 until SentKind.N) out["mean_${SentKind.labels[kd]}"] = nodes.sumOf { kinds[kd][it] } / (nodes.size * seconds)
        // Counters.
        val nodeMin = honestIds.size * minutes
        for (key in stageFCounters) out["c_$key"] = members.sumOf { stats[it].counters[key] ?: 0L }.toDouble()
        out["rreqOrigPerNodeMin"] = (out["c_rreqOrig"] ?: 0.0) / nodeMin
        out["rreqTxPerNodeMin"] = members.sumOf { stats[it].rreqTx } / nodeMin
        out["lookupsPerNodeMin"] = (out["c_lookupEpisodes"] ?: 0.0) / nodeMin
        out["lookupTxPerNodeMin"] = (out["c_lookupTx"] ?: 0.0) / nodeMin
        out["lookupReqTxPerNodeMin"] = members.sumOf { stats[it].lookupReqTx } / nodeMin
        val ep = out["c_lookupEpisodes"] ?: 0.0
        out["lookupSuccess"] = if (ep == 0.0) Double.NaN else (out["c_lookupOk"] ?: 0.0) / ep
        val lat = members.flatMap { stats[it].lookupLat }
        out["lookupP50Ms"] = percentile(lat, 0.5)
        out["lookupP95Ms"] = percentile(lat, 0.95)
        val hops = members.flatMap { stats[it].lookupHops }
        out["lkHopN"] = hops.size.toDouble()
        out["lkHop1"] = hops.count { it <= 1 }.toDouble()
        out["lkHop2"] = hops.count { it <= 2 }.toDouble()
        out["lkHopSum"] = hops.sum().toDouble()
        for (h in 1..6) out["lkHopEq$h"] = hops.count { if (h < 6) it == h else it >= 6 }.toDouble()
        out["lkHopP50"] = percentile(hops.map { it.toDouble() }, 0.5)
        out["lkHopP90"] = percentile(hops.map { it.toDouble() }, 0.9)
        out["convTagsNew"] = if (fCV[x]) convNew.toDouble() else Double.NaN
        val g = gIdx[x]
        if (g >= 0 && groupHold[g]) {
            val hs = holdSamples.coerceAtLeast(1)
            out["holdMean"] = holdSum[g] / hs
            out["holdBytesMean"] = holdBytesSum[g] / hs
            out["holdMax"] = holdMax[g]
            val c = budgetCounters[g]
            out["holdAdmitted"] = c[0].toDouble()
            out["holdRejSize"] = c[1].toDouble()
            out["holdRejShare"] = c[2].toDouble()
            out["holdRejFull"] = c[3].toDouble()
            out["holdEvicted"] = c[4].toDouble()
        } else {
            for (key in listOf("holdMean", "holdBytesMean", "holdMax", "holdAdmitted", "holdRejSize", "holdRejShare", "holdRejFull", "holdEvicted")) {
                out[key] = Double.NaN
            }
        }
        // First messages that needed a lookup (only in lookup arms).
        val s = stats[x]
        out["firstLkP95ClassicalMs"] = percentile(s.firstLookup[0], 0.95)
        out["firstLkP95PqMs"] = percentile(s.firstLookup[1], 0.95)
        out["firstLkNC"] = s.firstLookup[0].size.toDouble()
        out["firstLkNP"] = s.firstLookup[1].size.toDouble()
        out["firstLkOkC"] = s.firstLookup[0].count { it <= 2_000.0 }.toDouble()
        out["firstLkOkP"] = s.firstLookup[1].count { it <= 3_000.0 }.toDouble()
        out["firstNC"] = s.firstClassical.size.toDouble()
        out["firstNP"] = s.firstPq.size.toDouble()
        out["firstOkC"] = s.firstClassical.count { it <= 1_000.0 }.toDouble()
        out["firstOkP"] = s.firstPq.count { it <= 1_500.0 }.toDouble()
        // Large messages (A4): delivery when a route exists (same component at send), by the sender's true class.
        val lg = msgs.filter { it.measured && it.eligible && it.dst >= 0 && it.large && it.srcPeers >= 0 }
        val isLg = fLG[x]
        for (c in 0 until 3) {
            val sel = lg.filter { it.srcTrueClass == c }
            val cl = DensityClass.labels[c]
            out["lgN_$cl"] = if (isLg) sel.size.toDouble() else Double.NaN
            out["lgDel_$cl"] = if (!isLg || sel.isEmpty()) Double.NaN else sel.count { delivered[x][it.id] >= 0 }.toDouble() / sel.size
            // Excluding undelivered messages whose sender or destination left (churn) before the end of the run.
            val stay = sel.filter { delivered[x][it.id] >= 0 || lgSig[it.id] == incSig(it.src, it.dst) }
            out["lgNStay_$cl"] = if (isLg) stay.size.toDouble() else Double.NaN
            out["lgDelStay_$cl"] = if (!isLg || stay.isEmpty()) Double.NaN else stay.count { delivered[x][it.id] >= 0 }.toDouble() / stay.size
        }
        if (isLg && System.getProperty("rsim.lgdebug") != null) {
            val lab = sim.componentLabels()
            for (m in lg.filter { delivered[x][it.id] < 0 }) {
                println("LG-UNDELIVERED id=${m.id} ${m.src}->${m.dst} bytes=${m.largeBytes} sentAt=${m.sentAt} hops=${m.hopDistance} endSameComp=${lab[m.src] == lab[m.dst]} churned=${lgSig[m.id] != incSig(m.src, m.dst)} inc=${sim.incarnation(m.src)},${sim.incarnation(m.dst)}")
            }
        }
        out["lgN"] = if (isLg) lg.size.toDouble() else Double.NaN
        out["lgDel"] = if (!isLg || lg.isEmpty()) Double.NaN else lg.count { delivered[x][it.id] >= 0 }.toDouble() / lg.size
        out["lgP50Ms"] = if (isLg) percentile(s.largeLat, 0.5) else Double.NaN
        out["lgP95Ms"] = if (isLg) percentile(s.largeLat, 0.95) else Double.NaN
    }

    fun results(): List<Pair<InstanceConfig, LinkedHashMap<String, Double>>> {
        val seconds = (sim.durationMs - sim.warmupMs) / 1000.0
        val honestCount = honest.count { it }
        val capacity = sim.capacityByteSeconds()
        return (0 until k).map { x ->
            val s = stats[x]
            val out = LinkedHashMap<String, Double>()
            val bcast = inst[x].protocol.broadcast
            val measuredMsgs = msgs.filter { it.measured && (it.dst < 0) == bcast && small(x, it) }
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
            val loads = (0 until n).filter { honest[it] && it != attacker }.map { s.bytesSent[it] / seconds }
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
                for (i in 0 until n) sw += (routing[x][i] as? Plumtree)?.modeSwitches ?: 0
                out["modeSwitchesPerNodeH"] = sw / (honestCount * seconds / 3600.0)
            }
            densityResults(x, out)
            stageFResults(x, out, seconds)
            if (DEBUG) System.err.println("${inst[x].label} packets ${s.packetCounts}")
            inst[x] to out
        }
    }
}

private val DEBUG = System.getProperty("rsim.debug") != null

private const val CERT_CACHE = 64
private const val CERT_CLASSICAL = 140
private const val CERT_PQ = 1_350
private const val LARGE_PER_10K = 200
private const val LARGE_MIN = 16.0 * 1024
private const val LARGE_MAX = 128.0 * 1024

/** Stage F energy model (above baseline): 55 mW × scan duty, 1 mW per link, 1.25 µJ/B routed/control, 0.8 mJ/KiB bulk. */
private const val SCAN_MW = 55.0
private const val LINK_MW = 1.0
private const val MJ_PER_BYTE = 1.25e-3
private const val BULK_MJ_PER_KIB = 0.8

/** Stage F counters reported per arm (sum over the arm's unicast and broadcast instances). */
private val stageFCounters =
    listOf(
        "rreqOrig", "retag", "tombStored", "tombPurgePath", "tombPurgeCatchUp", "summaryUnicastIds", "summaryTombstones",
        "summaryBroadcastIds", "rreqConv", "prefetch", "convOldUsed", "convOldMiss", "lgRelayDrop", "lgResend", "claimIds", "claimServed", "claimDelivered", "lookupEpisodes", "lookupTx", "lookupOk",
        "lookupAnsSelf", "lookupAnsCache", "lookupReplyAtReq", "lookupExpiredMsgs", "rlDropped", "attackLookups",
    )

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
