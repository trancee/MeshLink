package meshlink.routingsim.runner

import meshlink.routingsim.sim.Density
import meshlink.routingsim.sim.InstanceConfig
import meshlink.routingsim.sim.MobilityKind
import meshlink.routingsim.sim.PlatformMix
import meshlink.routingsim.sim.Protocol
import meshlink.routingsim.sim.TrafficConfig
import java.io.File

/** Stage D: gap-closing follow-up on the stage C v1 configuration (see README "Stage D"). */
object StageDPlan {
    /**
     * The stage D unicast candidate: sparse sender floods (S5), full-range discovery (F) and conversation keep-alive
     * (K1). Its broadcast partner is [FINAL_BROADCAST].
     */
    const val FINAL = "S5+F+K1"

    /**
     * The recommended v1 unicast after the main grid: full-range discovery plus delivery-ack refresh. Chosen over
     * [FINAL] because S5 showed no measurable effect and K2 passed more cells at similar load (see README "Stage D").
     */
    const val V1 = "F+K2"

    /** The stage D broadcast candidate: adaptive B1/B3 with catch-up on link-up. */
    const val FINAL_BROADCAST = "CU"

    /** Conversation traffic: the recipient answers with probability 0.6 after 30 s on average. */
    val conversation = TrafficConfig(replyProb = 0.6, replyMeanDelayMs = 30_000.0)

    private fun ua(variant: String = "") = InstanceConfig(Protocol.UA, variant = variant)

    private fun ba(variant: String = "") = InstanceConfig(Protocol.BA, variant = variant)

    private val u1 = InstanceConfig(Protocol.U1)
    private val b1 = InstanceConfig(Protocol.B1)

    /** Static cells in the main set run fewer seeds than dynamic ones (budget cut, see README). */
    const val STATIC_SEEDS = 5

    fun instances(set: CSet): List<InstanceConfig> =
        when (set) {
            CSet.D_MAIN ->
                System.getProperty("rsim.dprobe")?.let { probe -> listOf(u1) + probe.split(',').map { ua(it) } } ?: listOf(u1, ua()) +
                    listOf("K1", "K2", "K3", "K1+K2+K3", "S5", "F", "F+K1", "F+K2", "F+K3", "F+K1+K2+K3", FINAL).map { ua(it) } +
                    listOf(b1, ba(), ba("FG"), ba(FINAL_BROADCAST))
            CSet.D_CONV -> listOf(u1, ua()) + listOf("K3", "F", "F+K1", "F+K3", "F+K1+K2+K3", FINAL).map { ua(it) }
            CSet.D_BIG -> listOf(ua(FINAL), b1, ba(), ba("FG"), ba(FINAL_BROADCAST))
            CSet.D_LIFE ->
                listOf("", "S5", FINAL).flatMap { v ->
                    listOf(10_000L, 60_000L).map { hold -> InstanceConfig(Protocol.UA, 120_000L, hold, 30_000L, rotation = true, variant = v) }
                } + listOf(10_000L, 60_000L).map { hold -> InstanceConfig(Protocol.U1, 120_000L, hold, 30_000L, rotation = true) }
            CSet.D_PIN -> listOf(ua("PIN"), b1, ba("PIN+CU"))
            CSet.D_LIFE_V1 ->
                listOf(InstanceConfig(Protocol.U1), ua("F"), ua(V1)).flatMap { c ->
                    listOf(10_000L, 60_000L).map { hold -> c.copy(lifetimeMs = 120_000L, minHoldMs = hold, skewMs = 30_000L, rotation = true) }
                }
            CSet.D_BIG_V1 -> listOf(ua(), ua(V1))
            CSet.D_IOS4, CSet.D_IOS3 -> listOf(u1, ua(V1), b1, ba(FINAL_BROADCAST))
            else -> emptyList()
        }

    private fun dynamic(
        topo: Topo,
        set: CSet,
        seeds: Int,
        gap: Long,
    ) = StageCPlan.dynamicScenarios.flatMap { (d, m) ->
        PlatformMix.entries.flatMap { p -> (1..seeds).map { CSpec(StageCPlan.scenario(d, 200, m, p, 5), topo, it, set, gap) } }
    }

    private fun fixedDensity(
        combos: List<Pair<Density, Int>>,
        set: CSet,
        seeds: Int,
        gap: Long,
    ) = combos.flatMap { (d, n) ->
        StageCPlan.staticMobility.flatMap { m ->
            PlatformMix.entries.flatMap { p -> (1..seeds).map { CSpec(StageCPlan.scenario(d, n, m, p, 5), Topo.ADAPT, it, set, gap) } }
        }
    }

    fun main(
        gap: Long,
        seeds: Int = 10,
    ) = dynamic(Topo.ADAPT, CSet.D_MAIN, seeds, gap) + fixedDensity(StageCPlan.staticCombos, CSet.D_MAIN, minOf(seeds, STATIC_SEEDS), gap)

    fun conv(
        gap: Long,
        seeds: Int = 5,
    ): List<CSpec> {
        val dyn =
            listOf(MobilityKind.ARRIVAL, MobilityKind.CORE_EDGE).flatMap { m ->
                PlatformMix.entries.flatMap { p ->
                    (1..seeds).map { CSpec(StageCPlan.scenario(Density.DYNAMIC, 200, m, p, 5), Topo.ADAPT, it, CSet.D_CONV, gap) }
                }
            }
        return fixedDensity(listOf(Density.MEDIUM to 200, Density.HIGH to 200), CSet.D_CONV, seeds, gap) + dyn
    }

    fun big(
        gap: Long,
        seeds: Int = 5,
    ) = (1..seeds).map {
        CSpec(StageCPlan.scenario(Density.DYNAMIC, 1000, MobilityKind.ARRIVAL, PlatformMix.HALF_IOS, 5), Topo.ADAPT, it, CSet.D_BIG, gap)
    }

    fun life(
        gap: Long,
        seeds: Int = 10,
    ) = StageCPlan.life(gap, seeds).map { it.copy(set = CSet.D_LIFE) }

    fun lifeV1(
        gap: Long,
        seeds: Int = 10,
    ) = StageCPlan.life(gap, seeds).map { it.copy(set = CSet.D_LIFE_V1) }

    fun bigV1(
        gap: Long,
        seeds: Int = 5,
    ) = big(gap, seeds).map { it.copy(set = CSet.D_BIG_V1) }

    /** Calibrations compared in the iOS-cap recheck (see [meshlink.routingsim.sim.Calibration.named]). */
    val ios4Calibs = listOf("base", "base+cap4", "meas", "meas+cap4", "meas+cap4+hide")

    /** iOS-cap recheck: medium and high static cells plus the arrival scenario, half-iOS, N = 200 (and medium N = 50). */
    /** Cap-3 recheck: an iPhone at 3 inbound links is undiscoverable (bake-off: iPhone X invisible at 4). */
    val ios3Calibs = listOf("base+cap3+hide", "meas+cap3+hide")

    fun ios3(
        gap: Long,
        seeds: Int = 5,
    ) = ios4(gap, seeds, ios3Calibs, CSet.D_IOS3)

    fun ios4(
        gap: Long,
        seeds: Int = 3,
        calibs: List<String> = ios4Calibs,
        set: CSet = CSet.D_IOS4,
    ): List<CSpec> {
        val cells =
            listOf(
                StageCPlan.scenario(Density.MEDIUM, 50, MobilityKind.STATIC, PlatformMix.HALF_IOS, 5),
                StageCPlan.scenario(Density.MEDIUM, 200, MobilityKind.STATIC, PlatformMix.HALF_IOS, 5),
                StageCPlan.scenario(Density.HIGH, 200, MobilityKind.STATIC, PlatformMix.HALF_IOS, 5),
                StageCPlan.scenario(Density.DYNAMIC, 200, MobilityKind.ARRIVAL, PlatformMix.HALF_IOS, 5),
            )
        return calibs.flatMap { c -> cells.flatMap { sc -> (1..seeds).map { CSpec(sc, Topo.ADAPT, it, set, gap, calib = c) } } }
    }

    fun pin(
        gap: Long,
        seeds: Int = 10,
    ) = dynamic(Topo.PINNED, CSet.D_PIN, seeds, gap)
}

fun stageDInstances(set: CSet) = StageDPlan.instances(set)

fun stageDTraffic(set: CSet) = if (set == CSet.D_CONV) StageDPlan.conversation else TrafficConfig()

/** CLI: d-pilot [--only=..] | d-run [--set=main,conv,big,life,pin,life-v1,big-v1] [--seeds=n] | d-aggregate | d-report. */
fun stageD(
    cmd: String,
    args: List<String>,
    outDir: File,
) {
    fun opt(name: String) = args.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=')
    val threads = opt("threads")?.toInt() ?: 4
    val gap = 60_000L
    val runner = StageCRunner(BridgeChoice(gap, longArrayOf(gap, gap, gap)))
    fun plan(sets: List<String>, seeds: Int?): List<CSpec> {
        val out = ArrayList<CSpec>()
        if ("main" in sets) out += StageDPlan.main(gap, seeds ?: 10)
        if ("conv" in sets) out += StageDPlan.conv(gap, seeds ?: 3)
        if ("big" in sets) out += StageDPlan.big(gap, seeds ?: 5)
        if ("life" in sets) out += StageDPlan.life(gap, seeds ?: 10)
        if ("pin" in sets) out += StageDPlan.pin(gap, seeds ?: 10)
        if ("life-v1" in sets) out += StageDPlan.lifeV1(gap, seeds ?: 10)
        if ("big-v1" in sets) out += StageDPlan.bigV1(gap, seeds ?: 5)
        if ("ios4" in sets) out += StageDPlan.ios4(gap, seeds ?: 3)
        if ("ios3" in sets) out += StageDPlan.ios3(gap, seeds ?: 5)
        return out
    }
    val sets = (opt("set") ?: "main,conv,big,life,pin").split(',')
    when (cmd) {
        "d-pilot" -> {
            val must = opt("only")?.split('&')?.map { it.split(';') } ?: emptyList()
            val pick = plan(sets, opt("seeds")?.toInt() ?: 1).filter { s -> must.all { alt -> alt.any { s.id.contains(it) } } }
            val dir = File(outDir, "pilot")
            val t0 = System.nanoTime()
            runner.runAll(pick, dir, threads)
            println("pilot wall %.0f s for ${pick.size} runs".format((System.nanoTime() - t0) / 1e9))
            val rows = (dir.listFiles() ?: emptyArray()).filter { it.name.startsWith("raw-") }.flatMap { RawCsv.read(it) }
            rows.groupBy { it["runId"] }.forEach { (id, r) -> println("  ${r.first()["wallMs"]} ms  $id") }
        }
        "d-run" -> runner.runAll(plan(sets, opt("seeds")?.toInt()), outDir, threads)
        "d-aggregate" -> {
            for (set in CSet.entries) {
                val f = CRaw.file(outDir, set)
                if (!f.exists()) continue
                val rows = CAggregate.aggregate(RawCsv.read(f))
                CAggregate.write(rows, File(outDir, "agg-${set.label}.csv"))
                println("${set.label}: ${rows.size} aggregated rows")
            }
        }
        "d-report" -> stageDReport(outDir)
        "d-ios3-report" -> stageDIos3Report(outDir, File(opt("cap4") ?: error("--cap4=DIR with raw-d-ios4.csv")))
        else -> error("unknown $cmd")
    }
}
