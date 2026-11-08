package meshlink.routingsim.runner

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
import meshlink.routingsim.sim.SybilSetup
import meshlink.routingsim.sim.TrafficConfig
import meshlink.routingsim.strategy.Clock
import meshlink.routingsim.strategy.ControlMessage
import meshlink.routingsim.strategy.DiverseViewSelection
import meshlink.routingsim.strategy.LinkPort
import meshlink.routingsim.strategy.LinkTable
import meshlink.routingsim.strategy.NeighborListMessage
import meshlink.routingsim.strategy.NodeContext
import meshlink.routingsim.strategy.Randomness
import meshlink.routingsim.strategy.SelectionParams
import meshlink.routingsim.strategy.SybilDefense
import meshlink.routingsim.strategy.d1Victim
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Stage S defense arm: which of D1–D3 honest phones run. */
enum class SArm(
    val label: String,
    val d1: Boolean,
    val d2: Boolean,
    val d3: Boolean,
) {
    BASE("base", false, false, false),
    D1("D1", true, false, false),
    D2("D2", false, true, false),
    D3("D3", false, false, true),
    D12("D1+D2", true, true, false),
    D13("D1+D3", true, false, true),
    D23("D2+D3", false, true, true),
    D123("D1+D2+D3", true, true, true),
    ;

    companion object {
        fun of(label: String) = entries.first { it.label == label }

        val minimum = listOf(BASE, D1, D2, D3, D123)
    }
}

/** Stage S attack cell. [fakes] = exact attacker radios (200 honest + fakes); otherwise [share] of N = 200 are fakes. */
enum class SAttack(
    val label: String,
    val share: Double,
    val fakes: Int?,
    val smart: Boolean,
) {
    NONE("none", 0.0, null, false),
    DUMB10("dumb10", 0.1, null, false),
    DUMB30("dumb30", 0.3, null, false),
    SMART10("smart10", 0.1, null, true),
    SMART30("smart30", 0.3, null, true),
    FEW_DUMB("few3-dumb", 0.0, 3, false),
    FEW_SMART("few3-smart", 0.0, 3, true),
    ;

    companion object {
        fun of(label: String) = entries.first { it.label == label }
    }
}

data class SSpec(
    val arm: SArm,
    val attack: SAttack,
    val mobility: MobilityKind,
    val seed: Int,
) {
    val id get() = "${arm.label}|${attack.label}|${mobility.label}|$seed"
}

/** The stage S plan (see README "Stage S"). */
object StageSPlan {
    const val NODES = 200
    const val FEW_BUDGET = 20
    val mobilities = listOf(MobilityKind.STATIC, MobilityKind.RANDOM_WAYPOINT)

    fun scenario(
        attack: SAttack,
        m: MobilityKind,
    ): Scenario {
        val fakes = attack.fakes
        return Scenario(
            Density.MEDIUM, NODES + (fakes ?: 0), m, PlatformMix.ANDROID, 5,
            sybilShare = attack.share, durationMs = StageCPlan.durationOf(m), sybilCount = fakes,
        )
    }

    /** Every attack cell × static and walking, plus a no-fakes partition-merge cell (for the repair gate). */
    fun specs(
        arms: List<SArm>,
        attacks: List<SAttack>,
        seeds: IntRange,
    ): List<SSpec> =
        arms.flatMap { arm ->
            val cells = attacks.flatMap { a -> mobilities.map { a to it } } +
                (if (SAttack.NONE in attacks) listOf(SAttack.NONE to MobilityKind.PARTITION_MERGE) else emptyList())
            cells.flatMap { (a, m) -> seeds.map { SSpec(arm, a, m, it) } }
        }
}

class StageSRunner {
    private val gap = 60_000L
    private val c = StageCRunner(BridgeChoice(gap, longArrayOf(gap, gap, gap)))
    private val base = Runner()
    private val instances =
        listOf(
            InstanceConfig(Protocol.UA, rotation = false, variant = StageDPlan.V1),
            InstanceConfig(Protocol.BA, rotation = false, variant = StageDPlan.FINAL_BROADCAST),
        )

    fun runOne(spec: SSpec): Pair<RunResult, Long> {
        val t0 = System.nanoTime()
        val s = StageSPlan.scenario(spec.attack, spec.mobility)
        // Shares count fakes within N (stage A convention); few radios add fakes to 200 honest phones in the same area.
        val extent = base.extentFor(s.copy(nodes = s.nodes - (s.sybilCount ?: 0), sybilCount = null))
        val sybil =
            SybilSetup(
                smart = spec.attack.smart, freshIdentity = true,
                d1 = spec.arm.d1, d2 = spec.arm.d2, d3 = spec.arm.d3, metrics = true,
            )
        var cal = Calibration.named(StageEPlan.CALIB).copy(sybil = sybil)
        if (spec.attack.fakes != null) cal = cal.copy(sybilBudget = StageSPlan.FEW_BUDGET)
        val r =
            Simulation(
                s, StrategyKind.N1N2_BRIDGE, spec.seed, cal, SelectionParams(bridgeMinGapMs = gap), extent,
                routingSetup = RoutingSetup(instances, TrafficConfig()),
                densitySetup = DensitySetup(settings = c.settingsFor(Topo.ADAPT)),
            ).run()
        return r to (System.nanoTime() - t0) / 1_000_000
    }

    fun runAll(
        specs: List<SSpec>,
        file: File,
        threads: Int,
    ) {
        val done = SRaw.completedIds(file)
        // Heaviest first: attack cells with many fakes and walking runs.
        val todo = specs.filter { it.id !in done }.sortedByDescending { (it.attack.share * 10).toInt() + if (it.mobility == MobilityKind.STATIC) 0 else 1 }
        println("runs: ${specs.size} total, ${specs.size - todo.size} already done, ${todo.size} to do, $threads threads")
        val queue = ArrayDeque(todo)
        val finished = AtomicInteger()
        val start = System.nanoTime()
        val workers =
            List(threads) {
                Thread {
                    while (true) {
                        val spec = synchronized(queue) { queue.removeFirstOrNull() } ?: break
                        try {
                            val (result, wall) = runOne(spec)
                            SRaw.append(file, spec, result, wall)
                            val k = finished.incrementAndGet()
                            if (k % 20 == 0 || k == todo.size) {
                                val el = (System.nanoTime() - start) / 1e9
                                println("  $k/${todo.size} done, %.0f s elapsed, eta %.0f s".format(el, el / k * (todo.size - k)))
                            }
                        } catch (e: Throwable) {
                            System.err.println("FAILED ${spec.id}: $e")
                            e.printStackTrace()
                        }
                    }
                }.also { it.start() }
            }
        workers.forEach { it.join() }
    }
}

/** Raw results: one row per run; topology metrics t_*, unicast (UA) u_*, broadcast (BA) b_*. */
object SRaw {
    val keyColumns = listOf("runId", "arm", "attack", "mobility", "seed", "nodes", "fakes")
    private var header: List<String>? = null

    fun completedIds(file: File): Set<String> =
        if (!file.exists()) emptySet() else file.readLines().drop(1).filter { it.isNotBlank() }.map { it.substringBefore(',') }.toSet()

    @Synchronized
    fun append(
        file: File,
        spec: SSpec,
        r: RunResult,
        wallMs: Long,
    ) {
        val values = LinkedHashMap<String, String>()
        values["runId"] = spec.id
        values["arm"] = spec.arm.label
        values["attack"] = spec.attack.label
        values["mobility"] = spec.mobility.label
        values["seed"] = spec.seed.toString()
        values["nodes"] = r.scenario.nodes.toString()
        values["fakes"] = RawCsv.fmt(r.metrics["sybilCount"] ?: Double.NaN)
        for ((k, v) in r.metrics) values["t_$k"] = RawCsv.fmt(v)
        for ((cfg, m) in r.instances) {
            val p = if (cfg.protocol.broadcast) "b_" else "u_"
            for ((k, v) in m) values["$p$k"] = RawCsv.fmt(v)
        }
        values["wallMs"] = wallMs.toString()
        val h =
            header ?: if (file.exists()) {
                file.bufferedReader().use { it.readLine() }.split(',')
            } else {
                file.parentFile.mkdirs()
                val h = values.keys.toList()
                file.writeText(h.joinToString(",") + "\n")
                h
            }
        header = h
        file.appendText(h.joinToString(",") { values[it] ?: "" } + "\n")
    }
}

/** Stub ports for the stage S self-test: [linked] peers are up, everything else is idle. */
private class StubNode(
    val self: Int,
    val budget: Int,
) : Clock,
    Randomness,
    LinkTable,
    LinkPort {
    var now = 0L
    val linked = LinkedHashMap<Int, Long>()
    private val rng = Random(1)

    override fun nowMillis() = now

    override fun nextDouble() = rng.nextDouble()

    override fun nextInt(bound: Int) = rng.nextInt(bound)

    override val degreeBudget get() = budget

    override fun linkCount() = linked.size

    override fun pendingCount() = 0

    override fun linkedPeers() = linked.keys.toIntArray()

    override fun isLinkedOrPending(peer: Int) = peer in linked

    override fun linkRssi(peer: Int) = -70.0

    override fun linkUpAt(peer: Int) = linked[peer] ?: now

    override fun lastActivityAt(peer: Int) = linkUpAt(peer)

    override fun dial(peer: Int) = false

    override fun disconnect(peer: Int) {}

    override fun send(
        peer: Int,
        message: ControlMessage,
    ) {}

    fun selection(defense: SybilDefense): DiverseViewSelection =
        DiverseViewSelection(NodeContext(self, this, this, this, this), SelectionParams(), bridge = true, defense = defense)
}

/** Focused checks of the defense rules (the project has no test source set). Exits non-zero on failure. */
fun stageSSelfTest() {
    val failures = ArrayList<String>()
    fun check(
        name: String,
        ok: Boolean,
    ) {
        println((if (ok) "PASS " else "FAIL ") + name)
        if (!ok) failures += name
    }

    // D3: neighbor 2 (honest) lists me and phone 3, which neighbor 4 also lists; fake 9 sends an empty list.
    for (d3 in listOf(false, true)) {
        val node = StubNode(1, 5)
        val sel = node.selection(SybilDefense(d3 = d3))
        for (p in listOf(2, 4, 9)) {
            node.linked[p] = 0
            sel.onLinkUp(p, inbound = false)
        }
        sel.onMessage(2, NeighborListMessage(intArrayOf(1, 3)))
        sel.onMessage(4, NeighborListMessage(intArrayOf(1, 3)))
        sel.onMessage(9, NeighborListMessage(IntArray(0)))
        val fake = sel.redundancyOf(9)
        val honest = sel.redundancyOf(2)
        if (d3) {
            check("D3: empty list is the most redundant link", fake > honest && fake > sel.redundancyOf(4))
        } else {
            check("D3 off: empty list looks maximally diverse (stage A behavior)", fake == 0 && honest > 0)
        }
    }
    // D3: an unconfirmed claim (phone 7, listed only by 5, never heard) does not count toward reach.
    run {
        val node = StubNode(1, 5)
        val sel = node.selection(SybilDefense(d3 = true))
        for (p in listOf(5, 6)) {
            node.linked[p] = 0
            sel.onLinkUp(p, inbound = false)
        }
        sel.onMessage(5, NeighborListMessage(intArrayOf(7)))
        sel.onMessage(6, NeighborListMessage(intArrayOf(1)))
        check("D3: neighbor with only unconfirmed entries is the most redundant", sel.redundancyOf(5) > 1_000)
    }

    // D2: two unproven inbound links fill probation; the third unproven inbound dial is refused.
    for (d2 in listOf(false, true)) {
        val node = StubNode(1, 6)
        val sel = node.selection(SybilDefense(d2 = d2))
        val before = sel.acceptsInbound(0)
        for (p in listOf(2, 3)) {
            node.linked[p] = 0
            sel.onLinkUp(p, inbound = true)
        }
        val third = sel.acceptsInbound(0)
        if (d2) {
            check("D2: accepts first unproven inbound", before)
            check("D2: refuses third unproven inbound", !third)
            check("D2: a pending inbound dial counts as unproven", !node.selection(SybilDefense(d2 = true)).acceptsInbound(2))
            sel.onUseful(2)
            check("D2: a credited inbound link is proven, freeing a probation slot", sel.acceptsInbound(0))
        } else {
            check("D2 off: accepts any inbound", third)
        }
    }

    // D1 rule.
    val d = SybilDefense(d1 = true)
    check("D1: evicts a useless link among useful ones", d1Victim(mapOf(1 to 0.1, 2 to 5.0, 3 to 4.0, 4 to 6.0), setOf(1, 2, 3, 4), d) == 1)
    check("D1: quiet period (median below evidence) evicts nothing", d1Victim(mapOf(1 to 0.0, 2 to 1.0, 3 to 1.5), setOf(1, 2, 3), d) == -1)
    check("D1: link inside the grace period is not judged", d1Victim(mapOf(1 to 0.0, 2 to 5.0, 3 to 4.0), setOf(2, 3), d) == -1)
    check("D1: a moderately useful link is kept", d1Victim(mapOf(1 to 2.0, 2 to 5.0, 3 to 4.0), setOf(1, 2, 3), d) == -1)
    if (failures.isNotEmpty()) {
        System.err.println("${failures.size} self-test failure(s)")
        kotlin.system.exitProcess(1)
    }
    println("all stage S self-tests passed")
}

/**
 * CLI: s-selftest | s-pilot [--arms=base,D1+D2+D3] [--threads=6] | s-run [--arms=all|min|a,b] [--seeds=10]
 * [--attacks=..] [--threads=6] | s-report. Output in --out (default results/stage-s).
 */
fun stageS(
    cmd: String,
    args: List<String>,
    outDir: File,
) {
    fun opt(name: String) = args.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=')
    val threads = opt("threads")?.toInt() ?: 6
    val arms =
        when (val a = opt("arms") ?: (if (cmd == "s-pilot") "base,D1+D2+D3" else "all")) {
            "all" -> SArm.entries.toList()
            "min" -> SArm.minimum
            else -> a.split(',').map { SArm.of(it) }
        }
    val attacks = opt("attacks")?.split(',')?.map { SAttack.of(it) } ?: SAttack.entries.toList()
    val runner = StageSRunner()
    when (cmd) {
        "s-selftest" -> stageSSelfTest()
        "s-pilot" -> {
            val dir = File(outDir, "pilot")
            val t0 = System.nanoTime()
            val specs = StageSPlan.specs(arms, attacks, 1..1)
            runner.runAll(specs, File(dir, "raw-s.csv"), threads)
            val wall = (System.nanoTime() - t0) / 1e9
            println("pilot wall %.0f s for ${specs.size} runs".format(wall))
            val rows = RawCsv.read(File(dir, "raw-s.csv"))
            val cpu = rows.sumOf { it["wallMs"]!!.toDouble() } / 1000
            println("pilot run time sum %.0f s, mean %.1f s/run".format(cpu, cpu / rows.size))
            for (r in rows.sortedBy { it["runId"] }) {
                println(
                    "  %6s ms  %-32s lcc=%s slot=%s uni=%s bcast=%s".format(
                        r["wallMs"], r["runId"], r["t_lcc"], r["t_sybilSlotShare"], r["u_deliveryAll"], r["b_arrivalAllHonest"],
                    ),
                )
            }
        }
        "s-run" -> runner.runAll(StageSPlan.specs(arms, attacks, 1..(opt("seeds")?.toInt() ?: 10)), File(outDir, "raw-s.csv"), threads)
        "s-report" -> stageSReport(outDir)
        else -> error("unknown $cmd")
    }
}
