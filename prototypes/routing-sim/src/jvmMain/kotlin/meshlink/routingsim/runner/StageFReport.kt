package meshlink.routingsim.runner

import meshlink.routingsim.runner.HtmlReport.esc
import meshlink.routingsim.runner.HtmlReport.fmt
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/** Stage F report: ADR 0014/0015/0016/0018 costs, rate limit, energy and scan schedules. Inline CSS only. */
object StageFReport {
    class Run(
        val set: String,
        val cell: String,
        val seed: String,
        val sched: String,
        val calib: String,
        val dur: String,
        val inst: Map<String, Map<String, String>>,
    ) {
        fun v(
            key: String,
            m: String,
        ) = inst[key]?.get(m)?.toDoubleOrNull() ?: Double.NaN

        fun t(m: String) = inst.values.first()["t_$m"]?.toDoubleOrNull() ?: Double.NaN
    }

    private val group = Regex("\\+?G([A-Za-z0-9]+)$")

    /** "U1", "B1", "U:<group>" or "B:<group>". */
    private fun key(r: Map<String, String>): String {
        val p = r["protocol"] ?: ""
        val g = group.find(r["variant"] ?: "")?.groupValues?.get(1)
        return when {
            g == null -> p
            p == "UA" -> "U:$g"
            else -> "B:$g"
        }
    }

    fun runs(rows: List<Map<String, String>>): List<Run> =
        rows.groupBy { it.getValue("runId") }.map { (_, rs) ->
            val f = rs.first()
            Run(
                f.getValue("set"), "${f["scenario"]}|${f["density"]}|${f["platform"]}", f.getValue("seed"), f["sched"] ?: "",
                f["calib"] ?: "", f["durationS"] ?: "", rs.associateBy { key(it) },
            )
        }

    class Stat(
        val mean: Double,
        val ci: Double,
        val n: Int,
    ) {
        fun s(
            scale: Double = 1.0,
            d: Int = -1,
        ): String {
            if (mean.isNaN()) return "–"
            fun f(x: Double) = if (d >= 0) String.format(Locale.ROOT, "%.${d}f", x * scale) else fmt(x * scale)
            return if (ci.isNaN()) f(mean) else "${f(mean)} <small>± ${f(ci)}</small>"
        }
    }

    fun stat(xs: List<Double>): Stat {
        val v = xs.filter { !it.isNaN() }
        if (v.isEmpty()) return Stat(Double.NaN, Double.NaN, 0)
        val mu = v.average()
        if (v.size < 2) return Stat(mu, Double.NaN, v.size)
        val sd = sqrt(v.sumOf { (it - mu) * (it - mu) } / (v.size - 1))
        return Stat(mu, Aggregate.tQuantile(v.size - 1) * sd / sqrt(v.size.toDouble()), v.size)
    }

    private fun mean(xs: List<Double>) = xs.filter { !it.isNaN() }.let { if (it.isEmpty()) Double.NaN else it.average() }

    private fun pct(
        v: Double,
        d: Int = 2,
    ) = if (v.isNaN()) "–" else String.format(Locale.ROOT, "%.${d}f%%", v * 100)

    private fun f0(v: Double) = if (v.isNaN()) "–" else String.format(Locale.ROOT, "%.0f", v)

    private fun f2(v: Double) = if (v.isNaN()) "–" else String.format(Locale.ROOT, "%.2f", v)

    val cellOrder =
        listOf("static|low", "static|medium", "static|high", "waypoint|medium", "arrival|dynamic").flatMap { c ->
            listOf("android", "half-ios").map { "$c|$it" }
        }

    fun cellTitle(cell: String): String {
        val p = cell.split('|')
        val what =
            when ("${p[0]}|${p[1]}") {
                "static|low" -> "low static"
                "static|medium" -> "medium static"
                "static|high" -> "high static"
                "waypoint|medium" -> "medium walking"
                else -> "arrival"
            }
        return "$what, ${if (p[2] == "android") "Android" else "half iOS"}"
    }

    /** Criteria 1–4 (and the aim 5, large messages 6) for arm [g] over [rs] (same cell, set, schedule). */
    class Eval(
        val g: String,
        val fails: List<String>,
        val del: DoubleArray,
        val bdel: DoubleArray,
        val idle: Double,
        val busiest: Double,
        val busiestBulk: Double,
        val ratio: Double,
        val first: DoubleArray,
        val firstLk: DoubleArray,
        val firstLkN: DoubleArray,
        val latP95: Double,
        val lg: DoubleArray,
        val n: Int,
    ) {
        val pass get() = fails.isEmpty()
    }

    fun evaluate(
        rs: List<Run>,
        g: String,
        ref: String = "A0",
    ): Eval? {
        val u = "U:$g"
        val b = "B:$g"
        val have = rs.filter { it.inst.containsKey(u) }
        if (have.isEmpty()) return null
        fun m(
            k: String,
            metric: String,
        ) = mean(have.map { it.v(k, metric) })
        val fails = ArrayList<String>()
        val classes = listOf("low", "medium", "high")
        val del = DoubleArray(3) { m(u, "del_${classes[it]}") }
        val bdel = DoubleArray(3) { m(b, "del_${classes[it]}") }
        for ((tag, k, refK, d) in listOf(Quad("unicast", u, "U1", del), Quad("broadcast", b, "B1", bdel))) {
            if (m(k, "n_low") >= 1) {
                val rd = m(refK, "del_low")
                if (!rd.isNaN() && (d[0] < rd - 0.005 || (rd >= 0.99 && d[0] < 0.99))) fails += "1 $tag low ${pct(d[0])} vs flood ${pct(rd)}"
            }
            if (m(k, "n_medium") >= 1 && d[1] < 0.99) fails += "1 $tag medium ${pct(d[1])}"
            if (m(k, "n_high") >= 1 && d[2] < 0.95) fails += "1 $tag high ${pct(d[2])}"
        }
        val idle = mean(have.map { it.v(u, "grpIdleCtrlShare") + it.t("controlShareOfCapacity") })
        if (idle > 0.10) fails += "2 idle control ${pct(idle)}"
        val busiest = m(u, "grpLoadMaxNoBulkBps")
        val refBusiest = mean(have.map { it.v("U:$ref", "grpLoadMaxNoBulkBps") })
        val ratio = busiest / refBusiest
        if (ratio > 1.25) fails += "3 busiest ×${f2(ratio)}"
        val first = doubleArrayOf(m(u, "firstP95ClassicalMs"), m(u, "firstP95PqMs"))
        val firstLk = doubleArrayOf(m(u, "firstLkP95ClassicalMs"), m(u, "firstLkP95PqMs"))
        val firstLkN = doubleArrayOf(m(u, "firstLkNC"), m(u, "firstLkNP"))
        if (first[0] > 1000) fails += "4 first p95 ${f0(first[0])} ms"
        if (first[1] > 1500) fails += "4 first PQ p95 ${f0(first[1])} ms"
        if (firstLk[0] > 2000) fails += "4 first+lookup p95 ${f0(firstLk[0])} ms"
        if (firstLk[1] > 3000) fails += "4 first+lookup PQ p95 ${f0(firstLk[1])} ms"
        val lg =
            doubleArrayOf(
                m(u, "lgDel_medium"), m(u, "lgDel_high"), m(u, "lgDelStay_medium"), m(u, "lgDelStay_high"),
                have.sumOf { it.v(u, "lgN_medium").let { x -> if (x.isNaN()) 0.0 else x } },
                have.sumOf { it.v(u, "lgN_high").let { x -> if (x.isNaN()) 0.0 else x } },
                m(u, "lgP50Ms"), m(u, "lgP95Ms"),
            )
        return Eval(
            g, fails, del, bdel, idle, busiest, m(u, "grpLoadMaxBps"), ratio, first, firstLk, firstLkN, m(u, "latP95Ms"), lg,
            have.size,
        )
    }

    private data class Quad(
        val a: String,
        val b: String,
        val c: String,
        val d: DoubleArray,
    )

    /** Large-message criterion 6 (on lgDelStay: undelivered messages whose sender or destination left are excluded). */
    fun largeFails(e: Eval): List<String> {
        val out = ArrayList<String>()
        if (!e.lg[2].isNaN() && e.lg[2] < 0.95) out += "6 large medium ${pct(e.lg[2], 1)}"
        if (!e.lg[3].isNaN() && e.lg[3] < 0.95) out += "6 large high ${pct(e.lg[3], 1)}"
        return out
    }

    private fun criteriaRow(
        title: String,
        e: Eval,
        extra: List<String> = emptyList(),
    ): String {
        val fails = e.fails + extra
        val cls = if (fails.isEmpty()) "better" else "worse"
        fun lk(i: Int) = if (e.firstLk[i].isNaN()) "–" else "${f0(e.firstLk[i])} <small>(n≈${f0(e.firstLkN[i])})</small>"
        return "<tr><td class=l>$title</td><td class=l>${e.g}</td><td>${pct(e.del[0])}</td><td>${pct(e.del[1])}</td><td>${pct(e.del[2])}</td>" +
            "<td>${pct(e.bdel[0])} / ${pct(e.bdel[1])} / ${pct(e.bdel[2])}</td><td>${pct(e.idle, 3)}</td><td>${f0(e.busiest)}</td><td>${f2(e.ratio)}</td>" +
            "<td>${f0(e.first[0])} / ${f0(e.first[1])}</td><td>${lk(0)} / ${lk(1)}</td><td>${f0(e.latP95)}</td>" +
            "<td class=\"l $cls\">${if (fails.isEmpty()) "pass" else esc(fails.joinToString("; "))}</td></tr>"
    }

    private const val CRIT_HEAD =
        "<tr><th class=l>cell</th><th class=l>arm</th><th>unicast del. low</th><th>medium</th><th>high</th><th>broadcast del. low / med / high</th>" +
            "<th>idle control (% capacity)</th><th>busiest phone B/s (no bulk)</th><th>÷ A0</th><th>first msg p95 ms, cert cached: classical / PQ</th>" +
            "<th>first msg p95 ms after lookup: classical / PQ</th><th>unicast p95 ms (aim ≤ 1000)</th><th class=l>criteria 1–4 (6 in A4)</th></tr>"

    private fun criteriaTable(
        all: List<Run>,
        set: String,
        arms: List<String>,
        cells: List<String> = cellOrder,
    ): String {
        val sb = StringBuilder("<table>$CRIT_HEAD")
        for (cell in cells) {
            val rs = all.filter { it.set == set && it.cell == cell && it.sched == StageFPlan.INTERIM }
            if (rs.isEmpty()) continue
            for (g in arms) {
                val e = evaluate(rs, g) ?: continue
                sb.append(criteriaRow("${cellTitle(cell)} <small>(${e.n} seeds)</small>", e, if (g.startsWith("A4")) largeFails(e) else emptyList()))
            }
        }
        return sb.append("</table>").toString()
    }

    private fun costTable(
        all: List<Run>,
        set: String,
        arms: List<String>,
    ): String {
        val cols =
            listOf(
                "rreqOrigPerNodeMin" to "route requests originated /node-min",
                "c_rreqConv" to "of which for conversation tags (run total)",
                "lookupsPerNodeMin" to "lookups /node-min",
                "lookupReqTxPerNodeMin" to "lookup request transmissions /node-min",
                "lookupSuccess" to "lookup success",
                "lookupP50Ms" to "lookup p50 ms",
                "lookupP95Ms" to "lookup p95 ms",
                "grpIdleBytesPerLinkUp" to "catch-up (idle) bytes per link-up",
                "holdMean" to "held for others, mean msgs/phone",
                "holdMax" to "max",
                "holdRejShare" to "rejected: ¼-per-link share (run total)",
                "holdRejFull" to "rejected: full",
                "holdEvicted" to "evicted",
                "c_tombStored" to "tombstones stored",
                "c_tombPurgePath" to "copies purged by receipt path",
                "c_tombPurgeCatchUp" to "copies purged by catch-up",
                "c_claimDelivered" to "held unicasts delivered via catch-up claim",
            )
        val sb = StringBuilder("<table><tr><th class=l>cell</th><th class=l>arm</th>")
        cols.forEach { sb.append("<th>${it.second}</th>") }
        sb.append("</tr>")
        for (cell in cellOrder) {
            val rs = all.filter { it.set == set && it.cell == cell && it.sched == StageFPlan.INTERIM }
            if (rs.isEmpty()) continue
            for (g in arms) {
                val h = rs.filter { it.inst.containsKey("U:$g") }
                if (h.isEmpty()) continue
                sb.append("<tr><td class=l>${cellTitle(cell)}</td><td class=l>$g</td>")
                for ((m, _) in cols) {
                    val v = mean(h.map { it.v("U:$g", m) })
                    sb.append("<td>${if (m == "lookupSuccess") pct(v, 1) else if (v.isNaN()) "–" else fmt(v)}</td>")
                }
                sb.append("</tr>")
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
                "<table><tr><th class=l>cell</th><th>large msgs (medium / high sender, all seeds)</th><th>delivery medium / high (all)</th>" +
                    "<th>delivery medium / high (excl. sender or destination left)</th><th>p50 / p95 latency s</th>" +
                    "<th>busiest phone B/s incl. bulk</th><th>bulk energy mW (mean phone)</th></tr>",
            )
        for (cell in cellOrder) {
            val rs = all.filter { it.set == set && it.cell == cell && it.sched == StageFPlan.INTERIM }
            val e = evaluate(rs, g) ?: continue
            val bulk = mean(rs.map { it.v("U:$g", "eBulkMw") })
            sb.append(
                "<tr><td class=l>${cellTitle(cell)}</td><td>${f0(e.lg[4])} / ${f0(e.lg[5])}</td><td>${pct(e.lg[0], 1)} / ${pct(e.lg[1], 1)}</td>" +
                    "<td>${pct(e.lg[2], 1)} / ${pct(e.lg[3], 1)}</td><td>${f2(e.lg[6] / 1000)} / ${f2(e.lg[7] / 1000)}</td><td>${f0(e.busiestBulk)}</td><td>${f2(bulk)}</td></tr>",
            )
        }
        return sb.append("</table>").toString()
    }

    /** Rate-limit test: honest effect of the attacker without (RL0) and with (RL1) the limit; RL1N = limit, no attacker. */
    fun rlRows(all: List<Run>): List<List<String>> {
        val fin = StageFPlan.finalArm
        val out = ArrayList<List<String>>()
        for (cell in cellOrder) {
            val rs = all.filter { it.set == CSet.F_RL.label && it.cell == cell }
            if (rs.isEmpty()) continue
            fun d(
                g: String,
                k: String,
            ) = stat(rs.map { it.v("$k:$g", "delivery") - it.v("$k:$fin", "delivery") })
            fun rel(g: String) = stat(rs.map { it.v("U:$g", "grpLoadMaxNoBulkBps") / it.v("U:$fin", "grpLoadMaxNoBulkBps") - 1 })
            val base = evaluate(rs, fin)!!
            for (g in listOf("RL0", "RL1", "RL1N")) {
                val e = evaluate(rs, g) ?: continue
                out +=
                    listOf(
                        cellTitle(cell), g, d(g, "U").s(100.0, 2) + " pp", d(g, "B").s(100.0, 2) + " pp", rel(g).s(100.0, 1) + "%",
                        f0(e.busiest) + " vs " + f0(base.busiest), fmt(mean(rs.map { it.v("U:$g", "lookupReqTxPerNodeMin") })),
                        fmt(mean(rs.map { it.v("U:$g", "c_rlDropped") })), f0(e.firstLk[0]) + " / " + f0(e.firstLk[1]),
                        if (e.fails.isEmpty()) "pass" else e.fails.joinToString("; "),
                    )
            }
        }
        return out
    }

    // ---- energy and schedules ----

    /** Fixed-density cells of class c (sweep), and the schedule label of class c in a run's schedule. */
    val classCells =
        listOf(
            listOf("static|low"),
            listOf("static|medium", "waypoint|medium"),
            listOf("static|high"),
        )
    val classNames = listOf("low", "medium", "high")

    private fun duty(s: String) = s.split('-').let { it[0].toDouble() / (it[0].toDouble() + it[1].toDouble()) }

    class SchedPoint(
        val cls: Int,
        val sched: String,
        val energy: Double,
        val energyMax: Double,
        val fails: List<String>,
        val joinRatioWorst: Double,
        val perCell: List<String>,
        val condFails: List<String> = emptyList(),
    ) {
        val ok get() = fails.isEmpty() && !(joinRatioWorst > 1.25)

        /** Conditional rule (deviation, see notes): only failures the schedule itself causes. */
        val condOk get() = condFails.isEmpty() && !(joinRatioWorst > 1.25)
    }

    /** "1 unicast low 99.2% vs flood 100%" -> "1 unicast low": the failing check without its numbers. */
    private fun failKey(f: String): String {
        val i = f.indexOf(": ")
        val body = if (i >= 0) f.substring(i + 2) else f
        val head = if (i >= 0) f.substring(0, i + 2) else ""
        return head + body.take(2) + body.drop(2).takeWhile { !it.isDigit() && it != '×' }.trim()
    }

    /** Sweep points of class [c]: every schedule incl. interim (interim from the main set). */
    fun sweepPoints(
        all: List<Run>,
        c: Int,
    ): List<SchedPoint> {
        val fin = StageFPlan.finalArm
        val interim = StageFPlan.INTERIM
        val out = ArrayList<SchedPoint>()
        val interimKeys = HashSet<String>()
        for (base in classCells[c]) for (p in listOf("android", "half-ios")) {
            val cell = "$base|$p"
            val ref = all.filter { it.cell == cell && it.sched == interim && it.set == CSet.F_MAIN.label }
            evaluate(ref, fin)?.fails?.forEach { interimKeys += failKey("${cellTitle(cell)}: $it") }
        }
        for (s in StageFPlan.schedules) {
            val label = StageFPlan.swap(interim, c, s)
            val fails = ArrayList<String>()
            val es = ArrayList<Double>()
            val ems = ArrayList<Double>()
            var worst = Double.NaN
            val perCell = ArrayList<String>()
            val cond = ArrayList<String>()
            for (base in classCells[c]) for (p in listOf("android", "half-ios")) {
                val cell = "$base|$p"
                val rs = all.filter { it.cell == cell && it.sched == label && (it.set == CSet.F_SWEEP.label || (label == interim && it.set == CSet.F_MAIN.label)) }
                val ref = all.filter { it.cell == cell && it.sched == interim && it.set == CSet.F_MAIN.label }
                if (rs.isEmpty()) continue
                val e = evaluate(rs, fin) ?: continue
                val cf = e.fails.map { "${cellTitle(cell)}: $it" }
                fails += cf
                cond += cf.filter { !it.substringAfter(": ").startsWith("3 ") && failKey(it) !in interimKeys }
                val refE = evaluate(ref, fin)
                if (refE != null) {
                    val self = e.busiest / refE.busiest
                    if (self > 1.25) cond += "${cellTitle(cell)}: 3' busiest vs interim ×${f2(self)}"
                }
                val en = mean(rs.map { it.v("U:$fin", "eMeanMw") })
                val em = mean(rs.map { it.v("U:$fin", "eMaxMw") })
                es += en
                ems += em
                val j = mean(rs.map { it.t("joinP95S") })
                val jr = j / mean(ref.map { it.t("joinP95S") })
                if (!jr.isNaN()) worst = if (worst.isNaN()) jr else maxOf(worst, jr)
                perCell += "${cellTitle(cell)}: ${f2(en)} mW (max ${f2(em)}), join p95 ${f2(j)} s (×${f2(jr)})"
            }
            if (es.isEmpty()) continue
            out += SchedPoint(c, s, es.average(), ems.average(), fails, worst, perCell, cond)
        }
        return out
    }

    /** Balanced = lowest-energy passing schedule; Responsive = the next higher duty. */
    fun picks(
        all: List<Run>,
        conditional: Boolean = false,
    ): List<Pair<String, String?>?> =
        (0..2).map { c ->
            val pts = sweepPoints(all, c)
            val ok = pts.filter { if (conditional) it.condOk else it.ok }
            if (ok.isEmpty()) return@map null
            val bal = ok.minBy { it.energy }.sched
            val byDuty = StageFPlan.schedules.sortedBy { duty(it) }
            val i = byDuty.indexOf(bal)
            bal to byDuty.getOrNull(i + 1)
        }

    private fun sweepSection(all: List<Run>): String {
        val sb = StringBuilder()
        val pk = picks(all)
        val ck = picks(all, conditional = true)
        for (c in 0..2) {
            val pts = sweepPoints(all, c)
            if (pts.isEmpty()) continue
            sb.append(
                "<h3>${classNames[c]} density class</h3><p>Pre-registered pick (criteria 1–4 as stated): " +
                    "${pk[c]?.let { "Balanced ${it.first.replace('-', '/')}, Responsive ${it.second?.replace('-', '/') ?: "none (no faster schedule swept)"}" } ?: "none passes"}. " +
                    "Conditional pick (failures already present at the interim schedule ignored; criterion 3 replaced by ≤ 1.25 × the final arm's busiest phone at interim): " +
                    "${ck[c]?.let { "Balanced ${it.first.replace('-', '/')}, Responsive ${it.second?.replace('-', '/') ?: "none (no faster schedule swept)"}" } ?: "none passes"}.</p>",
            )
            sb.append("<table><tr><th>schedule on/off s</th><th>duty</th><th>mean phone mW</th><th>busiest phone mW</th><th>worst join p95 ÷ interim</th><th class=l>criteria 1–4 (pre-registered)</th><th class=l>failures the schedule causes (conditional rule)</th><th class=l>per cell</th></tr>")
            for (p in pts.sortedBy { duty(it.sched) }) {
                val mark =
                    when (p.sched) {
                        pk[c]?.first -> " <b>Balanced</b>"
                        pk[c]?.second -> " <b>Responsive</b>"
                        else -> ""
                    } +
                    when (p.sched) {
                        ck[c]?.first -> " <b>Balanced (cond.)</b>"
                        ck[c]?.second -> " <b>Responsive (cond.)</b>"
                        else -> ""
                    } + if (p.sched == StageFPlan.INTERIM.split('_')[c]) " <i>(interim)</i>" else ""
                val cls = if (p.ok) "better" else "worse"
                sb.append(
                    "<tr><td>${p.sched.replace('-', '/')}$mark</td><td>${f2(duty(p.sched))}</td><td>${f2(p.energy)}</td><td>${f2(p.energyMax)}</td><td>${f2(p.joinRatioWorst)}</td>" +
                        "<td class=\"l $cls\">${if (p.fails.isEmpty()) "pass" else esc(p.fails.joinToString("; "))}</td>" +
                        "<td class=\"l ${if (p.condOk) "better" else "worse"}\">${if (p.condFails.isEmpty()) "none" else esc(p.condFails.joinToString("; "))}</td><td class=l><small>${esc(p.perCell.joinToString("; "))}</small></td></tr>",
                )
            }
            sb.append("</table>")
        }
        return sb.toString()
    }

    private fun energyTable(
        all: List<Run>,
        set: String,
        g: String,
        filter: (Run) -> Boolean = { true },
        extraCols: Boolean = false,
    ): String {
        val cols =
            listOf(
                "eMeanMw" to "mean mW", "eMaxMw" to "busiest mW", "eScanMw" to "scan", "eLinkMw" to "links", "eTopoMw" to "link control",
                "eRouteMw" to "routing bytes", "eBulkMw" to "bulk", "scanDuty" to "scan duty", "eMeanMwAndroid" to "Android mean",
                "eMeanMwIos" to "iOS mean (bounds only)",
            ) + if (extraCols) listOf("eMeanMwFg" to "foreground mean", "eMeanMwBg" to "background mean") else emptyList()
        val sb = StringBuilder("<table><tr><th class=l>cell</th><th class=l>schedule</th>")
        cols.forEach { sb.append("<th>${it.second}</th>") }
        sb.append("</tr>")
        val rsAll = all.filter { it.set == set && filter(it) }
        for (cell in cellOrder) for (sch in rsAll.map { it.sched }.distinct().sorted()) {
            val rs = rsAll.filter { it.cell == cell && it.sched == sch && it.inst.containsKey("U:$g") }
            if (rs.isEmpty()) continue
            sb.append("<tr><td class=l>${cellTitle(cell)}</td><td class=l>${sch.replace('-', '/').replace("_", " · ")}</td>")
            for ((m, _) in cols) sb.append("<td>${f2(mean(rs.map { it.v("U:$g", m) }))}</td>")
            sb.append("</tr>")
        }
        return sb.append("</table>").toString()
    }

    private fun confTable(
        all: List<Run>,
        set: String,
    ): String {
        val fin = StageFPlan.finalArm
        val sb = StringBuilder("<table>$CRIT_HEAD")
        val rsAll = all.filter { it.set == set }
        for (sch in rsAll.map { it.sched }.distinct().sorted()) for (cell in cellOrder) {
            val rs = rsAll.filter { it.cell == cell && it.sched == sch }
            val e = evaluate(rs, fin) ?: continue
            val j = mean(rs.map { it.t("joinP95S") })
            val ref = mean(all.filter { it.set == CSet.F_MAIN.label && it.cell == cell && it.sched == StageFPlan.INTERIM }.map { it.t("joinP95S") })
            sb.append(criteriaRow("${cellTitle(cell)} · ${sch.replace('-', '/').replace("_", " · ")} · join p95 ${f2(j)} s (×${f2(j / ref)} vs interim)", e))
        }
        return sb.append("</table>").toString()
    }

    private fun bgTable(all: List<Run>): String {
        val fin = StageFPlan.finalArm
        val rsAll = all.filter { it.set == CSet.F_BG.label }
        if (rsAll.isEmpty()) return "<p>Not run.</p>"
        val sb =
            StringBuilder(
                "<table><tr><th class=l>cell</th><th>unicast delivery</th><th>broadcast delivery</th><th>unicast p95 ms</th><th>first msg p95 cached C / PQ</th>" +
                    "<th>join p95 s</th><th>join median s</th><th>mean mW</th><th>foreground mean mW</th><th>background mean mW</th><th>busiest mW</th></tr>",
            )
        for (cell in cellOrder) {
            val rs = rsAll.filter { it.cell == cell }
            if (rs.isEmpty()) continue
            fun m(
                k: String,
                x: String,
            ) = mean(rs.map { it.v(k, x) })
            val u = "U:$fin"
            val b = "B:$fin"
            sb.append(
                "<tr><td class=l>${cellTitle(cell)}</td><td>${pct(m(u, "delivery"))}</td><td>${pct(m(b, "delivery"))}</td><td>${f0(m(u, "latP95Ms"))}</td>" +
                    "<td>${f0(m(u, "firstP95ClassicalMs"))} / ${f0(m(u, "firstP95PqMs"))}</td><td>${f2(mean(rs.map { it.t("joinP95S") }))}</td>" +
                    "<td>${f2(mean(rs.map { it.t("joinMedianS") }))}</td><td>${f2(m(u, "eMeanMw"))}</td><td>${f2(m(u, "eMeanMwFg"))}</td><td>${f2(m(u, "eMeanMwBg"))}</td><td>${f2(m(u, "eMaxMw"))}</td></tr>",
            )
        }
        return sb.append("</table>").toString()
    }

    fun render(
        rows: List<Map<String, String>>,
        notes: String,
    ): String {
        val all = runs(rows)
        val fin = StageFPlan.finalArm
        val sb = StringBuilder(HtmlReport.head("MeshLink routing simulation — stage F: catch-up, conversation tags, lookups, large messages, energy"))
        sb.append(
            """<h1>MeshLink routing simulation — stage F</h1>
            <p class="warn"><b>Every number on this page is simulated.</b> It comes from a simplified computer model of phones and
            Bluetooth links, calibrated against lab measurements (bearer bake-off, 2026-11). It is not a measurement on real devices.
            iPhone energy numbers use the Android coefficients and are bounds only.</p>
            <p>Stage F adds the accepted design's remaining mechanisms to the stage E final v1 one at a time and checks the
            pre-registered criteria: A1 full catch-up with holding for others and tombstones (ADR 0014), A2 per-conversation routing tags
            (ADR 0016), A3 certificate lookups from a cold cache (ADR 0018), A4 large messages (ADR 0015). It then tests a lookup rate
            limit, replaces the power index with an absolute energy model and sweeps the scan schedule per density class.</p>
            $notes""",
        )
        sb.append("<h2>1. Criteria per cell and arm (30 min runs, 30 min lifetime, interim scan schedule)</h2>")
        sb.append(
            """<p>Criteria: (1) unicast and broadcast delivery within a connected component ≥ 99% at low and medium, ≥ 95% at high, by the
            sender's true local density; at low density within 0.5 pp of flooding where flooding misses 99%; (2) idle control traffic,
            including catch-up summaries, claims and tombstones and the link layer's neighbor lists, ≤ 10% of link capacity; (3) the
            busiest phone's routing bytes sent ≤ 1.25 × A0's in the same cell (large-message bulk bytes excluded); (4) first message on
            ≤ 3 hops p95 ≤ 1 s classical / 1.5 s post-quantum with the certificate cached, ≤ 2 s / 3 s when a lookup was needed. Unicast
            p95 ≤ 1 s at medium/high is an aim, not a criterion. Values are means over seeds.</p>""",
        )
        sb.append(criteriaTable(all, CSet.F_MAIN.label, listOf("A0", "A1", "A2", "A3", "A4")))
        sb.append("<h3>Mechanism costs</h3>").append(costTable(all, CSet.F_MAIN.label, listOf("A0", "A1", "A2", "A3", "A4")))
        sb.append("<h3>Large messages (A4)</h3>").append(largeTable(all, CSet.F_MAIN.label, "A4"))
        if (all.any { it.set == CSet.F_A2L.label }) {
            sb.append("<h3>A2L fallback</h3>").append(criteriaTable(all, CSet.F_A2L.label, listOf("A0", "A1", "A2", "A2L")))
        }
        if (all.any { it.set == CSet.F_DIAG.label }) {
            sb.append(
                """<h3>Diagnostic, not pre-registered: relays forward only the first lookup reply</h3><p>A3D/A4D = A3/A4 where a relay
                forwards only the first lookup reply per request (salt) instead of every reply. Shows how much of A3/A4's busiest-phone
                increase comes from duplicate cache answers. Not part of the pre-registered arms; for the owner's decision only.</p>""",
            )
            sb.append(criteriaTable(all, CSet.F_DIAG.label, listOf("A0", "A3", "A3D", "A4D")))
            sb.append(costTable(all, CSet.F_DIAG.label, listOf("A0", "A3", "A3D", "A4D")))
        }
        sb.append("<h2>2. One-hour runs (1 h lifetime)</h2>").append(criteriaTable(all, CSet.F_LONG.label, listOf("A0", fin)))
        sb.append(costTable(all, CSet.F_LONG.label, listOf("A0", fin)))
        sb.append("<h2>3. Lookup rate limit</h2>")
        sb.append(
            """<p>One phone sends one lookup per second for random nonexistent identifiers. RL0: no limit; RL1: every relay allows each
            incoming link 1 route request or lookup per second (burst 5) and drops the excess; RL1N: the limit without the misbehaving
            phone. Differences are paired against the final arm without attacker and without limit in the same run (± 95% CI).
            The attacker is excluded from the busiest-phone figure.</p>""",
        )
        val rl = rlRows(all)
        sb.append("<table><tr><th class=l>cell</th><th class=l>arm</th><th>Δ unicast delivery</th><th>Δ broadcast delivery</th><th>Δ busiest phone</th><th>busiest B/s</th><th>lookup tx /node-min</th><th>dropped by limit</th><th>first msg after lookup p95 C / PQ</th><th class=l>criteria</th></tr>")
        rl.forEach { r -> sb.append("<tr>" + r.mapIndexed { i, v -> if (i < 2 || i == r.size - 1) "<td class=l>$v</td>" else "<td>$v</td>" }.joinToString("") + "</tr>") }
        sb.append("</table>")
        sb.append("<h2>4. Energy and scan schedules</h2>")
        sb.append(
            """<p>Energy above baseline per phone: 55 mW × scan duty, 0 for advertising, 1 mW per open link, 1.25 µJ per routed or
            control byte sent or received (incl. link control and handshakes), 0.8 mJ/KiB of large-message bulk bytes sent or received.
            Mean over phones online ≥ 60 s in the measured window (iOS asleep time counts as online time with no scanning). Final arm.</p>""",
        )
        sb.append(energyTable(all, CSet.F_MAIN.label, fin, { it.sched == StageFPlan.INTERIM }))
        sb.append("<h3>Schedule sweep per class</h3><p>Each row changes only that class's schedule (others interim). Balanced = lowest-energy schedule passing criteria 1–4 in every cell of the class with p95 join time ≤ 1.25 × interim; Responsive = next higher duty.</p>")
        sb.append(sweepSection(all))
        sb.append("<h3>Confirmation on dynamic scenarios</h3>").append(confTable(all, CSet.F_CONF.label))
        sb.append(energyTable(all, CSet.F_CONF.label, fin))
        sb.append("<h2>5. Background (report only)</h2><p>Half the phones backgrounded: Android scans 4 s every 34 s; iPhones keep the awake-10-s-in-60-s model. Balanced schedule.</p>")
        sb.append(bgTable(all)).append(energyTable(all, CSet.F_BG.label, fin, extraCols = true))
        sb.append("</main></body></html>\n")
        return sb.toString()
    }
}

fun stageFReport(out: File) {
    val rows = CSet.entries.filter { it.stageF }.flatMap { s -> CRaw.file(out, s).let { if (it.exists()) RawCsv.read(it) else emptyList() } }
    val notes = File(out, "findings.html").takeIf { it.exists() }?.readText() ?: ""
    File(out, "stage-f-report.html").writeText(StageFReport.render(rows, notes))
    println("wrote ${File(out, "stage-f-report.html")}")
    val all = StageFReport.runs(rows)
    StageFReport.picks(all).forEachIndexed { c, p -> println("class ${StageFReport.classNames[c]}: balanced/responsive = $p") }
    StageFReport.picks(all, conditional = true).forEachIndexed { c, p -> println("class ${StageFReport.classNames[c]}: conditional balanced/responsive = $p") }
}
