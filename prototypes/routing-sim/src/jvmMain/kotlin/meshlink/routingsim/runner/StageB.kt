package meshlink.routingsim.runner

import meshlink.routingsim.sim.Calibration
import meshlink.routingsim.sim.Density
import meshlink.routingsim.sim.InstanceConfig
import meshlink.routingsim.sim.MobilityKind
import meshlink.routingsim.sim.PlatformMix
import meshlink.routingsim.sim.Protocol
import meshlink.routingsim.sim.RoutingSetup
import meshlink.routingsim.sim.RunResult
import meshlink.routingsim.sim.Scenario
import meshlink.routingsim.sim.Simulation
import meshlink.routingsim.sim.StrategyKind
import meshlink.routingsim.sim.TrafficConfig
import meshlink.routingsim.strategy.SelectionParams
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Instance sets multiplexed over one topology run. */
enum class InstanceSet(
    val label: String,
) {
    /** Main grid: all protocols at the default lifetime rules, unicast with and without tag rotation. */
    MAIN("main"),

    /** MAIN without U3 (U3 dominates the simulator's cost at N = 1000; see README "Stage B pruning"). */
    MAIN_NO_U3("main-no-u3"),

    /** Lifetime × minimum hold × clock skew sweep (U1, U4 full; U3 reduced). */
    SWEEP("sweep"),

    /** Topology only (bridge comparison). */
    NONE("none"),
}

fun instancesFor(set: InstanceSet): List<InstanceConfig> =
    when (set) {
        InstanceSet.MAIN ->
            listOf(Protocol.U1, Protocol.U2, Protocol.U3, Protocol.U4).map { InstanceConfig(it) } +
                listOf(Protocol.U2, Protocol.U3, Protocol.U4).map { InstanceConfig(it, rotation = true) } +
                listOf(Protocol.B1, Protocol.B2, Protocol.B3, Protocol.GS).map { InstanceConfig(it) }
        InstanceSet.MAIN_NO_U3 -> instancesFor(InstanceSet.MAIN).filter { it.protocol != Protocol.U3 }
        InstanceSet.SWEEP -> {
            val out = ArrayList<InstanceConfig>()
            for (p in listOf(Protocol.U1, Protocol.U4)) {
                for (life in sweepLifetimes) {
                    for (hold in sweepHolds) {
                        for (skew in listOf(0L, 30_000L)) out.add(InstanceConfig(p, life, hold, skew, rotation = true))
                    }
                }
            }
            for (life in listOf(30_000L, 300_000L)) {
                for (hold in sweepHolds) out.add(InstanceConfig(Protocol.U3, life, hold, 30_000L, rotation = true))
            }
            out
        }
        InstanceSet.NONE -> emptyList()
    }

val sweepLifetimes = listOf(30_000L, 300_000L, 3_600_000L, 86_400_000L)
val sweepHolds = listOf(0L, 10_000L, 60_000L)

data class BSpec(
    val scenario: Scenario,
    val strategy: StrategyKind,
    val seed: Int,
    val set: InstanceSet,
) {
    val id get() = "${scenario.key}|${strategy.label}|$seed|${set.label}"
}

class StageBRunner(
    private val traffic: TrafficConfig = TrafficConfig(),
    /** Probe only: run just these protocol labels from the set. */
    private val only: Set<String>? = null,
) {
    private val base = Runner()
    private val cal = Calibration()

    fun runOne(spec: BSpec): Pair<RunResult, Long> {
        val t0 = System.nanoTime()
        val list = instancesFor(spec.set).filter { only == null || it.protocol.label in only }
        val setup = if (spec.set == InstanceSet.NONE) null else RoutingSetup(list, traffic)
        val r =
            Simulation(
                spec.scenario, spec.strategy, spec.seed, cal, SelectionParams(), base.extentFor(spec.scenario),
                routingSetup = setup,
            ).run()
        return r to (System.nanoTime() - t0) / 1_000_000
    }

    fun runAll(
        specs: List<BSpec>,
        rawFile: File,
        threads: Int,
    ) {
        val done = StageBCsv.completedIds(rawFile)
        val todo = specs.filter { it.id !in done }.sortedByDescending { it.scenario.nodes * (if (it.set == InstanceSet.NONE) 1 else 10) }
        println("runs: ${specs.size} total, ${done.size} already in ${rawFile.name}, ${todo.size} to do, $threads threads")
        // Heap budget: an N = 1000 run with routing peaks near 1 GB, so at most two run at once (-Xmx3g).
        val queue = ArrayDeque(todo)
        val lock = Object()
        var bigRunning = 0
        val finished = AtomicInteger()
        val start = System.nanoTime()

        fun isBig(s: BSpec) = s.scenario.nodes >= 1000 && s.set != InstanceSet.NONE

        fun next(): BSpec? =
            synchronized(lock) {
                while (true) {
                    if (queue.isEmpty()) return null
                    val pick = queue.firstOrNull { !isBig(it) || bigRunning < 2 }
                    if (pick != null) {
                        queue.remove(pick)
                        if (isBig(pick)) bigRunning++
                        return pick
                    }
                    lock.wait()
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
        val workers =
            List(threads) {
                Thread {
                    while (true) {
                        val spec = next() ?: break
                        try {
                            val (result, wall) = runOne(spec)
                            StageBCsv.append(rawFile, spec, result, wall)
                            val k = finished.incrementAndGet()
                            if (k % 20 == 0 || k == todo.size) {
                                val el = (System.nanoTime() - start) / 1e9
                                println("  $k/${todo.size} done, %.0f s elapsed, eta %.0f s".format(el, el / k * (todo.size - k)))
                            }
                        } catch (e: Throwable) {
                            System.err.println("FAILED ${spec.id}: $e")
                            e.printStackTrace()
                        } finally {
                            if (isBig(spec)) {
                                synchronized(lock) {
                                    bigRunning--
                                    lock.notifyAll()
                                }
                            }
                        }
                    }
                }.also { it.start() }
            }
        workers.forEach { it.join() }
    }
}

/** One row per (run, protocol instance); topology-only runs write one row with protocol "none". */
object StageBCsv {
    private val keyColumns =
        listOf(
            "runId", "set", "density", "nodes", "mobility", "platform", "degree", "strategy", "seed",
            "protocol", "lifetimeS", "holdS", "skewS", "rotation", "instance",
        )
    private val topoColumns =
        listOf(
            "lcc", "components", "diameter", "slotOccupancy", "controlShareOfCapacity", "dialsPerNodeMin",
            "linkUpsPerNodeMin", "joinMedianS", "mergeFirstLinkS", "mergeLccRecoverS", "peersInRange",
        )
    val instanceColumns =
        listOf(
            "sent", "eligible", "delivery", "deliveryAll", "latP50Ms", "latP95Ms", "latP99Ms", "firstP95ClassicalMs",
            "firstP95PqMs", "firstN", "coverageP10", "fullCoverShare", "dataTxPerDelivery", "dataTxPerNodeS",
            "dataBytesPerNodeS", "ctrlBytesPerNodeS", "idleCtrlBytesPerNodeS", "idleCtrlShare", "loadMeanBps",
            "loadP99Bps", "loadMaxBps", "dupRate", "lostInFlightPerSent", "dropExpiredPerSent", "dropHop_limitPerSent",
            "dropNo_routePerSent", "dropHold_refusedPerSent", "dropHold_fullPerSent", "dropDiscovery_failedPerSent",
            "hopsP50", "hopsP95", "hopsMax", "tableMean", "seenMean", "seenMax", "heldMean",
        )

    fun completedIds(file: File): Set<String> {
        if (!file.exists()) return emptySet()
        return file.readLines().drop(1).filter { it.isNotBlank() }.map { it.substringBefore(',') }.toSet()
    }

    @Synchronized
    fun append(
        file: File,
        spec: BSpec,
        r: RunResult,
        wallMs: Long,
    ) {
        if (!file.exists()) {
            file.parentFile.mkdirs()
            file.writeText((keyColumns + topoColumns + instanceColumns + "wallMs").joinToString(",") + "\n")
        }
        val s = spec.scenario
        val head = listOf(spec.id, spec.set.label, s.density.label, s.nodes, s.mobility.label, s.platform.label, s.degree, spec.strategy.label, spec.seed)
        val topo = topoColumns.map { RawCsv.fmt(r.metrics[it] ?: Double.NaN) }
        val sb = StringBuilder()
        if (r.instances.isEmpty()) {
            val row = head + listOf("none", "", "", "", "", "none") + topo + instanceColumns.map { "" } + wallMs
            sb.append(row.joinToString(",")).append('\n')
        }
        for ((cfg, m) in r.instances) {
            val row =
                head + listOf(cfg.protocol.label, cfg.lifetimeMs / 1000, cfg.minHoldMs / 1000, cfg.skewMs / 1000, if (cfg.rotation) 1 else 0, cfg.label) +
                    topo + instanceColumns.map { RawCsv.fmt(m[it] ?: Double.NaN) } + wallMs
            sb.append(row.joinToString(",")).append('\n')
        }
        file.appendText(sb.toString())
    }
}

fun parseScenario(text: String): Scenario {
    val p = text.split(',')
    return Scenario(
        Density.entries.first { it.label == p[0] }, p[1].toInt(), MobilityKind.entries.first { it.label == p[2] },
        PlatformMix.entries.first { it.label == p[3] }, p[4].toInt(),
    )
}

/** Prints every instance's key metrics for density,N,mobility,platform,d[,seed[,set[,strategy]]]. */
fun stageBProbe(
    specs: List<String>,
    only: Set<String>?,
) {
    val runner = StageBRunner(only = only)
    for (text in specs) {
        val p = text.split(',')
        val sc = parseScenario(text)
        val seed = p.getOrElse(5) { "1" }.toInt()
        val set = InstanceSet.entries.first { it.label == p.getOrElse(6) { "main" } }
        val strategy = StrategyKind.entries.first { it.label == p.getOrElse(7) { "N1+N2" } }
        val (r, wall) = runner.runOne(BSpec(sc, strategy, seed, set))
        val t = r.metrics
        if (System.getProperty("rsim.topo") != null) println("TOPO " + t.entries.filter { it.key != "events" }.joinToString(" ") { "${it.key}=${it.value}" })
        println(
            "${sc.key} ${strategy.label} seed $seed wall ${wall}ms events %.0f lcc %.3f diam %.1f occ %.2f merge1 %.1f mergeLcc %.1f".format(
                t["events"], t["lcc"], t["diameter"], t["slotOccupancy"], t["mergeFirstLinkS"], t["mergeLccRecoverS"],
            ),
        )
        for ((cfg, m) in r.instances) {
            println(
                "  %-34s sent %5.0f elig %5.0f del %.4f all %.4f p50 %6.0f p95 %7.0f first95 c %6.0f pq %6.0f n %3.0f cov10 %.3f tx/del %6.1f maxLoad %7.0f idle %.5f ctrl/ns %6.1f dup %.2f hops95 %3.0f exp %.4f norte %.4f refused %.4f full %.4f disc %.4f lost %.4f table %6.1f held %.2f".format(
                    cfg.label, m["sent"], m["eligible"], m["delivery"], m["deliveryAll"], m["latP50Ms"], m["latP95Ms"],
                    m["firstP95ClassicalMs"], m["firstP95PqMs"], m["firstN"], m["coverageP10"], m["dataTxPerDelivery"],
                    m["loadMaxBps"], m["idleCtrlShare"], m["ctrlBytesPerNodeS"], m["dupRate"], m["hopsP95"],
                    m["dropExpiredPerSent"], m["dropNo_routePerSent"], m["dropHold_refusedPerSent"], m["dropHold_fullPerSent"],
                    m["dropDiscovery_failedPerSent"], m["lostInFlightPerSent"], m["tableMean"], m["heldMean"],
                ),
            )
        }
    }
}

/** (density, N) pairs of the stage B grid (N ∈ {50, 200, 1000}; high density needs N ≥ 200). */
val stageBCombos =
    listOf(
        Density.LOW to 50, Density.MEDIUM to 50,
        Density.LOW to 200, Density.MEDIUM to 200, Density.HIGH to 200,
        Density.LOW to 1000, Density.MEDIUM to 1000, Density.HIGH to 1000,
    )

val stageBMobility = listOf(MobilityKind.STATIC, MobilityKind.RANDOM_WAYPOINT, MobilityKind.GROUP, MobilityKind.CORRIDOR)

class StageBPlan(
    val seeds: Int = 10,
    /** N = 1000 cells keep only these mobility models (the user's pre-agreed cut). */
    val n1000Mobility: List<MobilityKind> = stageBMobility,
    val n1000Seeds: Int = 10,
    /** Seeds 1..this at N = 1000 run U3; the rest run [InstanceSet.MAIN_NO_U3]. */
    val n1000U3Seeds: Int = 10,
    val n1000Degrees: List<Int> = listOf(5, 6),
)

fun stageBSpecs(plan: StageBPlan): List<BSpec> {
    val out = ArrayList<BSpec>()
    val n1n2 = StrategyKind.N1N2
    for ((density, n) in stageBCombos) {
        val mobilities = if (n == 1000) plan.n1000Mobility else stageBMobility
        val degrees = if (n == 1000) plan.n1000Degrees else listOf(5, 6)
        val seeds = if (n == 1000) plan.n1000Seeds else plan.seeds
        for (mobility in mobilities) for (platform in PlatformMix.entries) for (d in degrees) for (seed in 1..seeds) {
            val set = if (n == 1000 && seed > plan.n1000U3Seeds) InstanceSet.MAIN_NO_U3 else InstanceSet.MAIN
            out.add(BSpec(Scenario(density, n, mobility, platform, d), n1n2, seed, set))
        }
    }
    // Lifetime × minimum hold × skew sweep.
    for (density in listOf(Density.LOW, Density.MEDIUM)) {
        for (mobility in listOf(MobilityKind.STATIC, MobilityKind.RANDOM_WAYPOINT)) {
            for (seed in 1..plan.seeds) out.add(BSpec(Scenario(density, 200, mobility, PlatformMix.ANDROID, 5), n1n2, seed, InstanceSet.SWEEP))
        }
    }
    // Bridge variant: partition repair (topology only) at every size, both platform mixes.
    for ((density, n) in stageBCombos) for (platform in PlatformMix.entries) for (strategy in listOf(n1n2, StrategyKind.N1N2_BRIDGE)) {
        for (seed in 1..plan.seeds) out.add(BSpec(Scenario(density, n, MobilityKind.PARTITION_MERGE, platform, 5), strategy, seed, InstanceSet.NONE))
    }
    // Bridge variant: end-to-end effect at low density across the partition and merge.
    for (platform in PlatformMix.entries) for (strategy in listOf(n1n2, StrategyKind.N1N2_BRIDGE)) for (seed in 1..plan.seeds) {
        out.add(BSpec(Scenario(Density.LOW, 200, MobilityKind.PARTITION_MERGE, platform, 5), strategy, seed, InstanceSet.MAIN))
    }
    // Bridge variant: regression check on the main mobility models (topology only, N = 200).
    for (density in listOf(Density.LOW, Density.MEDIUM, Density.HIGH)) for (mobility in stageBMobility) {
        for (strategy in listOf(n1n2, StrategyKind.N1N2_BRIDGE)) for (seed in 1..plan.seeds) {
            out.add(BSpec(Scenario(density, 200, mobility, PlatformMix.ANDROID, 5), strategy, seed, InstanceSet.NONE))
        }
    }
    return out
}

/** Groups raw stage B rows by everything except run id and seed; mean and 95% CI per metric. */
object StageBAggregate {
    val groupColumns =
        listOf("set", "density", "nodes", "mobility", "platform", "degree", "strategy", "protocol", "lifetimeS", "holdS", "skewS", "rotation", "instance")

    fun aggregate(raw: List<Map<String, String>>): List<AggregateRow> {
        if (raw.isEmpty()) return emptyList()
        // MAIN and MAIN_NO_U3 rows of one cell belong together.
        val norm = raw.map { r -> if (r["set"] == "main-no-u3") r + ("set" to "main") else r }
        val metrics = norm.first().keys.filter { it !in groupColumns && it != "runId" && it != "seed" }
        return norm.groupBy { r -> groupColumns.associateWith { r.getValue(it) } }.map { (key, rows) ->
            val mean = LinkedHashMap<String, Double>()
            val ci = LinkedHashMap<String, Double>()
            for (metric in metrics) {
                val xs = rows.mapNotNull { it[metric]?.toDoubleOrNull() }.filter { !it.isNaN() }
                val (m, c) = meanCi(xs)
                mean[metric] = m
                ci[metric] = c
            }
            AggregateRow(key, rows.size, mean, ci)
        }
    }

    fun write(
        rows: List<AggregateRow>,
        file: File,
    ) {
        if (rows.isEmpty()) return
        val metrics = rows.first().mean.keys
        val sb = StringBuilder((groupColumns + "runs" + metrics.flatMap { listOf("${it}_mean", "${it}_ci95") }).joinToString(",")).append('\n')
        for (r in rows) {
            val vals = groupColumns.map { r.key.getValue(it) } + r.n.toString() + metrics.flatMap { listOf(RawCsv.fmt(r.m(it)), RawCsv.fmt(r.c(it))) }
            sb.append(vals.joinToString(",")).append('\n')
        }
        file.writeText(sb.toString())
    }
}

fun meanCi(xs: List<Double>): Pair<Double, Double> {
    if (xs.isEmpty()) return Double.NaN to Double.NaN
    val mu = xs.average()
    if (xs.size < 2) return mu to Double.NaN
    val sd = kotlin.math.sqrt(xs.sumOf { (it - mu) * (it - mu) } / (xs.size - 1))
    return mu to Aggregate.tQuantile(xs.size - 1) * sd / kotlin.math.sqrt(xs.size.toDouble())
}
