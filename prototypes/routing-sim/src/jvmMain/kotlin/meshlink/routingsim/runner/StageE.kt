package meshlink.routingsim.runner

import meshlink.routingsim.sim.Density
import meshlink.routingsim.sim.InstanceConfig
import meshlink.routingsim.sim.MobilityKind
import meshlink.routingsim.sim.PlatformMix
import meshlink.routingsim.sim.Protocol
import java.io.File

/** Stage E identifier rotation: [periodMs] null = never; [aligned] = every phone rotates at the same instant. */
class StageERotation(
    val label: String,
    val periodMs: Long?,
    val aligned: Boolean,
) {
    companion object {
        /** Stands in for "never" where a period is required (far beyond any run). */
        const val NEVER_MS = 1_000_000_000_000L

        fun parse(label: String): StageERotation =
            when {
                label == "none" -> StageERotation(label, null, false)
                label.endsWith("a") -> StageERotation(label, label.dropLast(1).toLong() * 1000, true)
                else -> StageERotation(label, label.toLong() * 1000, false)
            }

        /** Shadow estimators in every stage E run: the over-count each rotation period would cause. */
        val shadows: List<Pair<String, Long?>> =
            listOf("none" to null, "60" to 60_000L, "300" to 300_000L, "900" to 900_000L, "3600" to 3_600_000L, "300a" to 300_000L)
    }
}

/** Stage E: security costs of the final v1 configuration (see README "Stage E"). */
object StageEPlan {
    /** v1: identifiers and routing tags rotate every 15 min, each phone at its own phase. */
    const val V1_ROTATION = "900"

    /** Final v1: stage D v1 with the iOS inbound cap of 3 (undiscoverable at the cap), bake-off calibration, XXhfs bytes. */
    const val CALIB = "meas+cap3+hide+pq"

    /** The same with a classical Noise XX link handshake. */
    const val CALIB_XX = "meas+cap3+hide+xx"

    val rotations = listOf("none", "60", "300", "3600", "300a")

    /** Fragment sizes: ATT payload at MTU 185 and 517. */
    val fragmentBytes = listOf(182, 514)
    val fragmentCounts = listOf(1, 4, 16)

    private fun ua(
        variant: String,
        rotation: Boolean = true,
    ) = InstanceConfig(Protocol.UA, rotation = rotation, variant = variant)

    private fun ba(
        variant: String,
        rotation: Boolean = true,
    ) = InstanceConfig(Protocol.BA, rotation = rotation, variant = variant)

    fun instances(
        set: CSet,
        rot: StageERotation,
    ): List<InstanceConfig> {
        val v1u = StageDPlan.V1
        val v1b = StageDPlan.FINAL_BROADCAST
        return when (set) {
            CSet.E_BASE -> {
                val sizes =
                    listOf(4, 8, 16).flatMap { t -> listOf(8, 12, 16).map { i -> t to i } }.filter { it != 8 to 16 }
                        .map { (t, i) -> ua("$v1u+T$t+I$i") } +
                        listOf("T8+I8", "T8+I12", "T4+I16", "T16+I16").map { ba("$v1b+$it") }
                val frags =
                    fragmentBytes.flatMap { f ->
                        fragmentCounts.flatMap { kf ->
                            val base = "M${kf}x$f"
                            val modes = if (kf > 1) listOf(base, "$base+CT") else listOf(base)
                            modes.flatMap { m -> listOf(ua("$v1u+$m"), ba("$v1b+$m")) }
                        }
                    }
                listOf(InstanceConfig(Protocol.U1, rotation = true), ua(v1u), ua(v1u, rotation = false), InstanceConfig(Protocol.B1, rotation = true), ba(v1b)) +
                    sizes + frags
            }
            CSet.E_ROT ->
                if (rot.periodMs == null) {
                    listOf(ua(v1u, rotation = false), ba(v1b, rotation = false))
                } else {
                    listOf(ua(v1u), ua(v1u, rotation = false), ba(v1b))
                }
            CSet.E_HS -> listOf(InstanceConfig(Protocol.U1, rotation = true), ua(v1u), InstanceConfig(Protocol.B1, rotation = true), ba(v1b))
            else -> emptyList()
        }
    }

    /** Low, medium and high static at N = 200, walking at medium, and the arrival scenario; both platform mixes. */
    val cells =
        PlatformMix.entries.flatMap { p ->
            listOf(
                StageCPlan.scenario(Density.LOW, 200, MobilityKind.STATIC, p, 5),
                StageCPlan.scenario(Density.MEDIUM, 200, MobilityKind.STATIC, p, 5),
                StageCPlan.scenario(Density.HIGH, 200, MobilityKind.STATIC, p, 5),
                StageCPlan.scenario(Density.MEDIUM, 200, MobilityKind.RANDOM_WAYPOINT, p, 5),
                StageCPlan.scenario(Density.DYNAMIC, 200, MobilityKind.ARRIVAL, p, 5),
            )
        }

    fun base(
        gap: Long,
        seeds: Int,
    ) = cells.flatMap { sc -> (1..seeds).map { CSpec(sc, Topo.ADAPT, it, CSet.E_BASE, gap, calib = CALIB, rot = V1_ROTATION) } }

    fun rot(
        gap: Long,
        seeds: Int,
    ) = rotations.flatMap { r -> cells.flatMap { sc -> (1..seeds).map { CSpec(sc, Topo.ADAPT, it, CSet.E_ROT, gap, calib = CALIB, rot = r) } } }

    fun hs(
        gap: Long,
        seeds: Int,
    ) = cells.flatMap { sc -> (1..seeds).map { CSpec(sc, Topo.ADAPT, it, CSet.E_HS, gap, calib = CALIB_XX, rot = V1_ROTATION) } }
}

/** CLI: e-pilot [--set=..] [--seeds=1] [--only=..] | e-run [--set=base,rot,hs] [--seeds=5] | e-aggregate | e-report. */
fun stageE(
    cmd: String,
    args: List<String>,
    outDir: File,
) {
    fun opt(name: String) = args.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=')
    val threads = opt("threads")?.toInt() ?: 4
    val gap = 60_000L
    val runner = StageCRunner(BridgeChoice(gap, longArrayOf(gap, gap, gap)))
    fun plan(
        sets: List<String>,
        seeds: Int,
    ): List<CSpec> {
        val out = ArrayList<CSpec>()
        if ("base" in sets) out += StageEPlan.base(gap, seeds)
        if ("rot" in sets) out += StageEPlan.rot(gap, seeds)
        if ("hs" in sets) out += StageEPlan.hs(gap, seeds)
        return out
    }
    val sets = (opt("set") ?: "base,rot,hs").split(',')
    when (cmd) {
        "e-pilot" -> {
            val must = opt("only")?.split('&')?.map { it.split(';') } ?: emptyList()
            val pick = plan(sets, opt("seeds")?.toInt() ?: 1).filter { s -> must.all { alt -> alt.any { s.id.contains(it) } } }
            val dir = File(outDir, "pilot")
            val t0 = System.nanoTime()
            runner.runAll(pick, dir, threads)
            println("pilot wall %.0f s for ${pick.size} runs".format((System.nanoTime() - t0) / 1e9))
            val rows = (dir.listFiles() ?: emptyArray()).filter { it.name.startsWith("raw-") }.flatMap { RawCsv.read(it) }
            rows.groupBy { it["runId"] }.forEach { (id, r) -> println("  ${r.first()["wallMs"]} ms  $id") }
        }
        "e-run" -> runner.runAll(plan(sets, opt("seeds")?.toInt() ?: 5), outDir, threads)
        "e-aggregate" -> {
            for (set in listOf(CSet.E_BASE, CSet.E_ROT, CSet.E_HS)) {
                val f = CRaw.file(outDir, set)
                if (!f.exists()) continue
                val rows = CAggregate.aggregate(RawCsv.read(f))
                CAggregate.write(rows, File(outDir, "agg-${set.label}.csv"))
                println("${set.label}: ${rows.size} aggregated rows")
            }
        }
        "e-report" -> stageEReport(outDir)
        else -> error("unknown $cmd")
    }
}
