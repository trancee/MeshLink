package meshlink.routingsim.runner

import meshlink.routingsim.runner.HtmlReport.esc
import meshlink.routingsim.runner.HtmlReport.fmt
import meshlink.routingsim.runner.StageFReport.Run
import meshlink.routingsim.runner.StageFReport.cellOrder
import meshlink.routingsim.runner.StageFReport.cellTitle
import java.io.File
import java.util.Locale

/**
 * Stage F2 report: stage F re-evaluated under the owner's post-hoc amendments (absolute busiest-phone budget, minimum sample of
 * 100 messages per sub-population, A4D final arm), with busiest-phone and lookup-distance instrumentation. Inline CSS only.
 */
object StageF2Report {
    const val FIN = StageFPlan.FINAL_F2

    /** Amended criterion 3: busiest phone ≤ 10% of the slowest measured Android link, and ≤ A0's busiest-phone mW + 3 mW. */
    const val BUSY_BPS = 4_700.0
    const val BUSY_MW_DELTA = 3.0

    /** Amended minimum sample: delivery and latency criteria need ≥ 100 messages pooled over the seeds. */
    const val MIN_N = 100.0

    private fun mean(xs: List<Double>) = xs.filter { !it.isNaN() }.let { if (it.isEmpty()) Double.NaN else it.average() }

    private fun sum(xs: List<Double>) = xs.filter { !it.isNaN() }.sum()

    private fun pct(
        v: Double,
        d: Int = 2,
    ) = if (v.isNaN()) "–" else String.format(Locale.ROOT, "%.${d}f%%", v * 100)

    private fun f0(v: Double) = if (v.isNaN()) "–" else String.format(Locale.ROOT, "%.0f", v)

    private fun f1(v: Double) = if (v.isNaN()) "–" else String.format(Locale.ROOT, "%.1f", v)

    private fun f2(v: Double) = if (v.isNaN()) "–" else String.format(Locale.ROOT, "%.2f", v)

    private fun duty(s: String) = s.split('-').let { it[0].toDouble() / (it[0].toDouble() + it[1].toDouble()) }

    private val classes = listOf("low", "medium", "high")

    class Eval(
        val g: String,
        val fails: List<String>,
        val small: List<String>,
        val del: DoubleArray,
        val delN: DoubleArray,
        val bdel: DoubleArray,
        val bdelN: DoubleArray,
        val idle: Double,
        val busiest: Double,
        val busyMw: Double,
        val busyMwA0: Double,
        val ratioA0: Double,
        val first: DoubleArray,
        val firstN: DoubleArray,
        val firstLk: DoubleArray,
        val firstLkN: DoubleArray,
        val latP95: Double,
        val lg: DoubleArray,
        val n: Int,
    ) {
        val pass get() = fails.isEmpty()
    }

    /** Criteria 1–4 as amended (and 6 for large-message arms) for arm [g] over runs [rs] of one cell, set and schedule. */
    fun evaluate(
        rs: List<Run>,
        g: String,
    ): Eval? {
        val u = "U:$g"
        val b = "B:$g"
        val have = rs.filter { it.inst.containsKey(u) }
        if (have.isEmpty()) return null
        fun m(
            k: String,
            metric: String,
        ) = mean(have.map { it.v(k, metric) })
        fun n(
            k: String,
            metric: String,
        ) = sum(have.map { it.v(k, metric) })
        val fails = ArrayList<String>()
        val small = ArrayList<String>()
        val del = DoubleArray(3) { m(u, "del_${classes[it]}") }
        val delN = DoubleArray(3) { n(u, "n_${classes[it]}") }
        val bdel = DoubleArray(3) { m(b, "del_${classes[it]}") }
        val bdelN = DoubleArray(3) { n(b, "n_${classes[it]}") }
        for ((tag, k, refK, d, dn) in listOf(Five("unicast", u, "U1", del, delN), Five("broadcast", b, "B1", bdel, bdelN))) {
            for (c in 0..2) {
                if (dn[c] < 1) continue
                if (dn[c] < MIN_N) {
                    small += "$tag ${classes[c]} ${pct(d[c])} (n=${f0(dn[c])})"
                    continue
                }
                when (c) {
                    0 -> {
                        val rd = m(refK, "del_low")
                        if (!rd.isNaN() && (d[0] < rd - 0.005 || (rd >= 0.99 && d[0] < 0.99))) fails += "1 $tag low ${pct(d[0])} vs flood ${pct(rd)}"
                    }
                    1 -> if (d[1] < 0.99) fails += "1 $tag medium ${pct(d[1])}"
                    else -> if (d[2] < 0.95) fails += "1 $tag high ${pct(d[2])}"
                }
            }
        }
        val idle = mean(have.map { it.v(u, "grpIdleCtrlShare") + it.t("controlShareOfCapacity") })
        if (idle > 0.10) fails += "2 idle control ${pct(idle)}"
        val busiest = m(u, "grpLoadMaxNoBulkBps")
        val busyMw = m(u, "busyMw")
        val busyMwA0 = mean(have.map { it.v("U:A0", "busyMw") })
        if (busiest > BUSY_BPS) fails += "3 busiest ${f0(busiest)} B/s"
        if (busyMw - busyMwA0 > BUSY_MW_DELTA) fails += "3 busiest mW +${f2(busyMw - busyMwA0)}"
        val ratioA0 = busiest / mean(have.map { it.v("U:A0", "grpLoadMaxNoBulkBps") })
        val first = doubleArrayOf(m(u, "firstP95ClassicalMs"), m(u, "firstP95PqMs"))
        val firstN = doubleArrayOf(n(u, "firstNC"), n(u, "firstNP"))
        val firstLk = doubleArrayOf(m(u, "firstLkP95ClassicalMs"), m(u, "firstLkP95PqMs"))
        val firstLkN = doubleArrayOf(n(u, "firstLkNC"), n(u, "firstLkNP"))
        val lim = listOf(1000.0 to "first p95", 1500.0 to "first PQ p95", 2000.0 to "first+lookup p95", 3000.0 to "first+lookup PQ p95")
        val vals = listOf(first[0] to firstN[0], first[1] to firstN[1], firstLk[0] to firstLkN[0], firstLk[1] to firstLkN[1])
        for (i in 0..3) {
            val (v, cnt) = vals[i]
            if (v.isNaN() || cnt < 1) continue
            if (cnt < MIN_N) {
                small += "${lim[i].second} ${f0(v)} ms (n=${f0(cnt)})"
                continue
            }
            if (v > lim[i].first) fails += "4 ${lim[i].second} ${f0(v)} ms"
        }
        val lg =
            doubleArrayOf(
                m(u, "lgDel_medium"), m(u, "lgDel_high"), m(u, "lgDelStay_medium"), m(u, "lgDelStay_high"),
                n(u, "lgN_medium"), n(u, "lgN_high"), m(u, "lgP50Ms"), m(u, "lgP95Ms"), n(u, "lgNStay_medium"), n(u, "lgNStay_high"),
            )
        if (g.startsWith("A4")) {
            for ((i, c) in listOf(2 to "medium", 3 to "high")) {
                val cnt = lg[i + 6]
                if (lg[i].isNaN() || cnt < 1) continue
                if (cnt < MIN_N) small += "large $c ${pct(lg[i], 1)} (n=${f0(cnt)})" else if (lg[i] < 0.95) fails += "6 large $c ${pct(lg[i], 1)}"
            }
        }
        return Eval(
            g, fails, small, del, delN, bdel, bdelN, idle, busiest, busyMw, busyMwA0, ratioA0, first, firstN, firstLk, firstLkN,
            m(u, "latP95Ms"), lg, have.size,
        )
    }

    private data class Five(
        val a: String,
        val b: String,
        val c: String,
        val d: DoubleArray,
        val e: DoubleArray,
    )

    private fun dn(
        v: Double,
        n: Double,
    ) = if (n < 1) "–" else "${pct(v)}<small>${if (n < MIN_N) " n=${f0(n)}" else ""}</small>"

    private fun lat(
        v: Double,
        n: Double,
    ) = if (v.isNaN() || n < 1) "–" else "${f0(v)}<small> n=${f0(n)}</small>"

    private const val CRIT_HEAD =
        "<tr><th class=l>cell</th><th class=l>arm</th><th>unicast del. low</th><th>medium</th><th>high</th><th>broadcast del. low / med / high</th>" +
            "<th>idle control (% capacity)</th><th>busiest phone B/s, no bulk (≤ 4,700)</th><th>busiest phone mW (Δ vs A0 ≤ 3)</th><th>old ratio ÷ A0</th>" +
            "<th>first msg p95 ms cached: classical / PQ</th><th>after lookup: classical / PQ</th><th>unicast p95 ms (aim)</th>" +
            "<th class=l>criteria 1–4 (6 for A4*) as amended</th><th class=l>reported only (n &lt; 100)</th></tr>"

    private fun criteriaRow(
        title: String,
        e: Eval,
    ): String {
        val cls = if (e.pass) "better" else "worse"
        return "<tr><td class=l>$title</td><td class=l>${e.g}</td><td>${dn(e.del[0], e.delN[0])}</td><td>${dn(e.del[1], e.delN[1])}</td><td>${dn(e.del[2], e.delN[2])}</td>" +
            "<td>${dn(e.bdel[0], e.bdelN[0])} / ${dn(e.bdel[1], e.bdelN[1])} / ${dn(e.bdel[2], e.bdelN[2])}</td><td>${pct(e.idle, 3)}</td><td>${f0(e.busiest)}</td>" +
            "<td>${f1(e.busyMw)} <small>(${if (e.busyMw - e.busyMwA0 >= 0) "+" else ""}${f1(e.busyMw - e.busyMwA0)})</small></td><td>${f2(e.ratioA0)}</td>" +
            "<td>${lat(e.first[0], e.firstN[0])} / ${lat(e.first[1], e.firstN[1])}</td><td>${lat(e.firstLk[0], e.firstLkN[0])} / ${lat(e.firstLk[1], e.firstLkN[1])}</td>" +
            "<td>${f0(e.latP95)}</td><td class=\"l $cls\">${if (e.pass) "pass" else esc(e.fails.joinToString("; "))}</td>" +
            "<td class=l><small>${esc(e.small.joinToString("; "))}</small></td></tr>"
    }

    private fun criteriaTable(
        all: List<Run>,
        set: String,
        arms: List<String>,
        sched: String = StageFPlan.INTERIM,
    ): String {
        val sb = StringBuilder("<table>$CRIT_HEAD")
        for (cell in cellOrder) {
            val rs = all.filter { it.set == set && it.cell == cell && it.sched == sched }
            if (rs.isEmpty()) continue
            for (g in arms) {
                val e = evaluate(rs, g) ?: continue
                sb.append(criteriaRow("${cellTitle(cell)} <small>(${e.n} seeds)</small>", e))
            }
        }
        return sb.append("</table>").toString()
    }

    val kinds = listOf("data", "rreq", "rrep", "lkReq", "lkRep", "idle", "other", "bcast")
    private val kindNames =
        listOf(
            "unicast data", "route requests", "route replies", "lookup requests", "lookup replies", "catch-up (idle: summaries, claims, tombstones, IHAVE)",
            "other unicast control (acks, receipts)", "broadcast (Plumtree, held broadcasts)",
        )

    private fun busyTable(
        all: List<Run>,
        set: String,
        arms: List<String>,
        prefix: String = "busy_",
    ): String {
        val sb = StringBuilder("<table><tr><th class=l>cell</th><th class=l>arm</th><th>total B/s (no bulk)</th>")
        kindNames.forEach { sb.append("<th>$it</th>") }
        if (prefix == "busy_") sb.append("<th>bulk B/s</th><th>busiest is iPhone (share of seeds)</th><th>busiest phone mW</th><th>highest-mW phone mW</th>")
        sb.append("</tr>")
        for (cell in cellOrder) {
            val rs = all.filter { it.set == set && it.cell == cell && it.sched == StageFPlan.INTERIM }
            for (g in arms) {
                val h = rs.filter { it.inst.containsKey("U:$g") }
                if (h.isEmpty()) continue
                val u = "U:$g"
                val tot = mean(h.map { r -> kinds.sumOf { r.v(u, "$prefix$it") } })
                sb.append("<tr><td class=l>${cellTitle(cell)}</td><td class=l>$g</td><td>${f0(tot)}</td>")
                for (k in kinds) sb.append("<td>${f0(mean(h.map { it.v(u, "$prefix$k") }))}</td>")
                if (prefix == "busy_") {
                    sb.append(
                        "<td>${f0(mean(h.map { it.v(u, "busyBulkBps") }))}</td><td>${f2(mean(h.map { it.v(u, "busyIos") }))}</td>" +
                            "<td>${f2(mean(h.map { it.v(u, "busyMw") }))}</td><td>${f2(mean(h.map { it.v(u, "eMaxMw") }))}</td>",
                    )
                }
                sb.append("</tr>")
            }
        }
        return sb.append("</table>").toString()
    }

    /** Lookup statistics pooled over seeds: hop distance of the answer that completed each lookup, answers per lookup. */
    class LookupStats(
        val lookups: Double,
        val perNodeMin: Double,
        val success: Double,
        val p50Ms: Double,
        val p95Ms: Double,
        val hops: DoubleArray,
        val ansPerTx: Double,
        val replyAtReqPerTx: Double,
        val ansSelf: Double,
        val lookupTxPerNodeMin: Double,
    ) {
        val n get() = hops.sum()

        fun q(p: Double): Int {
            var acc = 0.0
            for (h in hops.indices) {
                acc += hops[h]
                if (acc >= p * n) return h + 1
            }
            return hops.size
        }

        fun within(h: Int) = (0 until h).sumOf { hops[it] } / n
    }

    fun lookupStats(
        rs: List<Run>,
        g: String,
    ): LookupStats? {
        val u = "U:$g"
        val h = rs.filter { it.inst.containsKey(u) }
        if (h.isEmpty() || sum(h.map { it.v(u, "c_lookupEpisodes") }) == 0.0) return null
        fun s(k: String) = sum(h.map { it.v(u, k) })
        val tx = s("c_lookupTx")
        return LookupStats(
            s("c_lookupEpisodes"), mean(h.map { it.v(u, "lookupsPerNodeMin") }), s("c_lookupOk") / s("c_lookupEpisodes"),
            mean(h.map { it.v(u, "lookupP50Ms") }), mean(h.map { it.v(u, "lookupP95Ms") }), DoubleArray(6) { s("lkHopEq${it + 1}") },
            (s("c_lookupAnsCache") + s("c_lookupAnsSelf")) / tx, s("c_lookupReplyAtReq") / tx, s("c_lookupAnsSelf"),
            mean(h.map { it.v(u, "lookupReqTxPerNodeMin") }),
        )
    }

    private fun lookupTable(
        all: List<Run>,
        set: String,
        arms: List<String>,
    ): String {
        val sb =
            StringBuilder(
                "<table><tr><th class=l>cell</th><th class=l>arm</th><th>lookups (all seeds)</th><th>lookups /node-min</th><th>request tx /node-min</th>" +
                    "<th>success</th><th>latency p50 / p95 ms</th><th>answer hops p50 / p90</th><th>answered within 1 hop</th><th>within 2 hops</th>" +
                    "<th>hop histogram 1/2/3/4/5/6+</th><th>answers generated per lookup attempt</th><th>replies reaching requester per attempt</th><th>answered by destination</th></tr>",
            )
        for (cell in cellOrder) {
            val rs = all.filter { it.set == set && it.cell == cell && it.sched == StageFPlan.INTERIM }
            for (g in arms) {
                val l = lookupStats(rs, g) ?: continue
                sb.append(
                    "<tr><td class=l>${cellTitle(cell)}</td><td class=l>$g</td><td>${f0(l.lookups)}</td><td>${f2(l.perNodeMin)}</td><td>${f1(l.lookupTxPerNodeMin)}</td>" +
                        "<td>${pct(l.success, 1)}</td><td>${f0(l.p50Ms)} / ${f0(l.p95Ms)}</td><td>${l.q(0.5)} / ${l.q(0.9)}</td><td>${pct(l.within(1), 1)}</td>" +
                        "<td>${pct(l.within(2), 1)}</td><td>${l.hops.joinToString("/") { f0(it) }}</td><td>${f1(l.ansPerTx)}</td><td>${f2(l.replyAtReqPerTx)}</td><td>${f0(l.ansSelf)}</td></tr>",
                )
            }
        }
        return sb.append("</table>").toString()
    }

    private fun largeTable(
        all: List<Run>,
        set: String,
        g: String,
    ): String {
        val sb =
            StringBuilder(
                "<table><tr><th class=l>cell</th><th>large msgs medium / high sender (all seeds)</th><th>delivery medium / high (all)</th>" +
                    "<th>delivery medium / high (excl. sender or destination left)</th><th>p50 / p95 latency s</th><th>busiest phone B/s incl. bulk</th></tr>",
            )
        for (cell in cellOrder) {
            val rs = all.filter { it.set == set && it.cell == cell && it.sched == StageFPlan.INTERIM }
            val e = evaluate(rs, g) ?: continue
            val withBulk = mean(rs.map { it.v("U:$g", "grpLoadMaxBps") })
            sb.append(
                "<tr><td class=l>${cellTitle(cell)}</td><td>${f0(e.lg[4])} / ${f0(e.lg[5])}</td><td>${pct(e.lg[0], 1)} / ${pct(e.lg[1], 1)}</td>" +
                    "<td>${pct(e.lg[2], 1)} <small>n=${f0(e.lg[8])}</small> / ${pct(e.lg[3], 1)} <small>n=${f0(e.lg[9])}</small></td>" +
                    "<td>${f2(e.lg[6] / 1000)} / ${f2(e.lg[7] / 1000)}</td><td>${f0(withBulk)}</td></tr>",
            )
        }
        return sb.append("</table>").toString()
    }

    // ---- schedules ----

    class SchedPoint(
        val sched: String,
        val energy: Double,
        val energyBusy: Double,
        val fails: List<String>,
        val joinRatioWorst: Double,
        val perCell: List<String>,
        val condFails: List<String>,
    ) {
        val ok get() = fails.isEmpty() && !(joinRatioWorst > 1.25)

        /** Secondary rule (stage F's conditional rule): failures already present at the interim schedule are ignored. */
        val condOk get() = condFails.isEmpty() && !(joinRatioWorst > 1.25)
    }

    /** "1 unicast low 99.2% vs flood 100%" -> "1 unicast low": the failing check without its numbers. */
    private fun failKey(f: String): String {
        val w = f.split(' ')
        return (listOf(w[0]) + w.drop(1).takeWhile { x -> x.none { it.isDigit() } && !x.startsWith("+") }).joinToString(" ")
    }

    /** Sweep points of class [c]: every schedule incl. interim (interim from the F2 main set, same instance streams). */
    fun sweepPoints(
        all: List<Run>,
        c: Int,
    ): List<SchedPoint> {
        val interim = StageFPlan.INTERIM
        val out = ArrayList<SchedPoint>()
        val interimKeys = HashSet<String>()
        for (base in StageFReport.classCells[c]) for (p in listOf("android", "half-ios")) {
            val cell = "$base|$p"
            val ref = all.filter { it.cell == cell && it.sched == interim && it.set == CSet.F2_MAIN.label }
            evaluate(ref, FIN)?.fails?.forEach { interimKeys += "$cell ${failKey(it)}" }
        }
        for (s in StageFPlan.schedules) {
            val label = StageFPlan.swap(interim, c, s)
            val fails = ArrayList<String>()
            val es = ArrayList<Double>()
            val eb = ArrayList<Double>()
            var worst = Double.NaN
            val perCell = ArrayList<String>()
            val cond = ArrayList<String>()
            for (base in StageFReport.classCells[c]) for (p in listOf("android", "half-ios")) {
                val cell = "$base|$p"
                val rs = all.filter { it.cell == cell && it.sched == label && (it.set == CSet.F2_SWEEP.label || (label == interim && it.set == CSet.F2_MAIN.label)) }
                val ref = all.filter { it.cell == cell && it.sched == interim && it.set == CSet.F2_MAIN.label }
                val e = evaluate(rs, FIN) ?: continue
                fails += e.fails.map { "${cellTitle(cell)}: $it" }
                cond += e.fails.filter { "$cell ${failKey(it)}" !in interimKeys }.map { "${cellTitle(cell)}: $it" }
                val en = mean(rs.map { it.v("U:$FIN", "eMeanMw") })
                es += en
                eb += e.busyMw
                val j = mean(rs.map { it.t("joinP95S") })
                val jr = j / mean(ref.map { it.t("joinP95S") })
                if (!jr.isNaN()) worst = if (worst.isNaN()) jr else maxOf(worst, jr)
                perCell +=
                    "${cellTitle(cell)}: ${f2(en)} mW (busiest ${f2(e.busyMw)}, A0 ${f2(e.busyMwA0)}; ${f0(e.busiest)} B/s), " +
                    "join p95 ${f2(j)} s (×${f2(jr)})${if (e.small.isEmpty()) "" else "; n<100: " + e.small.joinToString(", ")}"
            }
            if (es.isEmpty()) continue
            out += SchedPoint(s, es.average(), eb.average(), fails, worst, perCell, cond)
        }
        return out
    }

    /** Pre-registered stage F rule: Balanced = lowest-energy passing schedule; Responsive = the next higher duty. */
    fun picks(
        all: List<Run>,
        conditional: Boolean = false,
    ): List<Pair<String, String?>?> =
        (0..2).map { c ->
            val ok = sweepPoints(all, c).filter { if (conditional) it.condOk else it.ok }
            if (ok.isEmpty()) return@map null
            val bal = ok.minBy { it.energy }.sched
            val byDuty = StageFPlan.schedules.sortedBy { duty(it) }
            bal to byDuty.getOrNull(byDuty.indexOf(bal) + 1)
        }

    private fun pickText(p: Pair<String, String?>?) =
        p?.let { "Balanced ${it.first.replace('-', '/')}, Responsive ${it.second?.replace('-', '/') ?: "none (Balanced is already the fastest swept schedule, 4/2)"}" }
            ?: "no schedule passes"

    private fun sweepSection(all: List<Run>): String {
        val sb = StringBuilder()
        val pk = picks(all)
        val ck = picks(all, conditional = true)
        for (c in 0..2) {
            val pts = sweepPoints(all, c)
            if (pts.isEmpty()) continue
            sb.append(
                "<h3>${StageFReport.classNames[c]} density class</h3><p>Pick (pre-registered rule): ${pickText(pk[c])}. Secondary, as stage F's " +
                    "conditional rule (failures the final arm already has at the interim schedule ignored): ${pickText(ck[c])}.</p>",
            )
            sb.append("<table><tr><th>schedule on/off s</th><th>duty</th><th>mean phone mW</th><th>busiest phone mW</th><th>worst join p95 ÷ interim</th><th class=l>criteria 1–4 as amended</th><th class=l>failures not present at interim</th><th class=l>per cell</th></tr>")
            for (p in pts.sortedBy { duty(it.sched) }) {
                val mark =
                    when (p.sched) {
                        pk[c]?.first -> " <b>Balanced</b>"
                        pk[c]?.second -> " <b>Responsive</b>"
                        else -> ""
                    } +
                    when (p.sched) {
                        ck[c]?.first -> " <b>Balanced (secondary)</b>"
                        ck[c]?.second -> " <b>Responsive (secondary)</b>"
                        else -> ""
                    } + if (p.sched == StageFPlan.INTERIM.split('_')[c]) " <i>(interim)</i>" else ""
                sb.append(
                    "<tr><td>${p.sched.replace('-', '/')}$mark</td><td>${f2(duty(p.sched))}</td><td>${f2(p.energy)}</td><td>${f2(p.energyBusy)}</td><td>${f2(p.joinRatioWorst)}</td>" +
                        "<td class=\"l ${if (p.ok) "better" else "worse"}\">${if (p.fails.isEmpty()) "pass" else esc(p.fails.joinToString("; "))}</td>" +
                        "<td class=\"l ${if (p.condOk) "better" else "worse"}\">${if (p.condFails.isEmpty()) "none" else esc(p.condFails.joinToString("; "))}</td>" +
                        "<td class=l><small>${esc(p.perCell.joinToString("; "))}</small></td></tr>",
                )
            }
            sb.append("</table>")
        }
        return sb.toString()
    }

    /** "4-4_4-16_4-12": the Balanced (or Responsive) schedule per class. */
    fun pickedSchedule(
        all: List<Run>,
        responsive: Boolean,
        conditional: Boolean = false,
    ): String? {
        val pk = picks(all, conditional)
        val parts = (0..2).map { c -> pk[c]?.let { if (responsive) it.second ?: it.first else it.first } ?: return null }
        return parts.joinToString("_")
    }

    private fun confTable(all: List<Run>): String {
        val sb = StringBuilder("<table>$CRIT_HEAD")
        val rsAll = all.filter { it.set == CSet.F2_CONF.label }
        if (rsAll.isEmpty()) return "<p>Not run.</p>"
        for (sch in rsAll.map { it.sched }.distinct().sorted()) for (cell in cellOrder) {
            val rs = rsAll.filter { it.cell == cell && it.sched == sch }
            val e = evaluate(rs, FIN) ?: continue
            val j = mean(rs.map { it.t("joinP95S") })
            val ref = mean(all.filter { it.set == CSet.F2_MAIN.label && it.cell == cell && it.sched == StageFPlan.INTERIM }.map { it.t("joinP95S") })
            val en = mean(rs.map { it.v("U:$FIN", "eMeanMw") })
            val enRef = mean(all.filter { it.set == CSet.F2_MAIN.label && it.cell == cell && it.sched == StageFPlan.INTERIM }.map { it.v("U:$FIN", "eMeanMw") })
            sb.append(
                criteriaRow(
                    "${cellTitle(cell)} · ${sch.replace('-', '/').replace("_", " · ")} · join p95 ${f2(j)} s (×${f2(j / ref)} vs interim) · mean ${f2(en)} mW (interim ${f2(enRef)})",
                    e,
                ),
            )
        }
        return sb.append("</table>").toString()
    }

    private fun bgTable(all: List<Run>): String {
        val rsAll = all.filter { it.set == CSet.F2_BG.label }
        if (rsAll.isEmpty()) return "<p>Not run.</p>"
        val sb =
            StringBuilder(
                "<table><tr><th class=l>cell</th><th>unicast delivery</th><th>broadcast delivery</th><th>unicast p95 ms</th><th>first msg p95 cached C / PQ</th>" +
                    "<th>first msg p95 after lookup C / PQ</th><th>join p95 s</th><th>join median s</th><th>mean mW</th><th>foreground mean mW</th><th>background mean mW</th>" +
                    "<th>busiest phone B/s</th><th>busiest phone mW</th></tr>",
            )
        for (cell in cellOrder) {
            val rs = rsAll.filter { it.cell == cell }
            if (rs.isEmpty()) continue
            fun m(
                k: String,
                x: String,
            ) = mean(rs.map { it.v(k, x) })
            val u = "U:$FIN"
            val b = "B:$FIN"
            sb.append(
                "<tr><td class=l>${cellTitle(cell)} <small>(${rs.first().sched.replace('-', '/').replace("_", " · ")})</small></td><td>${pct(m(u, "delivery"))}</td><td>${pct(m(b, "delivery"))}</td><td>${f0(m(u, "latP95Ms"))}</td>" +
                    "<td>${f0(m(u, "firstP95ClassicalMs"))} / ${f0(m(u, "firstP95PqMs"))}</td><td>${f0(m(u, "firstLkP95ClassicalMs"))} / ${f0(m(u, "firstLkP95PqMs"))}</td>" +
                    "<td>${f2(mean(rs.map { it.t("joinP95S") }))}</td><td>${f2(mean(rs.map { it.t("joinMedianS") }))}</td><td>${f2(m(u, "eMeanMw"))}</td>" +
                    "<td>${f2(m(u, "eMeanMwFg"))}</td><td>${f2(m(u, "eMeanMwBg"))}</td><td>${f0(m(u, "grpLoadMaxNoBulkBps"))}</td><td>${f2(m(u, "busyMw"))}</td></tr>",
            )
        }
        return sb.append("</table>").toString()
    }

    fun render(
        rows: List<Map<String, String>>,
        notes: String,
    ): String {
        val all = StageFReport.runs(rows)
        val sb = StringBuilder(HtmlReport.head("MeshLink routing simulation — stage F2: amended criteria, final arm A4D, schedules"))
        sb.append(
            """<h1>MeshLink routing simulation — stage F2</h1>
            <p class="warn"><b>Every number on this page is simulated.</b> It comes from a simplified computer model of phones and
            Bluetooth links, calibrated against lab measurements (bearer bake-off, 2026-11). It is not a measurement on real devices.
            iPhone energy numbers use the Android coefficients and are bounds only.</p>
            <p>Stage F2 re-runs stage F with added instrumentation and evaluates it under the owner's <b>post-hoc amendments</b>
            (decided after stage F's results): (a) criterion 3 is an absolute budget: the busiest phone sends ≤ 4,700 B/s of routing
            bytes (10% of the slowest measured Android link, 47 kB/s; large-message bulk excluded) <i>and</i> its energy is ≤ A0's
            busiest phone + 3 mW in the same cell and run; (b) a delivery or latency criterion is applied to a sub-population only if it
            has ≥ 100 messages pooled over the seeds (smaller ones are reported with n); (c) ADR 0018 amended: relays forward only the
            first lookup reply per request, so the final arm is A4D = A4 + first-reply-only; (d) no lookup rate limit, A2L not adopted.
            Other criteria as pre-registered for stage F. "Busiest phone" is the honest phone that sent the most non-bulk routing
            bytes in the run (summed over the arm's unicast and broadcast instances); its mW is that phone's.</p>
            $notes""",
        )
        val arms = listOf("A0", "A1", "A2", "A3", "A4", "A3D", FIN)
        sb.append("<h2>1. Criteria per cell and arm (30 min runs, 30 min lifetime, interim scan schedule)</h2>")
        sb.append(criteriaTable(all, CSet.F2_MAIN.label, arms))
        sb.append("<h2>2. Busiest phone: bytes sent per second by kind</h2><p>Mean over seeds of the busiest phone of each run (non-bulk routing bytes sent).</p>")
        sb.append(busyTable(all, CSet.F2_MAIN.label, arms))
        sb.append("<h3>Mean phone, same breakdown</h3>").append(busyTable(all, CSet.F2_MAIN.label, arms, "mean_"))
        sb.append(
            """<h2>3. Certificate lookups</h2><p>Hop distance = hops the lookup request travelled from the requester to the node whose
            reply completed the lookup (flood path, so ≥ the shortest path), pooled over seeds; lookups started in the measured window.
            Answers generated = replies sent by answering nodes (the destination or any node with a cached certificate) per lookup
            attempt; replies reaching requester counts every copy that arrived back.</p>""",
        )
        sb.append(lookupTable(all, CSet.F2_MAIN.label, listOf("A3", "A4", "A3D", FIN)))
        sb.append("<h3>Large messages ($FIN)</h3>").append(largeTable(all, CSet.F2_MAIN.label, FIN))
        sb.append("<h2>4. One-hour runs (1 h lifetime)</h2>").append(criteriaTable(all, CSet.F2_LONG.label, listOf("A0", FIN)))
        sb.append(busyTable(all, CSet.F2_LONG.label, listOf("A0", FIN)))
        sb.append(lookupTable(all, CSet.F2_LONG.label, listOf(FIN)))
        sb.append("<h3>Large messages, one-hour runs</h3>").append(largeTable(all, CSet.F2_LONG.label, FIN))
        sb.append(
            """<h2>5. Scan schedule sweep ($FIN)</h2><p>Each row changes only that class's schedule (others interim: low 4/4, medium 4/4,
            high 4/8). Rule (pre-registered in stage F, criteria as amended): Balanced = lowest mean-phone energy schedule that passes
            criteria 1–4 in every cell of the class with worst p95 join time ≤ 1.25 × interim; Responsive = next higher duty.
            Criterion 3's mW limit compares with A0 in the same run (same schedule).</p>""",
        )
        sb.append(sweepSection(all))
        sb.append("<h2>6. Confirmation on dynamic cells</h2>").append(confTable(all))
        sb.append("<h2>7. Background (50% of phones backgrounded, Balanced)</h2>").append(bgTable(all))
        sb.append("</body></html>")
        return sb.toString()
    }
}

fun stageF2Report(out: File) {
    val rows = CSet.entries.filter { it.stageF2 }.flatMap { s -> CRaw.file(out, s).let { if (it.exists()) RawCsv.read(it) else emptyList() } }
    val all = StageFReport.runs(rows)
    StageF2Report.picks(all).forEachIndexed { c, p -> println("class ${StageFReport.classNames[c]}: balanced/responsive = $p") }
    println("balanced schedule: ${StageF2Report.pickedSchedule(all, false)}; responsive: ${StageF2Report.pickedSchedule(all, true)}")
    StageF2Report.picks(all, conditional = true).forEachIndexed { c, p -> println("class ${StageFReport.classNames[c]}: secondary balanced/responsive = $p") }
    println(
        "secondary balanced schedule: ${StageF2Report.pickedSchedule(all, false, true)}; responsive: ${StageF2Report.pickedSchedule(all, true, true)}",
    )
    val notes = File(out, "notes-f2.html").let { if (it.exists()) it.readText() else "" }
    File(out, "stage-f2-report.html").writeText(StageF2Report.render(rows, notes))
    println("wrote ${File(out, "stage-f2-report.html")}")
}
