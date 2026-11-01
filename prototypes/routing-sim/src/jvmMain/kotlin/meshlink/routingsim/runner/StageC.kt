package meshlink.routingsim.runner

import meshlink.routingsim.density.ClassSettings
import meshlink.routingsim.sim.Calibration
import meshlink.routingsim.sim.Density
import meshlink.routingsim.sim.DensitySetup
import meshlink.routingsim.sim.InstanceConfig
import meshlink.routingsim.sim.MobilityKind
import meshlink.routingsim.sim.PlatformMix
import meshlink.routingsim.sim.Protocol
import meshlink.routingsim.sim.RoutingSetup
import meshlink.routingsim.sim.RunResult
import meshlink.routingsim.sim.Scenario
import meshlink.routingsim.sim.Simulation
import meshlink.routingsim.sim.StrategyKind
import meshlink.routingsim.strategy.SelectionParams
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/** Stage C topology variants. Every variant runs the local density estimator; only adaptive ones act on it. */
enum class Topo(
    val label: String,
    val degree: Int,
    val adaptive: Boolean,
    val duty: Boolean,
) {
    FIXED5("fixed-d5", 5, false, false),
    FIXED6("fixed-d6", 6, false, false),

    /** Per-class degree, bridge limit, scan and advertising duty. */
    ADAPT("adaptive", 5, true, true),

    /** Per-class degree and bridge limit; scan and advertising stay at the fixed (fast) duty. */
    ADAPT_NODUTY("adaptive-noduty", 5, true, false),

    /** As [ADAPT] but 5 links in every class (follow-up after the main grid). */
    ADAPT_D5("adaptive-d5", 5, true, true),
}

enum class CSet(
    val label: String,
) {
    MAIN("main"),

    /** N = 1000: the main set without the relay-preference and rotation variants and UA4. */
    BIG("big"),
    LIFE("life"),
    LONG("long"),
    NONE("none"),
}

fun stageCInstances(set: CSet): List<InstanceConfig> =
    when (set) {
        CSet.MAIN ->
            listOf(
                InstanceConfig(Protocol.U1), InstanceConfig(Protocol.U4SF), InstanceConfig(Protocol.U6),
                InstanceConfig(Protocol.UA), InstanceConfig(Protocol.UA4),
                InstanceConfig(Protocol.UA, pref = true), InstanceConfig(Protocol.UA, rotation = true),
                InstanceConfig(Protocol.B1), InstanceConfig(Protocol.B3),
                InstanceConfig(Protocol.BA), InstanceConfig(Protocol.BA, pref = true),
            )
        CSet.BIG ->
            listOf(
                InstanceConfig(Protocol.U1), InstanceConfig(Protocol.U4SF), InstanceConfig(Protocol.U6), InstanceConfig(Protocol.UA),
                InstanceConfig(Protocol.B1), InstanceConfig(Protocol.B3), InstanceConfig(Protocol.BA),
            )
        CSet.LIFE ->
            listOf(Protocol.U1, Protocol.UA, Protocol.UA4).flatMap { p ->
                listOf(60_000L, 120_000L).flatMap { life ->
                    listOf(0L, 10_000L, 60_000L).map { hold -> InstanceConfig(p, life, hold, 30_000L, rotation = true) }
                }
            }
        CSet.LONG ->
            listOf(Protocol.U1, Protocol.UA, Protocol.UA4).flatMap { p ->
                listOf(3_600_000L, 86_400_000L).map { life -> InstanceConfig(p, life, 10_000L, 30_000L, rotation = true) }
            }
        CSet.NONE -> emptyList()
    }

data class CSpec(
    val scenario: Scenario,
    val topo: Topo,
    val seed: Int,
    val set: CSet,
    /** Bridge rate limit for fixed topologies (negative = no bridge preference). */
    val bridgeGapMs: Long,
    /** Driving estimator and classifier ("w10e:s1h25/d60"); null = the default. */
    val drive: String? = null,
) {
    val id get() =
        "${scenario.key}|dur${scenario.durationMs / 1000}|${topo.label}|gap${gapLabel(bridgeGapMs)}|$seed|${set.label}" +
            (drive?.let { "|drive=$it" } ?: "")
}

fun gapLabel(ms: Long) = if (ms < 0) "off" else (ms / 1000).toString()

/** Bridge limits per class for the adaptive topology, and the single limit of fixed topologies. */
class BridgeChoice(
    val fixedMs: Long,
    val perClassMs: LongArray,
)

class StageCRunner(
    private val bridge: BridgeChoice,
) {
    private fun densitySetup(
        settings: ClassSettings?,
        drive: String?,
    ): DensitySetup {
        if (drive == null) return DensitySetup(settings = settings)
        val (est, cls) = DensitySetup.parseDrive(drive)
        val classifiers = DensitySetup.defaultClassifiers()
        return DensitySetup(
            classifiers = classifiers,
            driveEstimator = DensitySetup.DEFAULT_ESTIMATORS.indexOfFirst { it.label == est },
            driveClassifier = classifiers.firstOrNull { it.label == cls.label } ?: cls,
            settings = settings,
        )
    }

    private val base = Runner()
    private val cal = Calibration()

    fun settingsFor(topo: Topo): ClassSettings? =
        when {
            !topo.adaptive -> null
            topo.duty ->
                ClassSettings(
                    topo.label, if (topo == Topo.ADAPT_D5) intArrayOf(5, 5, 5) else intArrayOf(6, 5, 5), intArrayOf(1, 2, 3), booleanArrayOf(true, true, false), bridge.perClassMs,
                )
            else ->
                ClassSettings(
                    topo.label, intArrayOf(6, 5, 5), intArrayOf(1, 1, 1), booleanArrayOf(true, true, true), bridge.perClassMs,
                )
        }

    fun runOne(spec: CSpec): Pair<RunResult, Long> {
        val t0 = System.nanoTime()
        val s = spec.scenario
        val venue = s.mobility in setOf(MobilityKind.ARRIVAL, MobilityKind.DEPARTURE, MobilityKind.CORE_EDGE, MobilityKind.SURGE)
        val extent = if (venue) 0.0 else base.extentFor(s)
        val bridgeOn = spec.topo.adaptive || spec.bridgeGapMs >= 0
        val strategy = if (bridgeOn) StrategyKind.N1N2_BRIDGE else StrategyKind.N1N2
        val params = SelectionParams(bridgeMinGapMs = if (spec.bridgeGapMs >= 0) spec.bridgeGapMs else 10_000)
        val list = stageCInstances(spec.set)
        val setup = if (list.isEmpty()) null else RoutingSetup(list)
        val r =
            Simulation(
                s, strategy, spec.seed, cal, params, extent,
                routingSetup = setup,
                densitySetup = densitySetup(settingsFor(spec.topo), spec.drive),
            ).run()
        return r to (System.nanoTime() - t0) / 1_000_000
    }

    fun runAll(
        specs: List<CSpec>,
        dir: File,
        threads: Int,
    ) {
        val done = CRaw.completedIds(dir)
        val todo = specs.filter { it.id !in done }.sortedByDescending { it.scenario.nodes * it.scenario.durationMs / 60_000 }
        println("runs: ${specs.size} total, ${specs.size - todo.size} already done, ${todo.size} to do, $threads threads")
        val queue = ArrayDeque(todo)
        val lock = Object()
        var bigRunning = 0
        val finished = AtomicInteger()
        val start = System.nanoTime()

        fun isBig(s: CSpec) = s.scenario.nodes >= 1000 && s.set != CSet.NONE

        fun next(): CSpec? =
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
                            CRaw.append(dir, spec, result, wall)
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

/** Raw results: one wide CSV per instance set, one row per (run, protocol instance). */
object CRaw {
    val keyColumns =
        listOf(
            "runId", "set", "scenario", "density", "nodes", "platform", "degree", "topo", "bridgeGapS", "durationS", "seed",
            "drive", "protocol", "instance", "lifetimeS", "holdS", "skewS", "rotation", "pref",
        )
    private val headers = HashMap<String, List<String>>()

    fun file(
        dir: File,
        set: CSet,
    ) = File(dir, "raw-${set.label}.csv")

    fun completedIds(dir: File): Set<String> =
        CSet.entries.flatMap { set ->
            val f = file(dir, set)
            if (!f.exists()) emptyList() else f.readLines().drop(1).filter { it.isNotBlank() }.map { it.substringBefore(',') }
        }.toSet()

    @Synchronized
    fun append(
        dir: File,
        spec: CSpec,
        r: RunResult,
        wallMs: Long,
    ) {
        val f = file(dir, spec.set)
        val topoKeys = r.metrics.keys.map { "t_$it" }
        val instKeys = r.instances.firstOrNull()?.second?.keys?.toList() ?: emptyList()
        val header =
            headers.getOrPut(spec.set.label) {
                if (f.exists()) {
                    f.bufferedReader().use { it.readLine() }.split(',')
                } else {
                    val h = keyColumns + topoKeys + instKeys.filter { it != "modeSwitchesPerNodeH" } + "modeSwitchesPerNodeH" + "wallMs"
                    f.parentFile.mkdirs()
                    f.writeText(h.joinToString(",") + "\n")
                    h
                }
            }
        val s = spec.scenario
        val sb = StringBuilder()
        val rows = if (r.instances.isEmpty()) listOf(null) else r.instances
        for (inst in rows) {
            val values = HashMap<String, String>()
            values["runId"] = spec.id
            values["set"] = spec.set.label
            values["scenario"] = s.mobility.label
            values["density"] = s.density.label
            values["nodes"] = s.nodes.toString()
            values["platform"] = s.platform.label
            values["degree"] = s.degree.toString()
            values["topo"] = spec.topo.label
            values["bridgeGapS"] = gapLabel(spec.bridgeGapMs)
            values["durationS"] = (s.durationMs / 1000).toString()
            values["seed"] = spec.seed.toString()
            values["drive"] = spec.drive ?: "default"
            values["wallMs"] = wallMs.toString()
            for ((k, v) in r.metrics) values["t_$k"] = RawCsv.fmt(v)
            if (inst == null) {
                values["protocol"] = "none"
                values["instance"] = "none"
            } else {
                val (cfg, m) = inst
                values["protocol"] = cfg.protocol.label
                values["instance"] = cfg.label
                values["lifetimeS"] = (cfg.lifetimeMs / 1000).toString()
                values["holdS"] = (cfg.minHoldMs / 1000).toString()
                values["skewS"] = (cfg.skewMs / 1000).toString()
                values["rotation"] = if (cfg.rotation) "1" else "0"
                values["pref"] = if (cfg.pref) "1" else "0"
                for ((k, v) in m) values[k] = RawCsv.fmt(v)
            }
            sb.append(header.joinToString(",") { values[it] ?: "" }).append('\n')
        }
        f.appendText(sb.toString())
    }
}

/** Mean and 95% CI over seeds per (cell, topology, instance). */
object CAggregate {
    val groupColumns = CRaw.keyColumns.filter { it != "runId" && it != "seed" }

    fun aggregate(raw: List<Map<String, String>>): List<AggregateRow> {
        if (raw.isEmpty()) return emptyList()
        val metrics = raw.first().keys.filter { it !in CRaw.keyColumns }
        return raw.groupBy { r -> groupColumns.associateWith { r[it] ?: "" } }.map { (key, rows) ->
            val mean = LinkedHashMap<String, Double>()
            val ci = LinkedHashMap<String, Double>()
            for (metric in metrics) {
                val xs = rows.mapNotNull { it[metric]?.toDoubleOrNull() }.filter { !it.isNaN() }
                if (xs.isEmpty()) {
                    mean[metric] = Double.NaN
                    ci[metric] = Double.NaN
                    continue
                }
                val mu = xs.average()
                mean[metric] = mu
                ci[metric] =
                    if (xs.size < 2) Double.NaN else Aggregate.tQuantile(xs.size - 1) * sqrt(xs.sumOf { (it - mu) * (it - mu) } / (xs.size - 1)) / sqrt(xs.size.toDouble())
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
        val header = groupColumns + "runs" + metrics.flatMap { listOf("${it}_mean", "${it}_ci95") }
        val sb = StringBuilder(header.joinToString(",")).append('\n')
        for (r in rows) {
            val vals = groupColumns.map { r.key.getValue(it) } + r.n.toString() + metrics.flatMap { listOf(f(r.m(it)), f(r.c(it))) }
            sb.append(vals.joinToString(",")).append('\n')
        }
        file.writeText(sb.toString())
    }

    private fun f(v: Double) = if (v.isNaN()) "NaN" else String.format(Locale.ROOT, "%.6g", v)
}

/** The stage C plan (see README "Stage C"). */
object StageCPlan {
    val dynamicScenarios =
        listOf(
            Density.DYNAMIC to MobilityKind.ARRIVAL,
            Density.DYNAMIC to MobilityKind.DEPARTURE,
            Density.DYNAMIC to MobilityKind.CORE_EDGE,
            Density.DYNAMIC to MobilityKind.SURGE,
            Density.AT10 to MobilityKind.RANDOM_WAYPOINT,
            Density.AT50 to MobilityKind.RANDOM_WAYPOINT,
            Density.AT10 to MobilityKind.STATIC,
            Density.AT50 to MobilityKind.STATIC,
        )

    fun durationOf(m: MobilityKind) = if (m == MobilityKind.ARRIVAL || m == MobilityKind.DEPARTURE) 1_200_000L else 900_000L

    val staticCombos =
        listOf(Density.LOW to 50, Density.MEDIUM to 50, Density.LOW to 200, Density.MEDIUM to 200, Density.HIGH to 200)
    val staticMobility = listOf(MobilityKind.STATIC, MobilityKind.RANDOM_WAYPOINT, MobilityKind.GROUP, MobilityKind.CORRIDOR)
    val bridgeGaps = listOf(-1L, 10_000L, 30_000L, 60_000L, 120_000L)

    fun scenario(
        d: Density,
        n: Int,
        m: MobilityKind,
        p: PlatformMix,
        degree: Int,
        durationMs: Long = durationOf(m),
    ) = Scenario(d, n, m, p, degree, durationMs = durationMs)

    fun bridge(seeds: Int = 10): List<CSpec> =
        listOf(Density.LOW, Density.MEDIUM, Density.HIGH).flatMap { d ->
            bridgeGaps.flatMap { gap ->
                (1..seeds).map { seed ->
                    CSpec(scenario(d, 200, MobilityKind.PARTITION_MERGE, PlatformMix.ANDROID, 5), Topo.FIXED5, seed, CSet.NONE, gap)
                }
            }
        }

    fun main(
        fixedGap: Long,
        seeds: Int = 10,
        n1000Seeds: Int = 5,
        withStatic: Boolean = true,
    ): List<CSpec> {
        val out = ArrayList<CSpec>()
        for ((d, m) in dynamicScenarios) {
            for (p in PlatformMix.entries) {
                for (t in listOf(Topo.FIXED5, Topo.FIXED6, Topo.ADAPT, Topo.ADAPT_NODUTY)) {
                    for (seed in 1..seeds) out.add(CSpec(scenario(d, 200, m, p, t.degree), t, seed, CSet.MAIN, fixedGap))
                }
            }
        }
        if (withStatic) {
            for ((d, n) in staticCombos) {
                for (m in staticMobility) {
                    for (p in PlatformMix.entries) {
                        for (t in listOf(Topo.FIXED5, Topo.FIXED6, Topo.ADAPT)) {
                            for (seed in 1..seeds) out.add(CSpec(scenario(d, n, m, p, t.degree), t, seed, CSet.MAIN, fixedGap))
                        }
                    }
                }
            }
        }
        for (p in PlatformMix.entries) {
            for (t in listOf(Topo.FIXED5, Topo.ADAPT)) {
                for (seed in 1..n1000Seeds) {
                    out.add(CSpec(scenario(Density.DYNAMIC, 1000, MobilityKind.ARRIVAL, p, t.degree), t, seed, CSet.BIG, fixedGap))
                }
            }
        }
        return out
    }

    /** Follow-up: the adaptive policy with 5 links in every class, on every N = 50/200 cell of the main set. */
    fun tune(
        fixedGap: Long,
        seeds: Int = 10,
    ): List<CSpec> {
        val out = ArrayList<CSpec>()
        for ((d, m) in dynamicScenarios) {
            for (p in PlatformMix.entries) for (seed in 1..seeds) out.add(CSpec(scenario(d, 200, m, p, 5), Topo.ADAPT_D5, seed, CSet.MAIN, fixedGap))
        }
        for ((d, n) in staticCombos) {
            for (m in staticMobility) {
                for (p in PlatformMix.entries) for (seed in 1..seeds) out.add(CSpec(scenario(d, n, m, p, 5), Topo.ADAPT_D5, seed, CSet.MAIN, fixedGap))
            }
        }
        return out
    }

    val driveAlternatives = listOf("w10e:s1h25/d30", "w10e:s1h50/d60", "w20e:s1h25/d60")

    /** Adaptive policy with alternative estimator/classifier drives on the N = 200 dynamic scenarios. */
    fun drives(
        fixedGap: Long,
        seeds: Int = 5,
    ): List<CSpec> =
        dynamicScenarios.flatMap { (d, m) ->
            PlatformMix.entries.flatMap { p ->
                driveAlternatives.flatMap { dr ->
                    (1..seeds).map { seed -> CSpec(scenario(d, 200, m, p, 5), Topo.ADAPT, seed, CSet.MAIN, fixedGap, dr) }
                }
            }
        }

    fun life(
        fixedGap: Long,
        seeds: Int = 10,
    ): List<CSpec> {
        val cells =
            listOf(
                Density.DYNAMIC to MobilityKind.ARRIVAL, Density.DYNAMIC to MobilityKind.CORE_EDGE,
                Density.LOW to MobilityKind.RANDOM_WAYPOINT, Density.LOW to MobilityKind.CORRIDOR,
            )
        return cells.flatMap { (d, m) ->
            PlatformMix.entries.flatMap { p ->
                (1..seeds).map { seed -> CSpec(scenario(d, 200, m, p, 5), Topo.ADAPT, seed, CSet.LIFE, fixedGap) }
            }
        }
    }

    fun long(
        fixedGap: Long,
        seeds: Int = 5,
    ): List<CSpec> =
        listOf(MobilityKind.RANDOM_WAYPOINT, MobilityKind.CORRIDOR).flatMap { m ->
            PlatformMix.entries.flatMap { p ->
                (1..seeds).map { seed -> CSpec(scenario(Density.LOW, 200, m, p, 5, 3_600_000L), Topo.ADAPT, seed, CSet.LONG, fixedGap) }
            }
        }
}

private fun parseGaps(text: String?): LongArray = text?.split('/')?.map { it.toLong() * 1000 }?.toLongArray() ?: longArrayOf(60_000, 60_000, 60_000)

/** CLI: c-bridge | c-pilot | c-run [--set main,life,long] | c-aggregate | c-probe spec.. */
fun stageC(
    cmd: String,
    args: List<String>,
    outDir: File,
) {
    fun opt(name: String) = args.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=')
    val threads = opt("threads")?.toInt() ?: 4
    val fixedGap = (opt("fixed-gap")?.toLong() ?: 60) * 1000
    val choice = BridgeChoice(fixedGap, parseGaps(opt("class-gaps")))
    val runner = StageCRunner(choice)
    when (cmd) {
        "c-bridge" -> runner.runAll(StageCPlan.bridge(), outDir, threads)
        "c-pilot" -> {
            val specs =
                StageCPlan.main(fixedGap, seeds = 1, n1000Seeds = 1).filter { it.scenario.platform == PlatformMix.HALF_IOS || it.scenario.nodes == 1000 } +
                    StageCPlan.life(fixedGap, 1).take(2) + StageCPlan.long(fixedGap, 1).take(1)
            val must = opt("only")?.split('&')?.map { it.split(';') } ?: emptyList()
            val not = opt("skip")?.split('&') ?: emptyList()
            val pick = specs.filter { s -> must.all { alt -> alt.any { s.id.contains(it) } } && not.none { s.id.contains(it) } }
            val t0 = System.nanoTime()
            runner.runAll(pick, File(outDir, "pilot"), threads)
            println("pilot wall %.0f s for ${pick.size} runs".format((System.nanoTime() - t0) / 1e9))
            val rows = (File(outDir, "pilot").listFiles() ?: emptyArray()).filter { it.name.startsWith("raw-") }.flatMap { RawCsv.read(it) }
            rows.groupBy { it["runId"] }.forEach { (id, r) -> println("  ${r.first()["wallMs"]} ms  $id") }
        }
        "c-run" -> {
            val sets = (opt("set") ?: "main,life,long").split(',')
            val specs = ArrayList<CSpec>()
            if ("main" in sets) specs += StageCPlan.main(fixedGap, withStatic = opt("no-static") == null)
            if ("life" in sets) specs += StageCPlan.life(fixedGap)
            if ("long" in sets) specs += StageCPlan.long(fixedGap)
            if ("drives" in sets) specs += StageCPlan.drives(fixedGap)
            if ("tune" in sets) specs += StageCPlan.tune(fixedGap)
            runner.runAll(specs, outDir, threads)
        }
        "c-aggregate" -> {
            for (set in CSet.entries) {
                val f = CRaw.file(outDir, set)
                if (!f.exists()) continue
                val rows = CAggregate.aggregate(RawCsv.read(f))
                CAggregate.write(rows, File(outDir, "agg-${set.label}.csv"))
                println("${set.label}: ${rows.size} aggregated rows")
            }
        }
        else -> error("unknown $cmd")
    }
}
