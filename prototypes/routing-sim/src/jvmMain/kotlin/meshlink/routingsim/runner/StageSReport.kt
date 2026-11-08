package meshlink.routingsim.runner

import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/** One stage S result metric derived from a raw row. */
private class SMetric(
    val name: String,
    val title: String,
    val digits: Int,
    val of: (Map<String, String>) -> Double,
)

private fun Map<String, String>.d(k: String) = this[k]?.toDoubleOrNull() ?: Double.NaN

private val sMetrics =
    listOf(
        SMetric("lcc", "honest LCC", 3) { it.d("t_lcc") },
        SMetric("slot", "sybil slot share", 3) { it.d("t_sybilSlotShare") },
        SMetric("uni", "unicast delivery (all honest pairs)", 3) { it.d("u_deliveryAll") },
        SMetric("uniElig", "unicast delivery (same honest group)", 3) { it.d("u_delivery") },
        SMetric("bcast", "broadcast arrival (all honest phones)", 3) { it.d("b_arrivalAllHonest") },
        SMetric("bcastElig", "broadcast arrival (sender's honest group)", 3) { it.d("b_delivery") },
        SMetric("join", "join time, first link median (s)", 1) { it.d("t_joinMedianS") },
        SMetric("repair", "partition repair, LCC recovery (s)", 0) { it.d("t_mergeLccRecoverS") },
        SMetric("ctrl", "control share of link capacity (topology + routing)", 4) {
            it.d("t_controlShareOfCapacity") + it.d("u_ctrlShareHonestLinks") + it.d("b_ctrlShareHonestLinks")
        },
        SMetric("busiest", "busiest phone control share of its capacity", 4) { it.d("t_busiestCtrlShare") },
        SMetric("busiestBps", "busiest phone control (B/s)", 0) { it.d("t_busiestCtrlBps") },
        SMetric("churn", "honest link closes / phone / min", 3) { it.d("t_hhClosesPerNodeMin") },
        SMetric("downs", "honest link downs (any cause) / phone / min", 3) { it.d("t_hhDownsPerNodeMin") },
        SMetric("fakeCloses", "fake links closed by honest phones / phone / min", 3) { it.d("t_fakeClosesPerNodeMin") },
        SMetric("d2Fake", "D2 refusals of fake dials / phone / min", 3) { it.d("t_d2RefusedFakePerNodeMin") },
        SMetric("d2Honest", "D2 refusals of honest dials / phone / min", 3) { it.d("t_d2RefusedHonestPerNodeMin") },
        SMetric("wallS", "run wall time (s)", 1) { it.d("wallMs") / 1000 },
    )

private class SCell(
    val arm: String,
    val attack: String,
    val mobility: String,
    val n: Int,
    val mean: Map<String, Double>,
    val ci: Map<String, Double>,
)

private fun aggregateS(raw: List<Map<String, String>>): List<SCell> =
    raw.groupBy { Triple(it["arm"]!!, it["attack"]!!, it["mobility"]!!) }.map { (k, rows) ->
        val mean = HashMap<String, Double>()
        val ci = HashMap<String, Double>()
        for (m in sMetrics) {
            val xs = rows.map { m.of(it) }.filter { !it.isNaN() }
            if (xs.isEmpty()) {
                mean[m.name] = Double.NaN
                ci[m.name] = Double.NaN
                continue
            }
            val mu = xs.average()
            mean[m.name] = mu
            ci[m.name] =
                if (xs.size < 2) Double.NaN else Aggregate.tQuantile(xs.size - 1) * sqrt(xs.sumOf { (it - mu) * (it - mu) } / (xs.size - 1)) / sqrt(xs.size.toDouble())
        }
        SCell(k.first, k.second, k.third, rows.size, mean, ci)
    }

private fun fmt(
    v: Double,
    digits: Int,
) = if (v.isNaN()) "–" else String.format(Locale.ROOT, "%.${digits}f", v)

private fun pm(
    c: SCell?,
    m: SMetric,
): String {
    if (c == null) return "n/a"
    val mu = c.mean[m.name] ?: Double.NaN
    if (mu.isNaN()) return "–"
    val ci = c.ci[m.name] ?: Double.NaN
    return fmt(mu, m.digits) + if (ci.isNaN()) "" else " ± " + fmt(ci, m.digits)
}

/** Stage S report: aggregate CSV, markdown tables and the pre-registered pass criteria. */
fun stageSReport(dir: File) {
    val raw = RawCsv.read(File(dir, "raw-s.csv"))
    val cells = aggregateS(raw)
    val byKey = cells.associateBy { Triple(it.arm, it.attack, it.mobility) }
    val arms = SArm.entries.map { it.label }.filter { a -> cells.any { it.arm == a } }
    val attacks = SAttack.entries.map { it.label }
    val mobs = listOf("static", "waypoint")
    val mobName = mapOf("static" to "static", "waypoint" to "walking", "partition-merge" to "partition-merge")
    fun cell(
        arm: String,
        attack: String,
        mob: String,
    ) = byKey[Triple(arm, attack, mob)]
    val metric = sMetrics.associateBy { it.name }

    // Aggregate CSV.
    val csv = StringBuilder("arm,attack,mobility,runs," + sMetrics.joinToString(",") { "${it.name}_mean,${it.name}_ci95" } + "\n")
    for (a in arms) for (at in attacks) for (m in mobs + "partition-merge") {
        val c = cell(a, at, m) ?: continue
        csv.append("$a,$at,$m,${c.n},")
        csv.append(sMetrics.joinToString(",") { x -> listOf(c.mean[x.name], c.ci[x.name]).joinToString(",") { v -> if (v == null || v.isNaN()) "NaN" else String.format(Locale.ROOT, "%.6g", v) } })
        csv.append('\n')
    }
    File(dir, "agg-s.csv").writeText(csv.toString())

    val md = StringBuilder()
    md.append("# Stage S: Sybil resistance of the v1 configuration\n\n")
    md.append("Generated by `s-report` from `raw-s.csv` (${raw.size} runs). Cells: mean ± 95% CI over seeds (t-distribution).\n")
    md.append("Medium density, N = 200 honest-or-fake phones (shares count fakes within N; few-radios = 200 honest + 3 fakes), Android, 900 s, 120 s warm-up.\n\n")

    // Pass criteria.
    val base = "base"
    md.append("## Pass criteria\n\n")
    md.append("A defense passes if honest LCC ≥ 0.8 at dumb 30% and at smart 30% (static and walking), its no-fakes cells keep unicast delivery, ")
    md.append("broadcast arrival, join time (static, walking) and partition repair (partition-merge) within the baseline arm's 95% CI ")
    md.append("(|arm mean − baseline mean| ≤ baseline CI half-width), and mean control traffic stays ≤ 10% of link capacity in every cell. ")
    md.append("Smart-attacker delivery is reported, not gated.\n\n")
    md.append("| arm | LCC dumb30 static / walk | LCC smart30 static / walk | dumb30 | smart30 | no-fakes cost | max control share | verdict |\n")
    md.append("|---|---|---|---|---|---|---|---|\n")
    val verdicts = LinkedHashMap<String, Boolean>()
    for (a in arms) {
        fun lccOk(attack: String) =
            mobs.all { m -> (cell(a, attack, m)?.mean?.get("lcc") ?: Double.NaN).let { !it.isNaN() && it >= 0.8 } }
        fun lccs(attack: String) = mobs.joinToString(" / ") { m -> fmt(cell(a, attack, m)?.mean?.get("lcc") ?: Double.NaN, 3) }
        val dumbOk = lccOk("dumb30")
        val smartOk = lccOk("smart30")
        val costs = ArrayList<String>()
        var costOk = true
        val checks = mobs.flatMap { m -> listOf("uni", "bcast", "join").map { it to m } } + ("repair" to "partition-merge")
        for ((name, m) in checks) {
            val b = cell(base, "none", m)
            val x = cell(a, "none", m)
            val bm = b?.mean?.get(name) ?: Double.NaN
            val bc = b?.ci?.get(name) ?: Double.NaN
            val xm = x?.mean?.get(name) ?: Double.NaN
            if (bm.isNaN() || xm.isNaN() || bc.isNaN()) {
                costs += "$name ${mobName[m]} not measured"
                costOk = false
                continue
            }
            val dev = xm - bm
            if (abs(dev) > bc) {
                costOk = false
                val better = if (name == "join" || name == "repair") dev < 0 else dev > 0
                costs += "$name ${mobName[m]} ${fmt(xm, metric.getValue(name).digits)} vs ${fmt(bm, metric.getValue(name).digits)} ± ${fmt(bc, metric.getValue(name).digits)}" +
                    if (better) " (better)" else " (worse)"
            }
        }
        val maxCtrl = cells.filter { it.arm == a }.maxOfOrNull { it.mean["ctrl"] ?: Double.NaN } ?: Double.NaN
        val ctrlOk = !maxCtrl.isNaN() && maxCtrl <= 0.10
        val pass = dumbOk && smartOk && costOk && ctrlOk
        verdicts[a] = pass
        md.append("| $a | ${lccs("dumb30")} | ${lccs("smart30")} | ${if (dumbOk) "pass" else "FAIL"} | ${if (smartOk) "pass" else "FAIL"} | ")
        md.append(if (costs.isEmpty()) "within CI" else costs.joinToString("; "))
        md.append(" | ${fmt(maxCtrl, 4)}${if (ctrlOk) "" else " FAIL"} | ${if (pass) "**PASS**" else "FAIL"} |\n")
    }
    md.append("\n")

    // Main tables.
    val cols = listOf("lcc", "slot", "uni", "bcast", "join", "ctrl", "busiest", "churn", "fakeCloses")
    md.append("## Results by mobility\n\n")
    md.append("Columns: " + cols.joinToString("; ") { "**$it** = ${metric.getValue(it).title}" } + ".\n\n")
    for (m in mobs) {
        md.append("### ${mobName[m]}\n\n")
        md.append("| attack | arm | runs | " + cols.joinToString(" | ") + " |\n")
        md.append("|---|---|---|" + cols.joinToString("") { "---|" } + "\n")
        for (at in attacks) for (a in arms) {
            val c = cell(a, at, m) ?: continue
            md.append("| $at | $a | ${c.n} | " + cols.joinToString(" | ") { pm(c, metric.getValue(it)) } + " |\n")
        }
        md.append("\n")
    }
    md.append("### Partition repair (no fakes, partition-merge, medium, N = 200)\n\n")
    val rcols = listOf("repair", "lcc", "uni", "bcast", "join", "ctrl", "churn")
    md.append("| arm | runs | " + rcols.joinToString(" | ") + " |\n|---|---|" + rcols.joinToString("") { "---|" } + "\n")
    for (a in arms) {
        val c = cell(a, "none", "partition-merge") ?: continue
        md.append("| $a | ${c.n} | " + rcols.joinToString(" | ") { pm(c, metric.getValue(it)) } + " |\n")
    }
    md.append("\n## Secondary metrics\n\n")
    val scols = listOf("uniElig", "bcastElig", "downs", "d2Fake", "d2Honest", "busiestBps", "wallS")
    md.append("Columns: " + scols.joinToString("; ") { "**$it** = ${metric.getValue(it).title}" } + ".\n\n")
    for (m in mobs) {
        md.append("### ${mobName[m]}\n\n| attack | arm | " + scols.joinToString(" | ") + " |\n|---|---|" + scols.joinToString("") { "---|" } + "\n")
        for (at in attacks) for (a in arms) {
            val c = cell(a, at, m) ?: continue
            md.append("| $at | $a | " + scols.joinToString(" | ") { pm(c, metric.getValue(it)) } + " |\n")
        }
        md.append("\n")
    }
    val total = raw.sumOf { it.d("wallMs") } / 1000
    md.append("Total simulated run time (sum over runs): %.0f s.\n".format(total))
    File(dir, "stage-s-report.md").writeText(md.toString())
    println("wrote ${File(dir, "stage-s-report.md")} and agg-s.csv (${cells.size} cells)")
    for ((a, p) in verdicts) println("  $a: ${if (p) "PASS" else "FAIL"}")
}
