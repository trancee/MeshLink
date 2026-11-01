package meshlink.routingsim.sim

import meshlink.routingsim.strategy.Clock
import meshlink.routingsim.strategy.ControlMessage
import meshlink.routingsim.strategy.DiverseViewSelection
import meshlink.routingsim.strategy.LinkDownReason
import meshlink.routingsim.strategy.LinkPort
import meshlink.routingsim.strategy.LinkTable
import meshlink.routingsim.strategy.NeighborSelection
import meshlink.routingsim.strategy.NodeContext
import meshlink.routingsim.strategy.Randomness
import meshlink.routingsim.strategy.ScanResult
import meshlink.routingsim.strategy.SelectionParams
import meshlink.routingsim.strategy.StrongestSignalSelection
import meshlink.routingsim.strategy.SybilSelection
import meshlink.routingsim.strategy.WireSizes
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

class RunResult(
    val scenario: Scenario,
    val strategy: StrategyKind,
    val seed: Int,
    val metrics: LinkedHashMap<String, Double>,
    val instances: List<Pair<InstanceConfig, LinkedHashMap<String, Double>>> = emptyList(),
)

internal class Link(
    val a: Int,
    val b: Int,
    val upAt: Long,
    val capacityBps: Double,
    val oneWayMs: Long,
) {
    var alive = true
    var busyAB = 0L
    var busyBA = 0L

    /** Stage B: per protocol instance and direction, when the link's queue drains. */
    var rq: LongArray? = null

    /** Stage B: routing packets queued or in flight on this link. */
    var inflight: ArrayList<Any>? = null

    fun other(id: Int) = if (id == a) b else a
}

private class Dial(
    val from: Int,
    val to: Int,
) {
    var cancelled = false
    var targetReserved = false
}

/**
 * One run of the link-level topology model, fully determined by (scenario, strategy, seed).
 * World randomness (placement, mobility, roles, churn, iOS background windows) does not depend on the
 * strategy, so N0 and N1+N2 see identical node trajectories for the same scenario and seed.
 */
class Simulation(
    private val scenario: Scenario,
    private val strategyKind: StrategyKind,
    private val seed: Int,
    private val cal: Calibration,
    private val params: SelectionParams,
    private val extent: Double,
    private val sizes: WireSizes = WireSizes(),
    private val timeSeries: ((Long, Map<String, Double>) -> Unit)? = null,
    routingSetup: RoutingSetup? = null,
    densitySetup: DensitySetup? = null,
) {
    private val n = scenario.nodes
    private val worldRng = Random(seedOf("world", scenario.key, seed))
    private val churnRng = Random(seedOf("churn", scenario.key, seed))
    private val protoRng = Random(seedOf("proto", scenario.key, strategyKind, seed))
    private val queue = EventQueue()
    private var now = 0L

    private val sybil = BooleanArray(n)
    private val ios = BooleanArray(n)
    private val background = BooleanArray(n)
    private val radio: Radio
    private val mobility: Mobility
    private val sybilIds: IntArray
    private val honestCount: Int

    private val online = BooleanArray(n)
    private val awake = BooleanArray(n) { true }
    private val incarnation = IntArray(n)
    private val budget = IntArray(n)
    private val links = Array(n) { ArrayList<Link>(8) }
    private val outDials = Array(n) { ArrayList<Dial>(4) }
    private val inDials = Array(n) { ArrayList<Dial>(4) }
    private val strategies = arrayOfNulls<NeighborSelection>(n)
    private val lastFailure = HashMap<Long, Long>()

    // Metrics (measured after warm-up).
    private var samples = 0
    private val sums = LinkedHashMap<String, Double>()
    private var lccMin = 1.0
    private var dials = 0L
    private var dialFails = 0L
    private var linkUps = 0L
    private var controlBytes = 0L
    private var controlBytesHonestLinks = 0L
    private var capacityByteSeconds = 0.0
    private val lifetimes = ArrayList<Double>()
    private val joinAt = LongArray(n) { -1 }
    private val joinDelays = ArrayList<Double>()
    private var diameterSamples = 0
    private var diameterSum = 0.0
    private var pathLenSum = 0.0
    private var tRadioMerge = -1L
    private var tFirstCross = -1L
    private var tLccRecover = -1L

    init {
        val sybilCount = (n * scenario.sybilShare).roundToInt()
        val order = (0 until n).shuffled(worldRng)
        for (k in 0 until sybilCount) sybil[order[k]] = true
        sybilIds = (0 until n).filter { sybil[it] }.toIntArray()
        honestCount = n - sybilCount
        for (i in 0 until n) {
            budget[i] = if (sybil[i]) cal.sybilBudget else scenario.degree
            if (scenario.platform == PlatformMix.HALF_IOS && !sybil[i]) {
                ios[i] = worldRng.nextDouble() < 0.5
                background[i] = ios[i] && worldRng.nextDouble() < cal.iosBackgroundShare
            }
        }
        radio = Radio(cal, n, seedOf("shadow", scenario.key, seed), sybil)
        mobility = createMobility(scenario.mobility, radio, cal, extent, Random(seedOf("mobility", scenario.key, seed)))
        mobility.place()
        radio.rebuild()
    }

    private val access =
        object : SimAccess {
            override val n get() = this@Simulation.n
            override val now get() = this@Simulation.now
            override val warmupMs get() = scenario.warmupMs
            override val durationMs get() = scenario.durationMs

            override fun active(i: Int) = this@Simulation.active(i)

            override fun online(i: Int) = this@Simulation.online[i]

            override fun incarnation(i: Int) = this@Simulation.incarnation[i]

            override fun link(
                a: Int,
                b: Int,
            ) = linkBetween(a, b)

            override fun peers(i: Int) = IntArray(links[i].size) { links[i][it].other(i) }

            override fun at(
                t: Long,
                block: () -> Unit,
            ) = this@Simulation.at(t, block)

            override fun componentLabels(): IntArray {
                components()
                return comp.copyOf()
            }

            override fun distancesFrom(s: Int): IntArray {
                bfs(s)
                return dist
            }

            override fun x(i: Int) = radio.xs[i]

            override fun y(i: Int) = radio.ys[i]

            override fun capacityByteSeconds() = capacityByteSeconds

            override fun stable(i: Int) = !background[i]

            override fun densityClass(i: Int) = this@Simulation.densityClass(i)

            override val transitionWindowMs get() = scenario.transitionWindowMs

            override fun peersInRange(i: Int): Int {
                var c = 0
                for (k in 0 until radio.nbrCount[i]) {
                    val j = radio.nbr[i][k]
                    if (active(j) && !sybil[j]) c++
                }
                return c
            }
        }

    private val density: DensityTracker? =
        densitySetup?.let { ds ->
            val rng = Random(seedOf("advert", scenario.key, seed))
            DensityTracker(ds, n, LongArray(n) { (rng.nextDouble() * ds.advertRotationMs).toLong() })
        }
    private val classSettings = densitySetup?.settings
    private val slowHear = densitySetup?.slowAdvertisingHear ?: cal.scanHearProbability
    private val tickCount = IntArray(n)

    private fun densityClass(i: Int) = density?.classOf(i) ?: 1

    private fun applyClass(i: Int) {
        val st = classSettings ?: return
        if (!sybil[i]) budget[i] = st.degree[densityClass(i)]
    }

    private val layer: RoutingLayer? =
        routingSetup?.let { RoutingLayer(access, it, seedOf("routing", scenario.key, seed), BooleanArray(n) { i -> !sybil[i] }) }

    private inner class NodePort(
        val id: Int,
        rngSeed: Long,
    ) : Clock,
        Randomness,
        LinkTable,
        LinkPort {
        private val rng = Random(rngSeed)

        override fun nowMillis() = now

        override fun nextDouble() = rng.nextDouble()

        override fun nextInt(bound: Int) = rng.nextInt(bound)

        override val degreeBudget get() = budget[id]

        override fun linkCount() = links[id].size

        override fun pendingCount() = outDials[id].size + inDials[id].size

        override fun linkedPeers() = IntArray(links[id].size) { links[id][it].other(id) }

        override fun isLinkedOrPending(peer: Int): Boolean =
            linkBetween(id, peer) != null || outDials[id].any { it.to == peer } || inDials[id].any { it.from == peer }

        override fun linkRssi(peer: Int) = radio.meanRssi(id, peer)

        override fun linkUpAt(peer: Int) = linkBetween(id, peer)?.upAt ?: now

        // Stage A carries no application data, so a link's last data activity is its set-up time.
        override fun lastActivityAt(peer: Int) = linkUpAt(peer)

        override fun bridgeGapMs(): Long? = classSettings?.bridgeGapMs?.get(densityClass(id))

        override fun dial(peer: Int): Boolean = startDial(id, peer)

        override fun disconnect(peer: Int) {
            linkBetween(id, peer)?.let { teardown(it, id) }
        }

        override fun send(
            peer: Int,
            message: ControlMessage,
        ) = sendControl(id, peer, message)
    }

    private fun newStrategy(id: Int): NeighborSelection {
        val port = NodePort(id, seedOf("strategy", scenario.key, strategyKind, seed, id, incarnation[id]))
        val ctx = NodeContext(id, port, port, port, port)
        return when {
            sybil[id] -> SybilSelection(ctx, sybilIds)
            strategyKind == StrategyKind.N0 -> StrongestSignalSelection(ctx, params)
            strategyKind == StrategyKind.N1N2_BRIDGE -> DiverseViewSelection(ctx, params, bridge = true)
            else -> DiverseViewSelection(ctx, params)
        }
    }

    private fun active(i: Int) = online[i] && awake[i]

    private fun used(i: Int) = links[i].size + outDials[i].size + inDials[i].size

    private fun measuring() = now >= scenario.warmupMs

    private fun linkBetween(
        a: Int,
        b: Int,
    ): Link? {
        val l = links[a]
        for (k in l.indices) if (l[k].other(a) == b) return l[k]
        return null
    }

    private fun at(
        t: Long,
        block: () -> Unit,
    ) {
        queue.push(
            t,
            object : Event() {
                override fun fire() = block()
            },
        )
    }

    private fun uniform(
        lo: Long,
        hi: Long,
    ) = lo + (protoRng.nextDouble() * (hi - lo)).toLong()

    // ---- dials and links ----

    private fun startDial(
        from: Int,
        to: Int,
    ): Boolean {
        if (!active(from) || from == to || to !in 0 until n) return false
        if (used(from) >= budget[from]) return false
        if (linkBetween(from, to) != null || outDials[from].any { it.to == to }) return false
        val d = Dial(from, to)
        outDials[from].add(d)
        if (measuring() && !sybil[from]) dials++
        at(now + uniform(cal.connectMinMs, cal.connectMaxMs)) { connectDone(d) }
        return true
    }

    private fun pairKey(
        a: Int,
        b: Int,
    ) = (a.toLong() shl 32) or b.toLong()

    private fun connectDone(d: Dial) {
        if (d.cancelled) return
        val to = d.to
        val retry = (lastFailure[pairKey(d.from, to)] ?: Long.MIN_VALUE / 2) >= now - cal.retryWindowMs
        val base = if (retry) cal.retryFailure else cal.firstAttemptFailure
        val pFail = minOf(cal.failureCap, base + radio.nbrCount[d.from] * cal.contentionFailurePerPeer)
        val reachable =
            active(to) && used(to) < budget[to] && linkBetween(d.from, to) == null &&
                radio.meanRssi(d.from, to) >= cal.sensitivityDbm
        if (!reachable || protoRng.nextDouble() < pFail) {
            failDial(d)
            return
        }
        d.targetReserved = true
        inDials[to].add(d)
        at(now + uniform(cal.handshakeMinMs, cal.handshakeMaxMs) + uniform(cal.rttMinMs, cal.rttMaxMs)) {
            handshakeDone(d)
        }
    }

    private fun releaseDial(d: Dial) {
        outDials[d.from].remove(d)
        if (d.targetReserved) inDials[d.to].remove(d)
    }

    private fun failDial(d: Dial) {
        releaseDial(d)
        lastFailure[pairKey(d.from, d.to)] = now
        if (measuring() && !sybil[d.from]) dialFails++
        if (active(d.from)) strategies[d.from]!!.onDialFailed(d.to)
    }

    private fun handshakeDone(d: Dial) {
        if (d.cancelled) return
        if (!active(d.to) || linkBetween(d.from, d.to) != null) {
            failDial(d)
            return
        }
        releaseDial(d)
        lastFailure.remove(pairKey(d.from, d.to))
        createLink(d.from, d.to)
    }

    private fun createLink(
        a: Int,
        b: Int,
    ) {
        val peers = (radio.nbrCount[a] + radio.nbrCount[b]) / 2.0
        val capacity =
            (cal.throughputMinBps + protoRng.nextDouble() * (cal.throughputMaxBps - cal.throughputMinBps)) /
                (1.0 + peers / cal.contentionThroughputPeers)
        val link = Link(a, b, now, capacity, uniform(cal.rttMinMs, cal.rttMaxMs) / 2)
        links[a].add(link)
        links[b].add(link)
        val dropMean = scenario.dropMeanS?.let { it * 1000.0 } ?: cal.linkDropMeanMs
        val u = protoRng.nextDouble()
        if (dropMean > 0) at(now + (-ln(1.0 - u) * dropMean).toLong()) { teardown(link, -1) }
        if (measuring()) {
            if (!sybil[a]) linkUps++
            if (!sybil[b]) linkUps++
        }
        if (!sybil[a] && !sybil[b]) {
            noteFirstLink(a)
            noteFirstLink(b)
            if (tRadioMerge >= 0 && tFirstCross < 0 && mobility.groupOf(a) != mobility.groupOf(b)) tFirstCross = now
        }
        strategies[a]!!.onLinkUp(b, inbound = false)
        if (link.alive) strategies[b]!!.onLinkUp(a, inbound = true)
        if (link.alive) layer?.onLinkUp(link)
    }

    private fun noteFirstLink(i: Int) {
        if (joinAt[i] >= 0) {
            joinDelays.add((now - joinAt[i]).toDouble())
            joinAt[i] = -1
        }
    }

    /** [initiator] is the closing node, or -1 when the link was lost. */
    private fun teardown(
        link: Link,
        initiator: Int,
    ) {
        if (!link.alive) return
        link.alive = false
        links[link.a].remove(link)
        links[link.b].remove(link)
        if (measuring() && link.upAt >= scenario.warmupMs && !sybil[link.a] && !sybil[link.b]) {
            lifetimes.add((now - link.upAt).toDouble())
        }
        for (e in intArrayOf(link.a, link.b)) {
            val reason =
                when (initiator) {
                    -1 -> LinkDownReason.LOST
                    e -> LinkDownReason.LOCAL_CLOSE
                    else -> LinkDownReason.REMOTE_CLOSE
                }
            strategies[e]?.onLinkDown(link.other(e), reason)
        }
        if (link.rq != null) layer?.onLinkDown(link)
    }

    private fun sendControl(
        from: Int,
        to: Int,
        message: ControlMessage,
    ) {
        val link = linkBetween(from, to) ?: return
        val bytes = message.sizeBytes(sizes)
        if (measuring() && !sybil[from]) {
            controlBytes += bytes
            if (!sybil[to]) controlBytesHonestLinks += bytes
        }
        val start = maxOf(now, if (from == link.a) link.busyAB else link.busyBA)
        val done = start + (bytes * 1000.0 / link.capacityBps).toLong()
        if (from == link.a) link.busyAB = done else link.busyBA = done
        at(done + link.oneWayMs) {
            if (link.alive) strategies[to]?.onMessage(from, message)
        }
    }

    private fun dropEverything(i: Int) {
        for (link in links[i].toTypedArray()) teardown(link, -1)
        for (d in outDials[i].toTypedArray()) {
            d.cancelled = true
            releaseDial(d)
        }
        for (d in inDials[i].toTypedArray()) {
            d.cancelled = true
            releaseDial(d)
            if (active(d.from)) strategies[d.from]!!.onDialFailed(i)
        }
    }

    // ---- node lifecycle ----

    private fun join(i: Int) {
        online[i] = true
        incarnation[i]++
        density?.reset(i)
        applyClass(i)
        strategies[i] = newStrategy(i)
        layer?.onJoin(i)
        // Joins still unconnected when the node leaves or the run ends count with their elapsed time (a lower bound).
        if (measuring() && !sybil[i] && now <= scenario.durationMs - 120_000 && awake[i]) {
            var peers = 0
            for (k in 0 until radio.nbrCount[i]) {
                val j = radio.nbr[i][k]
                if (active(j) && !sybil[j]) peers++
            }
            if (peers > 0) joinAt[i] = now
        }
        if (!sybil[i]) {
            val up = (-ln(1.0 - churnRng.nextDouble()) * cal.churnMeanUpMs).toLong()
            at(now + up) { leave(i) }
        }
        at(now + uniform(0, 200)) { tick(i, incarnation[i]) }
    }

    private fun leave(i: Int) {
        if (joinAt[i] >= 0) {
            joinDelays.add((now - joinAt[i]).toDouble())
            joinAt[i] = -1
        }
        online[i] = false
        dropEverything(i)
        layer?.onLeave(i)
        at(now + cal.churnOffMs) { join(i) }
    }

    private fun setAwake(
        i: Int,
        value: Boolean,
    ) {
        awake[i] = value
        if (!value) dropEverything(i)
        if (value && online[i]) at(now) { tick(i, incarnation[i], recurring = false) }
    }

    private fun tick(
        i: Int,
        inc: Int,
        recurring: Boolean = true,
    ) {
        if (inc != incarnation[i] || !online[i]) return
        if (recurring) at(now + cal.tickMs) { tick(i, inc) }
        if (!awake[i]) return
        val s = strategies[i]!!
        val st = classSettings
        val c = densityClass(i)
        val scanNow = st == null || tickCount[i]++ % st.scanEvery[c] == 0
        if (scanNow) {
            val results = ArrayList<ScanResult>(radio.nbrCount[i])
            for (k in 0 until radio.nbrCount[i]) {
                val j = radio.nbr[i][k]
                if (!active(j) || !strategies[j]!!.advertising()) continue
                val hear = if (st == null || st.fastAdvertising[densityClass(j)]) cal.scanHearProbability else slowHear
                if (protoRng.nextDouble() >= hear) continue
                results.add(ScanResult(j, radio.nbrRssi[i][k] + gaussian() * cal.scanNoiseSigmaDb))
            }
            s.onScan(results)
            val dt = density
            if (dt != null && !sybil[i]) {
                val peers = IntArray(links[i].size) { links[i][it].other(i) }
                if (dt.onScan(i, now, results, peers, measuring())) applyClass(i)
            }
        }
        density?.let { dt ->
            if (!sybil[i]) {
                val power = if (st == null) 1.0 else 0.6 * (if (scanNow) 1.0 else 0.0) + 0.4 * (if (st.fastAdvertising[c]) 1.0 else 0.4)
                dt.sample(i, now, access.peersInRange(i), measuring(), power)
            }
        }
        s.onTick()
    }

    private fun gaussian(): Double {
        var sum = 0.0
        repeat(4) { sum += protoRng.nextDouble() }
        return (sum - 2.0) * sqrt(3.0)
    }

    private fun mobilityTick() {
        at(now + cal.mobilityTickMs) { mobilityTick() }
        if (!mobility.moving) return
        mobility.advance(now, cal.mobilityTickMs)
        radio.rebuild()
        val threshold = cal.sensitivityDbm - cal.dropHysteresisDb
        val doomed = ArrayList<Link>()
        for (i in 0 until n) for (l in links[i]) if (l.a == i && radio.meanRssi(l.a, l.b) < threshold) doomed.add(l)
        for (l in doomed) teardown(l, -1)
        if (scenario.mobility == MobilityKind.PARTITION_MERGE && now >= cal.partitionReturnAtMs && tRadioMerge < 0) {
            if (radioCrossEdge()) {
                tRadioMerge = now
                if (linkCrossEdge()) tFirstCross = now
            }
        }
    }

    private fun radioCrossEdge(): Boolean {
        for (i in 0 until n) {
            if (!active(i) || sybil[i]) continue
            for (k in 0 until radio.nbrCount[i]) {
                val j = radio.nbr[i][k]
                if (active(j) && !sybil[j] && mobility.groupOf(i) != mobility.groupOf(j)) return true
            }
        }
        return false
    }

    private fun linkCrossEdge(): Boolean {
        for (i in 0 until n) {
            for (l in links[i]) {
                if (!sybil[l.a] && !sybil[l.b] && mobility.groupOf(l.a) != mobility.groupOf(l.b)) return true
            }
        }
        return false
    }

    // ---- metrics ----

    private fun add(
        key: String,
        v: Double,
    ) {
        sums[key] = (sums[key] ?: 0.0) + v
    }

    private val comp = IntArray(n)
    private val bfsQueue = IntArray(n)
    private val dist = IntArray(n)

    private fun honestNeighbor(
        i: Int,
        l: Link,
    ): Int {
        val j = l.other(i)
        return if (sybil[j] || !active(j)) -1 else j
    }

    /** Labels honest active components; returns (largest component label, its size, component count). */
    private fun components(): Triple<Int, Int, Int> {
        comp.fill(-1)
        var count = 0
        var best = -1
        var bestSize = 0
        for (s in 0 until n) {
            if (comp[s] != -1 || sybil[s] || !active(s)) continue
            var head = 0
            var tail = 0
            bfsQueue[tail++] = s
            comp[s] = count
            while (head < tail) {
                val u = bfsQueue[head++]
                for (l in links[u]) {
                    val v = honestNeighbor(u, l)
                    if (v >= 0 && comp[v] == -1) {
                        comp[v] = count
                        bfsQueue[tail++] = v
                    }
                }
            }
            if (tail > bestSize) {
                bestSize = tail
                best = count
            }
            count++
        }
        return Triple(best, bestSize, count)
    }

    private fun radioLargest(activeCount: Int): Int {
        val parent = IntArray(n) { it }

        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) {
                parent[r] = parent[parent[r]]
                r = parent[r]
            }
            return r
        }
        for (i in 0 until n) {
            if (!active(i) || sybil[i]) continue
            for (k in 0 until radio.nbrCount[i]) {
                val j = radio.nbr[i][k]
                if (j > i && active(j) && !sybil[j]) {
                    val a = find(i)
                    val b = find(j)
                    if (a != b) parent[a] = b
                }
            }
        }
        val size = IntArray(n)
        var best = 0
        for (i in 0 until n) {
            if (!active(i) || sybil[i]) continue
            val r = find(i)
            size[r]++
            if (size[r] > best) best = size[r]
        }
        return if (activeCount == 0) 0 else best
    }

    private fun sample() {
        at(now + SAMPLE_MS) { sample() }
        var activeCount = 0
        var isolated = 0
        var slotSum = 0.0
        var pendingSum = 0.0
        var inRange = 0.0
        var sybilLinks = 0
        var allLinks = 0
        var tableSybil = 0
        var tableAll = 0
        for (i in 0 until n) {
            if (sybil[i] || !active(i)) continue
            activeCount++
            var honestLinks = 0
            for (l in links[i]) {
                if (honestNeighbor(i, l) >= 0) honestLinks++
                if (sybil[l.other(i)]) sybilLinks++
                allLinks++
            }
            if (honestLinks == 0) isolated++
            slotSum += links[i].size.toDouble() / budget[i]
            pendingSum += (outDials[i].size + inDials[i].size).toDouble() / budget[i]
            for (k in 0 until radio.nbrCount[i]) {
                val j = radio.nbr[i][k]
                if (active(j) && !sybil[j]) inRange++
            }
            if (sybilIds.isNotEmpty()) {
                for (p in strategies[i]!!.peerTable()) {
                    tableAll++
                    if (sybil[p]) tableSybil++
                }
            }
        }
        if (activeCount == 0) return
        val (_, largest, count) = components()
        val lcc = largest.toDouble() / activeCount
        val radioLcc = radioLargest(activeCount).toDouble() / activeCount
        if (scenario.mobility == MobilityKind.PARTITION_MERGE && tRadioMerge >= 0 && tLccRecover < 0 &&
            lcc >= 0.9 * radioLcc
        ) {
            tLccRecover = now
        }
        var honestLinkCount = 0
        for (i in 0 until n) for (l in links[i]) if (l.a == i && !sybil[l.a] && !sybil[l.b]) {
            honestLinkCount++
            if (measuring()) capacityByteSeconds += l.capacityBps * SAMPLE_MS / 1000.0
        }
        val row =
            linkedMapOf(
                "lcc" to lcc,
                "radioLcc" to radioLcc,
                "components" to count.toDouble(),
                "isolated" to isolated.toDouble() / activeCount,
                "slotOccupancy" to slotSum / activeCount,
                "pendingOccupancy" to pendingSum / activeCount,
                "peersInRange" to inRange / activeCount,
                "activeShare" to activeCount.toDouble() / honestCount,
                "sybilSlotShare" to if (allLinks == 0) 0.0 else sybilLinks.toDouble() / allLinks,
                "sybilPeerTableShare" to if (tableAll == 0) 0.0 else tableSybil.toDouble() / tableAll,
                "honestLinks" to honestLinkCount.toDouble(),
            )
        timeSeries?.invoke(now, row)
        if (!measuring()) return
        samples++
        for ((k, v) in row) add(k, v)
        lccMin = minOf(lccMin, lcc)
    }

    private fun diameterSample() {
        at(now + DIAMETER_MS) { diameterSample() }
        if (!measuring()) return
        val (label, size, _) = components()
        if (size < 2) return
        val members = IntArray(size)
        var m = 0
        for (i in 0 until n) if (comp[i] == label) members[m++] = i
        val sources =
            if (size <= EXACT_DIAMETER_MAX) {
                members
            } else {
                IntArray(DIAMETER_SOURCES) { members[protoRng.nextInt(size)] }
            }
        var diameter = 0
        var pathSum = 0.0
        var pathCount = 0L
        var next = -1
        for ((idx, s0) in sources.withIndex()) {
            // For sampled diameters, every fourth source is the farthest node of the previous sweep.
            val s = if (size > EXACT_DIAMETER_MAX && idx % 4 == 1 && next >= 0) next else s0
            val (ecc, far, sum) = bfs(s)
            diameter = maxOf(diameter, ecc)
            next = far
            pathSum += sum
            pathCount += size - 1
        }
        diameterSamples++
        diameterSum += diameter
        pathLenSum += pathSum / pathCount
    }

    private fun bfs(s: Int): Triple<Int, Int, Double> {
        dist.fill(-1)
        var head = 0
        var tail = 0
        bfsQueue[tail++] = s
        dist[s] = 0
        var far = s
        var sum = 0.0
        while (head < tail) {
            val u = bfsQueue[head++]
            if (dist[u] > dist[far]) far = u
            sum += dist[u]
            for (l in links[u]) {
                val v = honestNeighbor(u, l)
                if (v >= 0 && dist[v] == -1) {
                    dist[v] = dist[u] + 1
                    bfsQueue[tail++] = v
                }
            }
        }
        return Triple(dist[far], far, sum)
    }

    // ---- run ----

    fun run(): RunResult {
        for (i in 0 until n) {
            val early = (worldRng.nextDouble() * 10_000).toLong()
            at(mobility.lateJoinMs(i) ?: early) { join(i) }
            if (background[i]) {
                val phase = (worldRng.nextDouble() * cal.iosCycleMs).toLong()
                awake[i] = false
                scheduleWake(i, phase)
            }
        }
        at(cal.mobilityTickMs) { mobilityTick() }
        at(SAMPLE_MS) { sample() }
        at(scenario.warmupMs) { diameterSample() }
        layer?.start()
        while (queue.size > 0 && queue.peekTime() <= scenario.durationMs) {
            val e = queue.pop()
            now = e.time
            e.fire()
        }
        now = scenario.durationMs
        val metrics = collect()
        return RunResult(scenario, strategyKind, seed, metrics, layer?.results() ?: emptyList())
    }

    private fun scheduleWake(
        i: Int,
        t: Long,
    ) {
        at(t) {
            setAwake(i, true)
            at(now + cal.iosWakeMs) { setAwake(i, false) }
            scheduleWake(i, now + cal.iosCycleMs)
        }
    }

    private fun collect(): LinkedHashMap<String, Double> {
        for (i in 0 until n) {
            if (joinAt[i] >= 0) {
                joinDelays.add((now - joinAt[i]).toDouble())
                joinAt[i] = -1
            }
        }
        val minutes = (scenario.durationMs - scenario.warmupMs) / 60_000.0
        val out = LinkedHashMap<String, Double>()
        val s = samples.coerceAtLeast(1).toDouble()
        for (k in listOf("peersInRange", "lcc", "radioLcc", "components", "isolated", "slotOccupancy",
            "pendingOccupancy", "activeShare", "honestLinks", "sybilSlotShare", "sybilPeerTableShare")) {
            out[k] = (sums[k] ?: Double.NaN) / s
        }
        out["lccMin"] = lccMin
        out["diameter"] = if (diameterSamples == 0) Double.NaN else diameterSum / diameterSamples
        out["meanPathLen"] = if (diameterSamples == 0) Double.NaN else pathLenSum / diameterSamples
        out["dialsPerNodeMin"] = dials / (honestCount * minutes)
        out["dialFailureRate"] = if (dials == 0L) Double.NaN else dialFails.toDouble() / dials
        out["linkUpsPerNodeMin"] = linkUps / (honestCount * minutes)
        out["linkLifetimeMedianS"] = percentile(lifetimes, 0.5) / 1000
        out["linkLifetimeP90S"] = percentile(lifetimes, 0.9) / 1000
        out["controlBytesPerNodeS"] = controlBytes / (honestCount * minutes * 60)
        out["controlShareOfCapacity"] =
            if (capacityByteSeconds == 0.0) 0.0 else controlBytesHonestLinks / capacityByteSeconds
        out["joins"] = joinDelays.size.toDouble()
        out["joinConnected60s"] =
            if (joinDelays.isEmpty()) Double.NaN else joinDelays.count { it <= 60_000 }.toDouble() / joinDelays.size
        out["joinMedianS"] = percentile(joinDelays, 0.5) / 1000
        out["joinP95S"] = percentile(joinDelays, 0.95) / 1000
        if (scenario.mobility == MobilityKind.PARTITION_MERGE) {
            out["mergeFirstLinkS"] =
                if (tRadioMerge < 0) Double.NaN else if (tFirstCross < 0) CENSORED_MERGE_S else (tFirstCross - tRadioMerge) / 1000.0
            out["mergeLccRecoverS"] =
                if (tRadioMerge < 0) Double.NaN else if (tLccRecover < 0) CENSORED_MERGE_S else (tLccRecover - tRadioMerge) / 1000.0
        } else {
            out["mergeFirstLinkS"] = Double.NaN
            out["mergeLccRecoverS"] = Double.NaN
        }
        density?.results(out, cal.tickMs)
        out["events"] = queue.processed.toDouble()
        return out
    }

    companion object {
        const val SAMPLE_MS = 5_000L
        const val DIAMETER_MS = 30_000L
        const val EXACT_DIAMETER_MAX = 300
        const val DIAMETER_SOURCES = 32

        const val CENSORED_MERGE_S = 420.0
    }
}

fun percentile(
    values: List<Double>,
    q: Double,
): Double {
    if (values.isEmpty()) return Double.NaN
    val sorted = values.sorted()
    val idx = (q * (sorted.size - 1)).roundToInt().coerceIn(0, sorted.size - 1)
    return sorted[idx]
}
