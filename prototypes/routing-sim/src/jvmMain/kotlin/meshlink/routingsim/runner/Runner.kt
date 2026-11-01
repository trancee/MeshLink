package meshlink.routingsim.runner

import meshlink.routingsim.sim.AreaSizer
import meshlink.routingsim.sim.Calibration
import meshlink.routingsim.sim.Density
import meshlink.routingsim.sim.MobilityKind
import meshlink.routingsim.sim.PlatformMix
import meshlink.routingsim.sim.RunResult
import meshlink.routingsim.sim.Scenario
import meshlink.routingsim.sim.Simulation
import meshlink.routingsim.sim.StrategyKind
import meshlink.routingsim.strategy.SelectionParams
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class RunSpec(
    val scenario: Scenario,
    val strategy: StrategyKind,
    val seed: Int,
) {
    val id get() = "${scenario.key}|${strategy.label}|$seed"
}

/** (density, N) pairs that can reach the density class; the rest are skipped (see README). */
val sizeCombos =
    listOf(
        Density.LOW to 10, Density.LOW to 50, Density.LOW to 200, Density.LOW to 1000,
        Density.MEDIUM to 50, Density.MEDIUM to 200, Density.MEDIUM to 1000,
        Density.HIGH to 200, Density.HIGH to 1000,
    )

val degrees = listOf(3, 5, 6, 8)

class Runner(
    private val cal: Calibration = Calibration(),
    private val params: SelectionParams = SelectionParams(),
) {
    private val extents = ConcurrentHashMap<String, Double>()

    fun extentFor(s: Scenario): Double =
        extents.computeIfAbsent("${s.mobility}-${s.nodes}-${s.density}") {
            AreaSizer.extentFor(s.mobility, s.nodes, s.density.targetPeers, cal)
        }

    fun runOne(spec: RunSpec): Pair<RunResult, Long> {
        val t0 = System.nanoTime()
        val result = Simulation(spec.scenario, spec.strategy, spec.seed, cal, params, extentFor(spec.scenario)).run()
        return result to (System.nanoTime() - t0) / 1_000_000
    }

    fun runAll(
        specs: List<RunSpec>,
        rawFile: File,
        threads: Int,
    ) {
        val done = RawCsv.completedIds(rawFile)
        val todo = specs.filter { it.id !in done }.sortedByDescending { it.scenario.nodes }
        println("runs: ${specs.size} total, ${done.size} already in ${rawFile.name}, ${todo.size} to do, $threads threads")
        val pool = Executors.newFixedThreadPool(threads)
        val finished = AtomicInteger()
        val start = System.nanoTime()
        for (spec in todo) {
            pool.submit {
                try {
                    val (result, wall) = runOne(spec)
                    RawCsv.append(rawFile, spec, result, wall, extentFor(spec.scenario))
                    val k = finished.incrementAndGet()
                    if (k % 50 == 0 || k == todo.size) {
                        val el = (System.nanoTime() - start) / 1e9
                        println("  $k/${todo.size} done, %.0f s elapsed, eta %.0f s".format(el, el / k * (todo.size - k)))
                    }
                } catch (e: Throwable) {
                    System.err.println("FAILED ${spec.id}: $e")
                    e.printStackTrace()
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(7, TimeUnit.DAYS)
    }
}

object RawCsv {
    private val keyColumns =
        listOf("runId", "density", "nodes", "mobility", "platform", "degree", "sybilShare", "dropMeanS", "strategy", "seed", "extentM")

    fun completedIds(file: File): Set<String> {
        if (!file.exists()) return emptySet()
        return file.readLines().drop(1).filter { it.isNotBlank() }.map { it.substringBefore(',') }.toSet()
    }

    @Synchronized
    fun append(
        file: File,
        spec: RunSpec,
        result: RunResult,
        wallMs: Long,
        extent: Double,
    ) {
        val s = spec.scenario
        if (!file.exists()) {
            file.parentFile.mkdirs()
            file.writeText((keyColumns + result.metrics.keys + "wallMs").joinToString(",") + "\n")
        }
        val values =
            listOf(
                spec.id, s.density.label, s.nodes, s.mobility.label, s.platform.label, s.degree, s.sybilShare, s.dropMeanS ?: "default",
                spec.strategy.label, spec.seed, String.format(Locale.ROOT, "%.1f", extent),
            ) + result.metrics.values.map { fmt(it) } + wallMs
        file.appendText(values.joinToString(",") + "\n")
    }

    fun fmt(v: Double): String = if (v.isNaN()) "NaN" else String.format(Locale.ROOT, "%.6g", v)

    fun read(file: File): List<Map<String, String>> {
        val lines = file.readLines().filter { it.isNotBlank() }
        val header = lines.first().split(',')
        return lines.drop(1).map { line -> header.zip(line.split(',')).toMap() }
    }
}
