package meshlink.routingsim.runner

import meshlink.routingsim.sim.Calibration
import meshlink.routingsim.sim.Density
import meshlink.routingsim.sim.MobilityKind
import meshlink.routingsim.sim.PlatformMix
import meshlink.routingsim.sim.Scenario
import meshlink.routingsim.sim.Simulation
import meshlink.routingsim.sim.StrategyKind
import meshlink.routingsim.strategy.SelectionParams
import java.io.File
import java.util.Locale

private const val DEFAULT_OUT = "out"

private fun arg(
    args: Array<String>,
    name: String,
    default: String,
): String {
    val i = args.indexOf("--$name")
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else default
}

fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val command = args.firstOrNull() ?: "help"
    val out = File(arg(args, "out", DEFAULT_OUT))
    val threads = arg(args, "threads", "4").toInt()
    when (command) {
        "pilot" -> pilot(threads)
        "probe" -> probe(args.drop(1).filter { !it.startsWith("--") && it.contains(',') })
        "calibrate-drop" -> calibrateDrop(threads)
        "timeseries" -> timeseries(out)
        "stage-a" -> stageA(out, threads, arg(args, "seeds", "10").toInt(), arg(args, "plan", "full"))
        "aggregate" -> aggregate(out)
        "report" -> report(out)
        "b-probe" ->
            stageBProbe(
                args.drop(1).filter { !it.startsWith("--") && it.contains(',') },
                arg(args, "only", "").takeIf { it.isNotEmpty() }?.split('+')?.toSet(),
            )
        "stage-b" -> {
            val plan = stageBPlanFrom(args)
            val specs = stageBSpecs(plan)
            if (args.contains("--dry")) {
                specs.groupBy { "${it.scenario.nodes} ${it.set.label}" }.toSortedMap().forEach { (k, v) -> println("$k: ${v.size}") }
                println("total ${specs.size}")
            } else {
                StageBRunner().runAll(specs, File(out, "stage-b-raw.csv"), threads)
                stageBAggregate(out)
            }
        }
        "stage-b-aggregate" -> stageBAggregate(out)
        else ->
            println(
                """
                usage: routing-sim <command> [--out DIR] [--threads 4]
                  pilot            one run per (density, N) and strategy, prints wall time per run
                  calibrate-drop   fit the link-drop hazard to cairn link lifetimes
                  timeseries       LCC over time for steady-state justification (writes DIR/timeseries.csv)
                  stage-a          run the stage A grid into DIR/stage-a-raw.csv (resumable) [--seeds 10] [--plan full|pruned]
                  aggregate        DIR/stage-a-raw.csv -> DIR/stage-a-aggregate.csv
                  b-probe CELL..   stage B: one run per density,N,mobility,platform,d[,seed[,set[,strategy]]], prints every instance [--only U1+U3]
                  stage-b          run the stage B plan into DIR/stage-b-raw.csv (resumable) [--n1000-mobility static] [--n1000-seeds 10]
                                   [--n1000-u3-seeds 10] [--n1000-degrees 5,6] [--dry]
                  stage-b-aggregate DIR/stage-b-raw.csv -> DIR/stage-b-aggregate.csv
                  report           DIR/stage-a-raw.csv (+ optional findings.html, run-notes.txt) -> DIR/stage-a-report.html
                """.trimIndent(),
            )
    }
}

/** Runs scenarios given as density,N,mobility,platform,d,sybilShare[,seed] with both strategies. */
private fun probe(specs: List<String>) {
    val runner = Runner()
    for (text in specs) {
        val p = text.split(',')
        val sc =
            Scenario(
                Density.entries.first { it.label == p[0] }, p[1].toInt(), MobilityKind.entries.first { it.label == p[2] },
                PlatformMix.entries.first { it.label == p[3] }, p[4].toInt(), p.getOrElse(5) { "0" }.toDouble(),
            )
        for (strategy in StrategyKind.entries) {
            val (r, wall) = runner.runOne(RunSpec(sc, strategy, p.getOrElse(6) { "1" }.toInt()))
            println("${sc.key} ${strategy.label} wall=${wall}ms " + r.metrics.entries.joinToString(" ") { "${it.key}=%.3g".format(it.value) })
        }
    }
}

private fun pilot(threads: Int) {
    val runner = Runner()
    val specs =
        sizeCombos.flatMap { (density, n) ->
            StrategyKind.entries.map {
                RunSpec(Scenario(density, n, MobilityKind.RANDOM_WAYPOINT, PlatformMix.ANDROID, 5), it, 1)
            }
        }
    val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
    val futures = specs.map { spec -> pool.submit<Pair<RunSpec, Pair<meshlink.routingsim.sim.RunResult, Long>>> { spec to runner.runOne(spec) } }
    for (f in futures) {
        val (spec, res) = f.get()
        val (r, wall) = res
        val m = r.metrics
        println(
            "%-48s %-6s wall %6d ms events %9.0f inRange %6.1f lcc %.3f radioLcc %.3f diam %5.1f dials/nm %.2f fail %.2f ctrlB/ns %.1f join50 %.1f s life50 %.0f s".format(
                spec.scenario.key, spec.strategy.label, wall, m["events"], m["peersInRange"], m["lcc"], m["radioLcc"],
                m["diameter"], m["dialsPerNodeMin"], m["dialFailureRate"], m["controlBytesPerNodeS"], m["joinMedianS"],
                m["linkLifetimeMedianS"],
            ),
        )
    }
    pool.shutdown()
}

/**
 * cairn fleet-like setting: 15 phones in one room (all in range), Android, d = 5, N0 with rotation.
 * Sweeps the supervision-drop mean and prints median and p90 link lifetimes to compare with cairn
 * ADR 0031 (median 27 s, p90 52 s).
 */
private fun calibrateDrop(threads: Int) {
    val means = listOf(30_000.0, 45_000.0, 60_000.0, 70_000.0, 90_000.0, 120_000.0, 180_000.0, 1e12)
    val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
    val futures =
        means.map { mean ->
            pool.submit<String> {
                val cal = Calibration(linkDropMeanMs = mean)
                val lifetimes = ArrayList<Double>()
                val p90s = ArrayList<Double>()
                for (seed in 1..10) {
                    val sc = Scenario(Density.MEDIUM, 15, MobilityKind.STATIC, PlatformMix.ANDROID, 5)
                    val r = Simulation(sc, StrategyKind.N0, seed, cal, SelectionParams(), 15.0).run()
                    lifetimes.add(r.metrics["linkLifetimeMedianS"]!!)
                    p90s.add(r.metrics["linkLifetimeP90S"]!!)
                }
                "drop mean %8.0f s: link lifetime median %.1f s (seeds %.1f..%.1f), p90 %.1f s".format(
                    mean / 1000, lifetimes.average(), lifetimes.min(), lifetimes.max(), p90s.average(),
                )
            }
        }
    futures.forEach { println(it.get()) }
    pool.shutdown()
}

private fun timeseries(out: File) {
    out.mkdirs()
    val file = File(out, "timeseries.csv")
    val sb = StringBuilder("scenario,strategy,seed,timeS,lcc,radioLcc,slotOccupancy,honestLinks\n")
    val runner = Runner()
    val cases =
        listOf(
            Scenario(Density.LOW, 200, MobilityKind.RANDOM_WAYPOINT, PlatformMix.ANDROID, 5),
            Scenario(Density.MEDIUM, 1000, MobilityKind.RANDOM_WAYPOINT, PlatformMix.ANDROID, 5),
            Scenario(Density.HIGH, 1000, MobilityKind.STATIC, PlatformMix.ANDROID, 5),
            Scenario(Density.MEDIUM, 200, MobilityKind.STATIC, PlatformMix.HALF_IOS, 5),
        )
    for (sc in cases) {
        for (strategy in StrategyKind.entries) {
            Simulation(sc, strategy, 1, Calibration(), SelectionParams(), runner.extentFor(sc), timeSeries = { t, row ->
                sb.append(
                    "${sc.key},${strategy.label},1,${t / 1000},${row["lcc"]},${row["radioLcc"]},${row["slotOccupancy"]},${row["honestLinks"]}\n",
                )
            }).run()
        }
    }
    file.writeText(sb.toString())
    println("wrote ${file.path}")
}

/** Full stage A plan, or the pruned plan chosen after the pilot (README "Pruning"). */
fun stageASpecs(
    seeds: Int,
    plan: String,
): List<RunSpec> {
    val specs = ArrayList<RunSpec>()
    for ((density, n) in sizeCombos) {
        for (mobility in MobilityKind.entries) {
            for (platform in PlatformMix.entries) {
                for (d in degrees) {
                    if (plan == "pruned" && !keepInPrunedPlan(n, mobility, platform, d)) continue
                    for (strategy in StrategyKind.entries) {
                        for (seed in 1..seeds) specs.add(RunSpec(Scenario(density, n, mobility, platform, d), strategy, seed))
                    }
                }
            }
        }
    }
    for (density in Density.entries) {
        for (mobility in listOf(MobilityKind.STATIC, MobilityKind.RANDOM_WAYPOINT)) {
            for (share in listOf(0.1, 0.3)) {
                for (strategy in StrategyKind.entries) {
                    for (seed in 1..seeds) {
                        specs.add(RunSpec(Scenario(density, 200, mobility, PlatformMix.ANDROID, 5, sybilShare = share), strategy, seed))
                    }
                }
            }
        }
    }
    // Sensitivity of the topology metrics to the fitted supervision-drop hazard (0 = no drops).
    for (mobility in listOf(MobilityKind.STATIC, MobilityKind.RANDOM_WAYPOINT, MobilityKind.GROUP)) {
        for (drop in listOf(180, 0)) {
            for (strategy in StrategyKind.entries) {
                for (seed in 1..seeds) {
                    specs.add(RunSpec(Scenario(Density.MEDIUM, 200, mobility, PlatformMix.ANDROID, 5, dropMeanS = drop), strategy, seed))
                }
            }
        }
    }
    return specs
}

/** Pruning rule (applied only with --plan pruned): at N = 1000, sweep d only for static and random waypoint. */
fun keepInPrunedPlan(
    n: Int,
    mobility: MobilityKind,
    platform: PlatformMix,
    d: Int,
): Boolean {
    if (n < 1000) return true
    if (mobility == MobilityKind.STATIC || mobility == MobilityKind.RANDOM_WAYPOINT) return true
    return d == 5 || d == 6
}

private fun stageA(
    out: File,
    threads: Int,
    seeds: Int,
    plan: String,
) {
    Runner().runAll(stageASpecs(seeds, plan), File(out, "stage-a-raw.csv"), threads)
    aggregate(out)
}

private fun aggregate(out: File) {
    val rows = Aggregate.aggregate(RawCsv.read(File(out, "stage-a-raw.csv")))
    Aggregate.write(rows, File(out, "stage-a-aggregate.csv"))
    println("aggregated ${rows.size} cells into ${File(out, "stage-a-aggregate.csv").path}")
}

private fun report(out: File) {
    val rows = Aggregate.aggregate(RawCsv.read(File(out, "stage-a-raw.csv")))
    val file = File(out, "stage-a-report.html")
    file.writeText(HtmlReport.render(rows, File(out, "timeseries.csv").takeIf { it.exists() }, File(out, "run-notes.txt").takeIf { it.exists() }, File(out, "findings.html").takeIf { it.exists() }))
    println("wrote ${file.path}")
}

private fun stageBPlanFrom(args: Array<String>): StageBPlan =
    StageBPlan(
        seeds = arg(args, "seeds", "10").toInt(),
        n1000Mobility = arg(args, "n1000-mobility", "static,waypoint,group,corridor").split(',').map { m -> MobilityKind.entries.first { it.label == m } },
        n1000Seeds = arg(args, "n1000-seeds", "10").toInt(),
        n1000U3Seeds = arg(args, "n1000-u3-seeds", "10").toInt(),
        n1000Degrees = arg(args, "n1000-degrees", "5,6").split(',').map { it.toInt() },
    )

private fun stageBAggregate(out: File) {
    val rows = StageBAggregate.aggregate(RawCsv.read(File(out, "stage-b-raw.csv")))
    StageBAggregate.write(rows, File(out, "stage-b-aggregate.csv"))
    println("aggregated ${rows.size} rows into ${File(out, "stage-b-aggregate.csv").path}")
}
