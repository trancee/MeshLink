package meshlink.routingsim.runner

import meshlink.routingsim.density.ClassSettings
import meshlink.routingsim.sim.Density
import meshlink.routingsim.sim.InstanceConfig
import meshlink.routingsim.sim.MobilityKind
import meshlink.routingsim.sim.PlatformMix
import meshlink.routingsim.sim.Protocol
import java.io.File

/** Stage F: ADR 0014/0015/0016/0018 costs, rate limit, energy and scan schedules (see README "Stage F"). */
object StageFPlan {
    /** Stage E v1 with the 5 min rotation the ADRs settled on (±1 epoch acceptance). */
    const val ROTATION = "300"
    const val CALIB = "meas+cap3+hide+pq"
    const val V1U = "F+K2+T8+I12"
    const val V1B = "CU+T8+I12"
    const val LIFE_MAIN = 1_800_000L
    const val LIFE_LONG = 3_600_000L
    const val DURATION = 1_800_000L
    const val DURATION_LONG = 3_600_000L

    /** Interim scan schedule (on-off seconds) for low, medium, high. */
    const val INTERIM = "4-4_4-4_4-8"
    val schedules = listOf("4-2", "4-4", "4-8", "4-12", "4-16")

    /** Cumulative arm flags (unicast). */
    val arms =
        linkedMapOf(
            "A0" to "",
            "A1" to "H",
            "A2" to "H+CV",
            "A3" to "H+CV+LK",
            "A4" to "H+CV+LK+LG",
            "A2L" to "H+CVL",
            "A4L" to "H+CVL+LK+LG",
            "A3D" to "H+CV+LK+LD",
            "A4D" to "H+CV+LK+LG+LD",
        )

    /** The final arm used by the long, rate-limit, sweep, confirmation and background sets. */
    var finalArm = System.getProperty("rsim.final") ?: "A4"

    /** Stage F2 (owner amendment): the final arm is A4 with relays forwarding only the first lookup reply. */
    const val FINAL_F2 = "A4D"

    private fun join(vararg parts: String) = parts.filter { it.isNotEmpty() }.joinToString("+")

    private fun pair(
        arm: String,
        group: String,
        life: Long,
        extra: String = "",
    ): List<InstanceConfig> {
        val flags = arms.getValue(arm)
        val hold = if ("H" in flags.split('+')) "H" else ""
        return listOf(
            InstanceConfig(Protocol.UA, life, rotation = true, variant = join(V1U, flags, extra, "G$group")),
            InstanceConfig(Protocol.BA, life, rotation = true, variant = join(V1B, hold, "G$group")),
        )
    }

    fun instances(spec: CSpec): List<InstanceConfig> {
        val life = if (spec.scenario.durationMs > DURATION) LIFE_LONG else LIFE_MAIN
        val u1 = InstanceConfig(Protocol.U1, life, rotation = true)
        val b1 = InstanceConfig(Protocol.B1, life, rotation = true)
        val fin = finalArm
        return when (spec.set) {
            CSet.F_MAIN -> listOf(u1, b1) + listOf("A0", "A1", "A2", "A3", "A4").flatMap { pair(it, it, life) }
            CSet.F_LONG, CSet.F_SWEEP, CSet.F_CONF, CSet.F_BG -> listOf(u1, b1) + pair("A0", "A0", life) + pair(fin, fin, life)
            CSet.F_RL ->
                listOf(u1, b1) + pair("A0", "A0", life) + pair(fin, fin, life) + pair(fin, "RL0", life, "ATK") +
                    pair(fin, "RL1", life, "RL+ATK") + pair(fin, "RL1N", life, "RL")
            CSet.F_A2L -> listOf(u1, b1) + listOf("A0", "A1", "A2", "A2L").flatMap { pair(it, it, life) }
            CSet.F_DIAG -> listOf(u1, b1) + listOf("A0", "A3", "A3D", "A4D").flatMap { pair(it, it, life) }
            // F2: A0 and the final arm first, so their instance streams match the long, sweep, confirmation and background sets.
            CSet.F2_MAIN -> listOf(u1, b1) + listOf("A0", FINAL_F2, "A1", "A2", "A3", "A4", "A3D").flatMap { pair(it, it, life) }
            CSet.F2_LONG, CSet.F2_SWEEP, CSet.F2_CONF, CSet.F2_BG -> listOf(u1, b1) + pair("A0", "A0", life) + pair(FINAL_F2, FINAL_F2, life)
            else -> emptyList()
        }
    }

    /** Applies a schedule label ("on-off" seconds per class, "_"-separated) to the class settings. */
    fun withSchedule(
        st: ClassSettings,
        label: String,
    ): ClassSettings {
        val parts = label.split('_').map { p -> p.split('-').map { it.toInt() / 2 } }
        require(parts.size == 3) { "schedule needs low_medium_high: $label" }
        return ClassSettings(
            st.label, st.degree, st.scanEvery, st.fastAdvertising, st.bridgeGapMs,
            scanOnTicks = IntArray(3) { parts[it][0] }, scanOffTicks = IntArray(3) { parts[it][1] },
        )
    }

    /** The interim schedule with class [c] replaced by [s]. */
    fun swap(
        base: String,
        c: Int,
        s: String,
    ) = base.split('_').toMutableList().also { it[c] = s }.joinToString("_")

    private fun sc(
        d: Density,
        m: MobilityKind,
        p: PlatformMix,
        dur: Long = DURATION,
    ) = StageCPlan.scenario(d, 200, m, p, 5, dur)

    val cells =
        PlatformMix.entries.flatMap { p ->
            listOf(
                sc(Density.LOW, MobilityKind.STATIC, p),
                sc(Density.MEDIUM, MobilityKind.STATIC, p),
                sc(Density.HIGH, MobilityKind.STATIC, p),
                sc(Density.MEDIUM, MobilityKind.RANDOM_WAYPOINT, p),
                sc(Density.DYNAMIC, MobilityKind.ARRIVAL, p),
            )
        }

    private const val GAP = 60_000L

    private fun spec(
        s: meshlink.routingsim.sim.Scenario,
        seed: Int,
        set: CSet,
        sched: String? = null,
        calib: String = CALIB,
    ) = CSpec(s, Topo.ADAPT, seed, set, GAP, calib = calib, rot = ROTATION, sched = sched ?: INTERIM)

    fun main(
        seeds: Int,
        set: CSet = CSet.F_MAIN,
    ) = cells.flatMap { s -> (1..seeds).map { spec(s, it, set) } }

    fun long(
        seeds: Int,
        set: CSet = CSet.F_LONG,
    ) = PlatformMix.entries.flatMap { p ->
        listOf(Density.MEDIUM, Density.HIGH).flatMap { d -> (1..seeds).map { spec(sc(d, MobilityKind.STATIC, p, DURATION_LONG), it, set) } }
    }

    fun rl(seeds: Int) =
        listOf(Density.MEDIUM, Density.HIGH).flatMap { d -> (1..seeds).map { spec(sc(d, MobilityKind.STATIC, PlatformMix.ANDROID), it, CSet.F_RL) } }

    /** Fixed-density cells per swept class: 0 low static, 1 medium static and walking, 2 high static. */
    fun sweepCells(c: Int) =
        PlatformMix.entries.flatMap { p ->
            when (c) {
                0 -> listOf(sc(Density.LOW, MobilityKind.STATIC, p))
                1 -> listOf(sc(Density.MEDIUM, MobilityKind.STATIC, p), sc(Density.MEDIUM, MobilityKind.RANDOM_WAYPOINT, p))
                else -> listOf(sc(Density.HIGH, MobilityKind.STATIC, p))
            }
        }

    /** Every non-interim schedule per class (the interim point is the main set's run of the same cell and seed). */
    fun sweep(
        seeds: Int,
        classes: List<Int> = listOf(0, 1, 2),
        set: CSet = CSet.F_SWEEP,
    ): List<CSpec> {
        val interim = INTERIM.split('_')
        return classes.flatMap { c ->
            schedules.filter { it != interim[c] }.flatMap { s ->
                sweepCells(c).flatMap { cell -> (1..seeds).map { spec(cell, it, set, swap(INTERIM, c, s)) } }
            }
        }
    }

    fun conf(
        seeds: Int,
        scheds: List<String>,
        set: CSet = CSet.F_CONF,
    ) = scheds.flatMap { sch ->
        PlatformMix.entries.flatMap { p ->
            listOf(sc(Density.MEDIUM, MobilityKind.RANDOM_WAYPOINT, p), sc(Density.DYNAMIC, MobilityKind.ARRIVAL, p)).flatMap { cell ->
                (1..seeds).map { spec(cell, it, set, sch) }
            }
        }
    }

    fun bg(
        seeds: Int,
        sched: String,
        set: CSet = CSet.F_BG,
    ) = PlatformMix.entries.flatMap { p ->
        listOf(sc(Density.MEDIUM, MobilityKind.STATIC, p), sc(Density.DYNAMIC, MobilityKind.ARRIVAL, p)).flatMap { cell ->
            (1..seeds).map { spec(cell, it, set, sched, "$CALIB+bg50") }
        }
    }

    fun a2l(seeds: Int) = main(seeds).map { it.copy(set = CSet.F_A2L) }

    /** Not pre-registered: lookup replies deduplicated at relays (diagnostic of criterion 3 in A3/A4). */
    fun diag(seeds: Int) = main(seeds).map { it.copy(set = CSet.F_DIAG) }
}

/**
 * CLI: f-pilot [--set=..] [--seeds=1] [--only=..] | f-run --set=main|long|rl|sweep|conf|bg|a2l [--seeds=5]
 * [--classes=0,1,2] [--sched=..,..] [--cells=..] | f-aggregate | f-report | f2-report | f-check --only=..
 * Stage F2 sets: main2, long2, sweep2, conf2, bg2 (final arm A4D).
 */
fun stageF(
    cmd: String,
    args: List<String>,
    outDir: File,
) {
    fun opt(name: String) = args.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=')
    opt("final")?.let { StageFPlan.finalArm = it }
    val threads = opt("threads")?.toInt() ?: 4
    val runner = StageCRunner(BridgeChoice(60_000L, longArrayOf(60_000L, 60_000L, 60_000L)))
    fun plan(
        sets: List<String>,
        seeds: Int,
    ): List<CSpec> {
        val out = ArrayList<CSpec>()
        val scheds = opt("sched")?.split(',') ?: listOf(StageFPlan.INTERIM)
        if ("main" in sets) out += StageFPlan.main(seeds)
        if ("long" in sets) out += StageFPlan.long(seeds)
        if ("rl" in sets) out += StageFPlan.rl(seeds)
        if ("sweep" in sets) out += StageFPlan.sweep(seeds, opt("classes")?.split(',')?.map { it.toInt() } ?: listOf(0, 1, 2))
        if ("conf" in sets) out += StageFPlan.conf(seeds, scheds)
        if ("bg" in sets) out += StageFPlan.bg(seeds, scheds.first())
        if ("a2l" in sets) out += StageFPlan.a2l(seeds)
        if ("diag" in sets) out += StageFPlan.diag(seeds)
        if ("main2" in sets) out += StageFPlan.main(seeds, CSet.F2_MAIN)
        if ("long2" in sets) out += StageFPlan.long(seeds, CSet.F2_LONG)
        if ("sweep2" in sets) out += StageFPlan.sweep(seeds, opt("classes")?.split(',')?.map { it.toInt() } ?: listOf(0, 1, 2), CSet.F2_SWEEP)
        if ("conf2" in sets) out += StageFPlan.conf(seeds, scheds, CSet.F2_CONF)
        if ("bg2" in sets) out += StageFPlan.bg(seeds, scheds.first(), CSet.F2_BG)
        val must = opt("only")?.split('&')?.map { it.split(';') } ?: emptyList()
        return out.filter { s -> must.all { alt -> alt.any { s.id.contains(it) } } }
    }
    val sets = (opt("set") ?: "main").split(',')
    when (cmd) {
        "f-pilot" -> {
            val pick = plan(sets, opt("seeds")?.toInt() ?: 1)
            val dir = File(outDir, "pilot")
            val t0 = System.nanoTime()
            runner.runAll(pick, dir, threads)
            println("pilot wall %.0f s for ${pick.size} runs".format((System.nanoTime() - t0) / 1e9))
            val rows = (dir.listFiles() ?: emptyArray()).filter { it.name.startsWith("raw-") }.flatMap { RawCsv.read(it) }
            rows.groupBy { it["runId"] }.forEach { (id, r) -> println("  ${r.first()["wallMs"]} ms  $id") }
        }
        "f-run" -> {
            val t0 = System.nanoTime()
            val specs = plan(sets, opt("seeds")?.toInt() ?: 5)
            runner.runAll(specs, outDir, threads)
            println("f-run ${sets.joinToString(",")}: wall %.0f s for ${specs.size} runs".format((System.nanoTime() - t0) / 1e9))
        }
        "f-check" -> {
            // Sanity checks: one run, print the stage F counters per instance.
            val spec = plan(sets, opt("seeds")?.toInt() ?: 1).first()
            println("check ${spec.id}")
            val (r, wall) = runner.runOne(spec)
            println("wall $wall ms; joinP95S ${r.metrics["joinP95S"]}")
            val keys = (opt("keys") ?: "delivery,del_low,del_medium,del_high,grpIdleCtrlShare,grpLoadMaxNoBulkBps,firstP95ClassicalMs,firstP95PqMs").split(',')
            for ((cfg, m) in r.instances) {
                println(cfg.label)
                println("  " + keys.joinToString("  ") { "$it=${m[it]?.let { v -> "%.4g".format(v) }}" })
                println("  " + m.filterKeys { it.startsWith("c_") || it.startsWith("hold") || it.startsWith("lookup") || it == "convTagsNew" }
                    .entries.joinToString("  ") { "${it.key}=${"%.4g".format(it.value)}" })
            }
        }
        "f-aggregate" -> {
            for (set in CSet.entries.filter { it.stageF }) {
                val f = CRaw.file(outDir, set)
                if (!f.exists()) continue
                val rows = CAggregate.aggregate(RawCsv.read(f))
                CAggregate.write(rows, File(outDir, "agg-${set.label}.csv"))
                println("${set.label}: ${rows.size} aggregated rows")
            }
        }
        "f-report" -> stageFReport(outDir)
        "f2-report" -> stageF2Report(outDir)
        else -> error("unknown $cmd")
    }
}

