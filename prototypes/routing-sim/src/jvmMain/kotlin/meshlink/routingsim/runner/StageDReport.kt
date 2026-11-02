package meshlink.routingsim.runner

import meshlink.routingsim.runner.HtmlReport.barChart
import meshlink.routingsim.runner.HtmlReport.esc
import meshlink.routingsim.runner.HtmlReport.fmt
import java.io.File
import java.util.Locale

/** Stage D report: gap-closing changes on the stage C v1 configuration. Inline CSS and SVG only. */
object StageDReport {
    private val dynOrder =
        listOf(
            "arrival" to "dynamic", "departure" to "dynamic", "core-edge" to "dynamic", "surge" to "dynamic",
            "static" to "at10", "static" to "at50", "waypoint" to "at10", "waypoint" to "at50",
        )
    private val scenarioNames =
        mapOf(
            "arrival|dynamic" to "A arrival (low → high)",
            "departure|dynamic" to "B departure (high → low)",
            "core-edge|dynamic" to "C dense core + sparse edge",
            "surge|dynamic" to "D surge (3× in 60 s)",
            "static|at10" to "E standing at low/medium (10)",
            "static|at50" to "E standing at medium/high (50)",
            "waypoint|at10" to "E walking around 10",
            "waypoint|at50" to "E walking around 50",
        )
    private val classes = listOf("low", "medium", "high")
    private val v1U = "UA ${StageDPlan.V1}"
    private val candU = "UA ${StageDPlan.FINAL}"
    private val v1B = "BA ${StageDPlan.FINAL_BROADCAST}"

    /** Instance name: protocol (B1B3 shown as BA) plus the stage D variant flags. */
    fun inst(r: Map<String, String>): String {
        val p = r["protocol"] ?: ""
        val base = if (p == "B1B3") "BA" else p
        val v = r["variant"] ?: "-"
        return if (v == "-" || v.isEmpty()) base else "$base $v"
    }

    private fun AggregateRow.k(name: String) = key[name] ?: ""

    private fun cellOf(r: Map<String, String>) = listOf(r["scenario"], r["density"], r["nodes"], r["platform"]).joinToString("|")

    private class Config(
        val name: String,
        val u: String,
        val b: String,
    ) {
        val label get() = "$u · $b"
    }

    private class Eval(
        val failures: List<String>,
        val load: Double,
        val ctrl: Double,
        val u: AggregateRow,
        val b: AggregateRow,
    ) {
        val pass get() = failures.isEmpty()
    }

    private class Index(
        rows: List<AggregateRow>,
    ) {
        private val map = HashMap<String, AggregateRow>()

        init {
            for (r in rows) map["${cellOf(r.key)}|${inst(r.key)}"] = r
        }

        fun get(
            cell: String,
            inst: String,
        ) = map["$cell|$inst"]
    }

    /** Stage C criteria (amended low-density rule) by the sender's true local density at send. */
    private fun failures(
        x: AggregateRow,
        ref: AggregateRow?,
        tag: String,
    ): List<String> {
        val out = ArrayList<String>()
        if (x.m("n_low") >= 1) {
            val d = x.m("del_low")
            val rd = ref?.m("del_low") ?: Double.NaN
            if (!rd.isNaN() && (d < rd - 0.005 || (rd >= 0.99 && d < 0.99))) out.add("$tag low ${pct(d)} vs flood ${pct(rd)}")
        }
        if (x.m("n_medium") >= 1 && x.m("del_medium") < 0.99) out.add("$tag medium ${pct(x.m("del_medium"))}")
        if (x.m("n_high") >= 1 && x.m("del_high") < 0.95) out.add("$tag high ${pct(x.m("del_high"))}")
        val c = x.m("firstP95ClassicalMs")
        val pq = x.m("firstP95PqMs")
        if (!c.isNaN() && c > 1000) out.add("$tag first-message p95 ${fmt(c)} ms")
        if (!pq.isNaN() && pq > 1500) out.add("$tag first-message (PQ) p95 ${fmt(pq)} ms")
        return out
    }

    private fun evaluate(
        idx: Index,
        cell: String,
        c: Config,
    ): Eval? {
        val u = idx.get(cell, c.u) ?: return null
        val b = idx.get(cell, c.b) ?: return null
        val f = failures(u, idx.get(cell, "U1"), "unicast") + failures(b, idx.get(cell, "B1"), "broadcast")
        val idle = u.m("idleCtrlShare").orZero() + b.m("idleCtrlShare").orZero() + u.m("t_controlShareOfCapacity").orZero()
        val all = if (idle > 0.10) f + "idle control ${pct(idle)} of capacity" else f
        val ctrl = u.m("ctrlBytesPerNodeS").orZero() + b.m("ctrlBytesPerNodeS").orZero() + u.m("t_controlBytesPerNodeS").orZero()
        return Eval(all, u.m("loadMaxBps") + b.m("loadMaxBps"), ctrl, u, b)
    }

    private fun Double.orZero() = if (isNaN()) 0.0 else this

    private fun pct(v: Double) = if (v.isNaN()) "–" else String.format(Locale.ROOT, "%.2f%%", v * 100)

    private fun mcFmt(
        r: AggregateRow?,
        m: String,
    ): String {
        val v = r?.m(m) ?: return "–"
        if (v.isNaN()) return "–"
        val c = r.c(m)
        val ratio = m.startsWith("del")
        val s = if (ratio) pct(v) else fmt(v)
        return if (c.isNaN()) s else "$s ± ${if (ratio) String.format(Locale.ROOT, "%.2f", c * 100) else fmt(c)}"
    }

    private fun cellTitle(cell: String): String {
        val p = cell.split('|')
        val name = scenarioNames["${p[0]}|${p[1]}"] ?: "${p[1]} density, ${p[0]}"
        return "$name — N = ${p[2]}, ${if (p[3] == "android") "Android only" else "half iOS"}"
    }

    private val flood = Config("flooding everywhere", "U1", "B1")
    private val stageC = Config("stage C adaptive", "UA", "BA")
    private val stageD = Config("stage D v1", v1U, v1B)
    private val pinned = Config("pinned low class", "UA PIN", "BA PIN+CU")
    private val cand = Config("pre-registered candidate", candU, v1B)

    fun render(
        dir: File,
        main: List<AggregateRow>,
        rawMain: List<Map<String, String>>,
        conv: List<AggregateRow>,
        big: List<AggregateRow>,
        life: List<AggregateRow>,
        pin: List<AggregateRow>,
        lifeV1: List<AggregateRow>,
        bigV1: List<AggregateRow>,
        ios4: List<AggregateRow> = emptyList(),
    ): String {
        val idx = Index(main + pin)
        val sb = StringBuilder(HtmlReport.head("MeshLink routing simulation — stage D: closing the gaps"))
        sb.append(intro(rawMain, File(dir, "run-notes.txt").takeIf { it.exists() }))
        File(dir, "findings.html").takeIf { it.exists() }?.let { sb.append("<h2>What we found</h2>").append(it.readText()) }
        val dynCells = dynOrder.flatMap { (m, d) -> listOf("android", "half-ios").map { p -> "$m|$d|200|$p" } }
        val staticCells =
            main.filter { it.k("density") in classes }.map { cellOf(it.key) }.distinct()
                .sortedWith(compareBy({ classes.indexOf(it.split('|')[1]) }, { it.split('|')[2].toInt() }, { it }))
        sb.append(scoreboard(idx, dynCells, staticCells, main))
        sb.append(criteriaTable(idx, dynCells, staticCells))
        sb.append(keepSection(main, conv, dynCells, staticCells))
        sb.append(sparseSection(idx, staticCells, life, lifeV1))
        sb.append(bigSection(big, bigV1))
        sb.append(broadcastSection(idx, dynCells + staticCells))
        sb.append(pinSection(idx, dynCells))
        sb.append(ios4Section(ios4))
        sb.append(glossary())
        sb.append("</main></body></html>\n")
        return sb.toString()
    }

    private fun intro(
        raw: List<Map<String, String>>,
        notes: File?,
    ): String {
        val runs = raw.map { it["runId"] }.toSet().size
        return """
            <h1>MeshLink routing simulation — stage D: closing the gaps</h1>
            <p class="warn"><b>Every number on this page is simulated.</b> It comes from a simplified computer model of phones and
            Bluetooth links, calibrated against a few lab measurements. It is not a measurement on real devices.</p>
            <h2>The question</h2>
            <p>Stage C chose an adaptive v1 configuration: each phone estimates how many phones are around it and picks low, medium or
            high settings on its own. Three gaps were left. Stage D tests targeted fixes on top of the stage C v1 configuration
            (relay preference off, since it was dropped from v1):</p>
            <ol>
            <li><b>Slow unicast at medium and high density.</b> The 95th-percentile delivery time (p95) for one-to-one messages was
            2–3 s; the aim is ≤ 1 s. Tested: keeping routes alive while two phones talk (K1), refreshing routes when a delivery ack comes
            back (K2), keeping the reverse path cached (K3), and a faster route search for distant phones (F).</li>
            <li><b>Phones that are actually alone but classify as medium.</b> A few medium-density cells lost 0.5–1 pp for senders who
            were really in a sparse spot. Tested: a phone with fewer than 5 phones nearby (its own estimate) always floods its own
            messages (S5).</li>
            <li><b>Broadcast in a large half-iOS mesh.</b> At N = 1000 with half the phones on iOS (often asleep), broadcast at medium
            density stayed just under 99%. Tested: a shorter repair timeout (FG) and a catch-up announcement when a link comes up
            (CU).</li>
            </ol>
            <p>Also shown: the <b>pinned low class</b> preset (every phone uses the low-density settings — flood everything — whatever
            its density), as a low-latency reference. Criteria are those of stage C, applied by the sender’s true local density at send:
            delivery within 0.5 pp of flooding (and ≥ 99% where flooding reaches it) at low density, ≥ 99% at medium, ≥ 95% at high;
            first-message p95 ≤ 1 s (≤ 1.5 s post-quantum); idle control traffic ≤ 10% of capacity; then the lowest busiest-phone load,
            then the lowest p95.</p>
            <p class="note">$runs simulation runs in the main set. Tables show the mean over seeds and a 95% confidence interval (±).
            Unless stated otherwise: N = 200 phones, lifetime 5 min, minimum hold 10 s, clock skew ±30 s, identifiers rotate every 15 min,
            adaptive topology (6 links at low, 5 at medium and high). All protocol variants of one run share the same simulated phones,
            movements and links, so differences between variants are paired.</p>
            ${notes?.let { "<details><summary>Run notes (what was run, what was cut and why)</summary><pre>${esc(it.readText())}</pre></details>" } ?: ""}
            """.trimIndent()
    }

    private fun scoreboard(
        idx: Index,
        dyn: List<String>,
        stat: List<String>,
        main: List<AggregateRow>,
    ): String {
        val us = main.filter { it.k("protocol") in listOf("U1", "UA") }.map { inst(it.key) }.distinct().sorted()
        val configs =
            listOf(flood, stageC, stageD, cand, pinned) +
                us.filter { it != "U1" && it != v1U && it != candU }.map { Config("", it, v1B) } + listOf(Config("", v1U, "BA"), Config("", v1U, "BA FG"))
        val sb = StringBuilder("<h2>Scoreboard: configurations against the criteria</h2>")
        sb.append(
            """<p>Each row is one complete configuration (unicast · broadcast). “Cells passed” counts cells in which every criterion
            holds: 16 dynamic scenarios (N = 200, both platform mixes) and 40 fixed-density cells (N = 50 and 200, four movement
            patterns, both platform mixes). The pinned preset ran only in the dynamic scenarios. p95 medium / high = mean over cells of
            the unicast p95 for senders in that class; “cells ≤ 1 s” counts cells in which the medium and high unicast p95 are both at
            most 1 s.</p>""",
        )
        sb.append("<table><tr><th class=l>configuration</th><th>dynamic passed</th><th>fixed passed</th><th>busiest phone B/s</th><th>control B/node·s</th><th>unicast p95 medium ms</th><th>unicast p95 high ms</th><th>cells ≤ 1 s (medium+high)</th><th class=l>most common failure</th></tr>")
        for (c in configs) {
            val evDyn = dyn.mapNotNull { evaluate(idx, it, c) }
            val evStat = stat.mapNotNull { evaluate(idx, it, c) }
            val all = evDyn + evStat
            if (all.isEmpty()) continue
            val fails = all.flatMap { e -> e.failures.map { it.split(' ').take(2).joinToString(" ") } }
            val common = fails.groupingBy { it }.eachCount().maxByOrNull { it.value }?.let { "${it.key} (${it.value}×)" } ?: ""
            val pm = all.map { it.u.m("p95_medium") }.filter { !it.isNaN() }
            val ph = all.map { it.u.m("p95_high") }.filter { !it.isNaN() }
            val fast =
                all.filter { !it.u.m("p95_medium").isNaN() || !it.u.m("p95_high").isNaN() }
                    .let { l -> "${l.count { e -> listOf("p95_medium", "p95_high").all { m -> e.u.m(m).let { v -> v.isNaN() || v <= 1000 } } }}/${l.size}" }
            val hl = c.name.isNotEmpty()
            sb.append("<tr${if (c === stageD) " class=better" else ""}><td class=l>${if (hl) "<b>${esc(c.name)}</b><br>" else ""}${esc(c.label)}</td>")
            sb.append("<td>${evDyn.count { it.pass }}/${evDyn.size}</td><td>${evStat.count { it.pass }}/${evStat.size}</td>")
            sb.append("<td>${fmt(all.map { it.load }.average())}</td><td>${fmt(all.map { it.ctrl }.average())}</td>")
            sb.append("<td>${fmt(pm.average())}</td><td>${fmt(ph.average())}</td><td>$fast</td><td class=l>${esc(common)}</td></tr>")
        }
        return sb.append("</table>").toString()
    }

    private fun criteriaTable(
        idx: Index,
        dyn: List<String>,
        stat: List<String>,
    ): String {
        val configs = listOf(flood, stageC, stageD, cand, pinned)
        val sb = StringBuilder("<h2>Criteria check per cell</h2>")
        sb.append(
            """<p>Green: every criterion met; red: the failures are listed. The p95 columns are the stage D v1 unicast p95 for senders in
            the medium and high class. The pinned preset ran only in the dynamic scenarios.</p>""",
        )
        sb.append("<table><tr><th class=l>cell</th>")
        for (c in configs) sb.append("<th class=l>${esc(c.name)}<br><span class=note>${esc(c.label)}</span></th>")
        sb.append("<th>v1 p95 medium ms</th><th>v1 p95 high ms</th><th>v1 busiest phone B/s</th></tr>")
        for (cell in dyn + stat) {
            sb.append("<tr><td class=l>${esc(cellTitle(cell))}</td>")
            for (c in configs) {
                val e = evaluate(idx, cell, c)
                when {
                    e == null -> sb.append("<td>–</td>")
                    e.pass -> sb.append("<td class=better>met</td>")
                    else -> sb.append("<td class=\"worse l\">${esc(e.failures.joinToString("; "))}</td>")
                }
            }
            val e = evaluate(idx, cell, stageD)
            sb.append("<td>${mcFmt(e?.u, "p95_medium")}</td><td>${mcFmt(e?.u, "p95_high")}</td><td>${e?.let { fmt(it.load) } ?: "–"}</td></tr>")
        }
        return sb.append("</table>").toString()
    }

    private fun groupLabel(cell: String) =
        cell.split('|').let { if (it[1] in classes) "fixed ${it[1]}" else "dynamic" }

    /** Mean over cells of per-cell means for one unicast instance. */
    private fun avg(
        rows: List<AggregateRow>,
        metric: String,
    ) = rows.map { it.m(metric) }.filter { !it.isNaN() }.let { if (it.isEmpty()) Double.NaN else it.average() }

    private fun keepSection(
        main: List<AggregateRow>,
        conv: List<AggregateRow>,
        dyn: List<String>,
        stat: List<String>,
    ): String {
        val sb = StringBuilder("<h2>1. Faster unicast at medium and high density</h2>")
        sb.append(
            """<p>Variants (each on top of stage C adaptive unicast, UA):
            <b>K1</b> keeps a route alive for 3 min after the last message between the pair;
            <b>K2</b> the recipient sends a small delivery ack back along the route, which refreshes the route at every hop (and, if the
            ack does not arrive in time, the sender re-sends once by a new route search);
            <b>K3</b> every phone on the way keeps the reverse path to the sender for 3 min;
            <b>F</b> when no route is known, the route search goes out to the whole mesh at once, alongside the short 3-hop flood of the
            message itself, instead of widening step by step (1, 3, 5, 7 hops, then everyone).
            Near = recipient 1–3 hops away, far = more than 3 hops. “Repeat” = messages to a recipient the sender has written to
            before. Standard traffic: each phone writes to 3 contacts, one message every 4 min on average, so a given pair talks about
            every 12 min and routes usually expire in between. Conversation traffic: the recipient replies with probability 0.6 after
            30 s on average (chains continue), the case route keeping is designed for.</p>""",
        )
        val metrics =
            listOf(
                "p95_medium" to "p95 medium ms", "p95_high" to "p95 high ms", "p95_near" to "p95 near ms", "p95_far" to "p95 far ms",
                "p95_repeat" to "p95 repeat ms", "firstP95ClassicalMs" to "first-msg p95 ms", "delivery" to "delivery",
                "ctrlBytesPerNodeS" to "control B/node·s", "loadMaxBps" to "busiest phone B/s",
            )
        for ((title, rows, cells) in listOf(
            Triple("Standard traffic — dynamic scenarios (N = 200)", main, dyn),
            Triple("Standard traffic — fixed medium and high cells", main, stat.filter { it.split('|')[1] != "low" }),
            Triple("Conversation traffic — fixed medium/high (N = 200) and arrival/core-edge", conv, null),
        )) {
            val sel = rows.filter { it.k("protocol") in listOf("U1", "UA") && (cells == null || cellOf(it.key) in cells) }
            if (sel.isEmpty()) continue
            val insts = sel.map { inst(it.key) }.distinct().sortedWith(compareBy({ if (it == "U1") 0 else 1 }, { it.length }, { it }))
            val nCells = sel.map { cellOf(it.key) }.distinct().size
            sb.append("<h3>${esc(title)} — mean over $nCells cells</h3><table><tr><th class=l>unicast</th>")
            for ((_, l) in metrics) sb.append("<th>${esc(l)}</th>")
            sb.append("<th>cells with medium and high p95 ≤ 1 s</th></tr>")
            for (i in insts) {
                val rs = sel.filter { inst(it.key) == i }
                sb.append("<tr${if (i == v1U) " class=better" else ""}><td class=l>${esc(i)}</td>")
                for ((m, _) in metrics) sb.append("<td>${if (m == "delivery") pct(avg(rs, m)) else fmt(avg(rs, m))}</td>")
                val relevant = rs.filter { !it.m("p95_medium").isNaN() || !it.m("p95_high").isNaN() }
                val ok = relevant.count { r -> listOf("p95_medium", "p95_high").all { m -> r.m(m).let { it.isNaN() || it <= 1000 } } }
                sb.append("<td>$ok/${relevant.size}</td></tr>")
            }
            sb.append("</table>")
            if (rows === main && cells == dyn) {
                val show = listOf("U1", "UA", "UA K3", "UA F", "UA F+K1+K2+K3", v1U).filter { it in insts }
                val labels = cells.map { c -> c.split('|').let { "${it[0].take(5)} ${it[1].removePrefix("dynamic")} ${if (it[3] == "android") "A" else "A/i"}".trim() } to "" }
                val series = show.associateWith { i -> cells.map { c -> sel.firstOrNull { cellOf(it.key) == c && inst(it.key) == i }?.let { it.m("p95_medium") to it.c("p95_medium") } } }
                val pal = show.zip(listOf("#718096", "#d69e2e", "#6b46c1", "#2b6cb0", "#63b3ed", "#2f855a")).toMap()
                sb.append(barChart("Unicast p95, medium-class senders", "milliseconds, lower is better; dashed line = 1 s", "", labels, series, pal, 1000.0))
                sb.append("<p class=note>Legend: ${show.joinToString(" · ") { "<span style=\"color:${pal[it]}\">■</span> ${esc(it)}" }}</p>")
            }
        }
        return sb.toString()
    }

    private fun sparseSection(
        idx: Index,
        stat: List<String>,
        life: List<AggregateRow>,
        lifeV1: List<AggregateRow>,
    ): String {
        val sb = StringBuilder("<h2>2. Sparse senders flood their own messages (S5)</h2>")
        sb.append(
            """<p>With S5, a phone whose own estimate is below 5 phones nearby floods the messages it sends itself, whatever its class.
            The table shows delivery for messages whose sender was truly in a low-density spot at the time of sending, in the fixed
            medium cells (where the sender’s class is usually medium). Red: more than 0.5 pp behind flooding (U1).</p>""",
        )
        val us = listOf("U1", "UA", "UA S5", candU, v1U)
        sb.append("<table><tr><th class=l>cell</th><th>messages from truly low senders per run</th>")
        for (u in us) sb.append("<th>${esc(u)}</th>")
        sb.append("</tr>")
        for (cell in stat.filter { it.split('|')[1] == "medium" }) {
            val ref = idx.get(cell, "U1")?.m("del_low") ?: Double.NaN
            sb.append("<tr><td class=l>${esc(cellTitle(cell))}</td><td>${fmt(idx.get(cell, "UA")?.m("n_low") ?: Double.NaN)}</td>")
            for (u in us) {
                val r = idx.get(cell, u)
                val v = r?.m("del_low") ?: Double.NaN
                val cls = if (v.isNaN() || ref.isNaN() || u == "U1") "" else if (v < ref - 0.005) "worse" else "better"
                sb.append("<td class=$cls>${mcFmt(r, "del_low")}</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</table>")
        if (life.isNotEmpty()) {
            sb.append(
                """<h3>Short lifetime: 120 s with ±30 s clock skew</h3><p>Adaptive topology, N = 200, 10 seeds, identifiers rotating.
                Minimum hold = a phone only takes a message into store-and-forward if at least this much lifetime is left. Overall unicast
                delivery; in brackets, delivery for truly low senders. Stage C found the sparse corridor at 120 s / 60 s hold at 98.54%
                (UA) vs 99.00% (U1). Green: within 0.5 pp of U1 in the same runs and ≥ 99% where U1 reaches 99%.</p>""",
            )
            sb.append(lifeTable(life, listOf("U1", "UA", "UA S5", candU)))
        }
        if (lifeV1.isNotEmpty()) {
            sb.append("<p>Follow-up runs (same cells and seeds, separate runs with their own U1 reference): full-range discovery alone (F) and the recommended v1 unicast ($v1U).</p>")
            sb.append(lifeTable(lifeV1, listOf("U1", "UA F", v1U)))
        }
        return sb.toString()
    }

    private fun lifeTable(
        life: List<AggregateRow>,
        insts: List<String>,
    ): String {
        val sb = StringBuilder()
        val holds = life.map { it.k("holdS") }.distinct().sortedBy { it.toInt() }
        sb.append("<table><tr><th class=l>scenario</th><th class=l>platform</th><th class=l>hold</th>")
        for (i in insts) sb.append("<th>${esc(i)}</th>")
        sb.append("</tr>")
        for (cell in life.map { cellOf(it.key) }.distinct()) {
            for (h in holds) {
                val p = cell.split('|')
                sb.append("<tr><td class=l>${esc(scenarioNames["${p[0]}|${p[1]}"] ?: "${p[1]} ${p[0]}")}</td><td class=l>${p[3]}</td><td class=l>$h s</td>")
                val ref = life.firstOrNull { cellOf(it.key) == cell && it.k("holdS") == h && inst(it.key) == "U1" }?.m("delivery") ?: Double.NaN
                for (i in insts) {
                    val r = life.firstOrNull { cellOf(it.key) == cell && it.k("holdS") == h && inst(it.key) == i }
                    val v = r?.m("delivery") ?: Double.NaN
                    val cls = if (v.isNaN() || i == "U1") "" else if (v < ref - 0.005 || (ref >= 0.99 && v < 0.99)) "worse" else "better"
                    sb.append("<td class=$cls>${mcFmt(r, "delivery")}<br><span class=note>(${mcFmt(r, "del_low")})</span></td>")
                }
                sb.append("</tr>")
            }
        }
        return sb.append("</table>").toString()
    }

    private fun bigSection(
        big: List<AggregateRow>,
        bigV1: List<AggregateRow>,
    ): String {
        if (big.isEmpty()) return ""
        val sb = StringBuilder("<h2>3. Broadcast in the large half-iOS mesh (N = 1000, arrival)</h2>")
        sb.append(
            """<p>Half of the phones are iOS; half of those are in the background and only awake for 10 s every 60 s, and a sleeping
            phone drops its links. <b>FG</b>: the tree-repair (graft) timeout goes from the stage C value down to 150 ms. <b>CU</b>
            (catch-up): whenever a link comes up, both sides announce the ids of the messages they received in the last 60 s; the other
            side pulls any it is missing. Same messages on the air as before. B1 = pure flooding. 5 seeds.</p>""",
        )
        sb.append("<table><tr><th class=l>broadcast</th><th>delivery</th><th>sender low</th><th>sender medium</th><th>sender high</th><th>p95 ms</th><th>control B/node·s</th><th>busiest phone B/s</th></tr>")
        for (r in big.filter { it.k("protocol") != "UA" }.sortedBy { inst(it.key) }) {
            val okMed = r.m("del_medium") >= 0.99
            sb.append("<tr><td class=l>${esc(inst(r.key))}</td><td>${mcFmt(r, "delivery")}</td><td>${mcFmt(r, "del_low")}</td>")
            sb.append("<td class=${if (okMed) "better" else "worse"}>${mcFmt(r, "del_medium")}</td><td>${mcFmt(r, "del_high")}</td>")
            sb.append("<td>${mcFmt(r, "latP95Ms")}</td><td>${mcFmt(r, "ctrlBytesPerNodeS")}</td><td>${mcFmt(r, "loadMaxBps")}</td></tr>")
        }
        sb.append("</table>")
        val us = big.filter { it.k("protocol") == "UA" } + bigV1
        if (us.isNotEmpty()) {
            sb.append("<h3>Unicast at N = 1000 (same scenario; the follow-up rows are separate runs with the same seeds)</h3>")
            sb.append("<table><tr><th class=l>unicast</th><th>delivery</th><th>sender medium</th><th>sender high</th><th>p95 medium ms</th><th>p95 high ms</th><th>first-msg p95 ms</th><th>control B/node·s</th><th>busiest phone B/s</th></tr>")
            for (r in us) {
                sb.append("<tr><td class=l>${esc(inst(r.key))}${if (r in bigV1) " (follow-up)" else ""}</td>")
                for (m in listOf("delivery", "del_medium", "del_high", "p95_medium", "p95_high", "firstP95ClassicalMs", "ctrlBytesPerNodeS", "loadMaxBps")) sb.append("<td>${mcFmt(r, m)}</td>")
                sb.append("</tr>")
            }
            sb.append("</table>")
        }
        return sb.toString()
    }

    private fun broadcastSection(
        idx: Index,
        cells: List<String>,
    ): String {
        val bs = listOf("B1", "BA", "BA FG", "BA CU")
        val sb = StringBuilder("<h2>Broadcast variants at N = 200 (dynamic and fixed-density cells)</h2>")
        sb.append("<p>Mean over cells of the per-cell means; “cells ≥ criterion” counts cells where broadcast meets its delivery criteria.</p>")
        sb.append("<table><tr><th class=l>group</th><th class=l>broadcast</th><th>delivery</th><th>sender medium</th><th>p95 ms</th><th>control B/node·s</th><th>busiest phone B/s</th><th>cells ≥ criterion</th></tr>")
        for (g in listOf("dynamic", "fixed low", "fixed medium", "fixed high")) {
            val cs = cells.filter { groupLabel(it) == g }
            for (b in bs) {
                val rs = cs.mapNotNull { idx.get(it, b) }
                if (rs.isEmpty()) continue
                val ok = cs.count { c -> idx.get(c, b)?.let { failures(it, idx.get(c, "B1"), "b").isEmpty() } == true }
                sb.append("<tr><td class=l>$g</td><td class=l>${esc(b)}</td><td>${pct(avg(rs, "delivery"))}</td><td>${pct(avg(rs, "del_medium"))}</td>")
                sb.append("<td>${fmt(avg(rs, "latP95Ms"))}</td><td>${fmt(avg(rs, "ctrlBytesPerNodeS"))}</td><td>${fmt(avg(rs, "loadMaxBps"))}</td><td>$ok/${cs.size}</td></tr>")
            }
        }
        return sb.append("</table>").toString()
    }

    private fun pinSection(
        idx: Index,
        dyn: List<String>,
    ): String {
        val sb = StringBuilder("<h2>Pinned low class: the low-latency preset</h2>")
        sb.append(
            """<p>Every phone uses the stage C low-class settings whatever its density: unicast and broadcast flood, 6 links, scanning
            and advertising at the low-class (most active) duty cycle, catch-up on. Shown next to the stage D v1 adaptive configuration
            and pure flooding on the adaptive topology. Radio power index: relative radio-on time from the scan and advertising duty
            cycle (an assumption, not a measurement; see the stage C report).</p>""",
        )
        val configs = listOf(stageD, pinned, flood)
        val metrics =
            listOf(
                "u:delivery" to "unicast delivery", "u:latP95Ms" to "unicast p95 ms", "u:p95_medium" to "… medium ms", "u:p95_high" to "… high ms",
                "u:firstP95ClassicalMs" to "first-msg p95 ms", "b:delivery" to "broadcast delivery", "b:latP95Ms" to "broadcast p95 ms",
                "load" to "busiest phone B/s", "ctrl" to "control B/node·s", "u:t_powerIndex" to "radio power index",
            )
        sb.append("<table><tr><th class=l>scenario</th><th class=l>configuration</th>")
        for ((_, l) in metrics) sb.append("<th>${esc(l)}</th>")
        sb.append("<th class=l>criteria</th></tr>")
        for (cell in dyn) {
            for (c in configs) {
                val e = evaluate(idx, cell, c) ?: continue
                sb.append("<tr><td class=l>${esc(cellTitle(cell))}</td><td class=l>${esc(c.name)}</td>")
                for ((m, _) in metrics) {
                    val v =
                        when {
                            m == "load" -> fmt(e.load)
                            m == "ctrl" -> fmt(e.ctrl)
                            m.startsWith("u:") -> mcFmt(e.u, m.substring(2))
                            else -> mcFmt(e.b, m.substring(2))
                        }
                    sb.append("<td>$v</td>")
                }
                sb.append("<td class=${if (e.pass) "better" else "\"worse l\""}>${if (e.pass) "met" else esc(e.failures.joinToString("; "))}</td></tr>")
            }
        }
        return sb.append("</table>").toString()
    }

    private val calibNames =
        linkedMapOf(
            "base" to "model calibration (stages A–D)",
            "base+cap4" to "model calibration, iOS ≤ 4 inbound",
            "meas" to "bake-off calibration",
            "meas+cap4" to "bake-off calibration, iOS ≤ 4 inbound",
            "meas+cap4+hide" to "bake-off, iOS ≤ 4 inbound, hide when full",
            "base+cap3+hide" to "model calibration, iOS ≤ 3 inbound, undiscoverable at 3",
            "meas+cap3+hide" to "bake-off calibration, iOS ≤ 3 inbound, undiscoverable at 3",
        )

    /** Cap 3 vs cap 4 (stage D addendum 2): [rows] holds both sets' aggregates. */
    fun ios3Html(rows: List<AggregateRow>, notes: String): String {
        val sb = StringBuilder(HtmlReport.head("MeshLink routing simulation — iPhone incoming-link cap 3 vs 4"))
        sb.append(notes)
        sb.append(ios4Section(rows, listOf("base", "base+cap4", "base+cap3+hide", "meas", "meas+cap4", "meas+cap3+hide"), intro = false))
        sb.append(glossary()).append("</main></body></html>\n")
        return sb.toString()
    }

    private fun ios4Section(
        ios4: List<AggregateRow>,
        calibs: List<String> = listOf("base", "base+cap4", "meas", "meas+cap4", "meas+cap4+hide"),
        intro: Boolean = true,
    ): String {
        if (ios4.isEmpty()) return ""
        val sb = StringBuilder(if (intro) "<h2>Recheck: iPhones keep at most 4 incoming links</h2>" else "<h2>Results</h2>")
        if (intro) sb.append(
            """<p>The bearer bake-off (real devices) found that an iPhone in the peripheral role keeps at most 4 incoming links and
            closes the rest itself. Phones that start a link (central role) reached 7–9 links. Here an iOS phone refuses every incoming
            connection beyond 4 (the phone that dialed sees a failed connection). Outgoing links are unchanged. The recommended v1
            (UA F+K2 · BA CU, degree 6 / 5 / 5) runs in the half-iOS mix: medium static (N = 50 and 200), high static (N = 200) and the
            arrival scenario (N = 200), 5 seeds each.</p>
            <p>The <b>bake-off calibration</b> replaces model values that differ materially from the measurements:</p>
            <ul><li>link throughput and RTT now depend on the phone that dials: Android 47–79 KB/s and 30–45 ms, iPhone 19–31 KB/s
            and 60 ms (before: 62–89 KB/s and 34–56 ms for all);</li>
            <li>GATT connection set-up 1.3–3.7 s (before 0.7–1.2 s);</li>
            <li>handshake: post-quantum XXhfs 101–255 ms (before classical XX 55–90 ms);</li>
            <li>a failed connection attempt is retried up to 3 times inside the same dial, after 500 ms × attempt (before: the dial
            failed and the phone picked another peer at its next 2 s step).</li></ul>
            <p><b>hide when full</b>: an iOS phone stops advertising while it holds 4 incoming links, so fewer phones try to dial it
            (the candidate fix). <b>Refused dials</b>: incoming connections refused by a full iPhone, per phone per minute. All
            numbers are simulated.</p>""",
        )
        val cells = ios4.map { cellOf(it.key) }.distinct().sortedWith(compareBy({ it.split('|')[1] == "dynamic" }, { classes.indexOf(it.split('|')[1]) }, { it.split('|')[2].toInt() }))
        val metrics =
            listOf(
                "u:delivery" to "unicast delivery", "u:p95_medium" to "unicast p95 medium ms", "u:p95_high" to "… high ms",
                "u:firstP95PqMs" to "first-msg p95 PQ ms", "b:delivery" to "broadcast delivery", "load" to "busiest phone B/s (v1)",
                "fload" to "busiest phone B/s (flooding)", "ctrl" to "control B/node·s", "u:t_iosLinks" to "links per iPhone",
                "u:t_iosInbound" to "… of them incoming", "u:t_androidLinks" to "links per Android", "u:t_capRefusalsPerNodeMin" to "refused dials /phone·min",
                "u:t_lcc" to "largest group",
            )
        sb.append("<table><tr><th class=l>cell</th><th class=l>calibration</th>")
        for ((_, l) in metrics) sb.append("<th>${esc(l)}</th>")
        sb.append("<th class=l>v1 criteria</th><th class=l>flooding criteria</th></tr>")
        for (cell in cells) {
            for (cal in calibs) {
                val name = calibNames[cal] ?: cal
                val idx = Index(ios4.filter { it.k("drive") == cal })
                val e = evaluate(idx, cell, stageD) ?: continue
                val f = evaluate(idx, cell, flood)
                sb.append("<tr><td class=l>${esc(cellTitle(cell))}</td><td class=l>${esc(name)}</td>")
                for ((m, _) in metrics) {
                    val v =
                        when {
                            m == "load" -> fmt(e.load)
                            m == "fload" -> f?.let { fmt(it.load) } ?: "–"
                            m == "ctrl" -> fmt(e.ctrl)
                            m.startsWith("u:") -> mcFmt(e.u, m.substring(2))
                            else -> mcFmt(e.b, m.substring(2))
                        }
                    sb.append("<td>$v</td>")
                }
                for (x in listOf(e, f)) {
                    if (x == null) {
                        sb.append("<td>–</td>")
                    } else {
                        sb.append("<td class=${if (x.pass) "better" else "\"worse l\""}>${if (x.pass) "met" else esc(x.failures.joinToString("; "))}</td>")
                    }
                }
                sb.append("</tr>")
            }
        }
        return sb.append("</table>").toString()
    }

    private fun glossary() =
        """
        <h2>Words used here</h2>
        <ul>
        <li><b>U1</b>: unicast by managed flood. <b>UA</b>: stage C adaptive unicast (flood in the low class, hybrid route-or-short-flood
        in medium and high). <b>B1</b>: broadcast flood. <b>BA</b>: stage C adaptive broadcast (flood in low, Plumtree tree in medium
        and high).</li>
        <li>Stage D flags: <b>K1</b> conversation keep-alive (3 min), <b>K2</b> delivery ack refreshes the route, <b>K3</b> reverse-path
        cache (3 min), <b>F</b> full-range route search at once, <b>S5</b> sparse sender (own estimate below 5) floods its own
        messages, <b>FG</b> faster graft, <b>CU</b> catch-up on link-up, <b>PIN</b> pinned low class.</li>
        <li><b>Stage D v1</b>: $v1U · $v1B. <b>Pre-registered candidate</b> (fixed before the main grid, used in the lifetime
        and N = 1000 sets): $candU · $v1B. <b>Stage C adaptive</b>: UA · BA. <b>Flooding everywhere</b>: U1 · B1.</li>
        <li><b>p95</b>: 95% of delivered messages arrive within this time. <b>Busiest phone</b>: routing bytes per second sent by the
        busiest phone. <b>Message lifetime, minimum hold, store-and-forward, hop limit</b>: as in the project glossary.</li>
        </ul>
        """.trimIndent()
}

fun stageDReport(out: File) {
    fun raw(set: CSet) = CRaw.file(out, set).let { if (it.exists()) RawCsv.read(it) else emptyList() }
    fun agg(set: CSet) = raw(set).let { if (it.isEmpty()) emptyList() else CAggregate.aggregate(it) }
    val rawMain = raw(CSet.D_MAIN)
    val html =
        StageDReport.render(
            out, CAggregate.aggregate(rawMain), rawMain, agg(CSet.D_CONV), agg(CSet.D_BIG), agg(CSet.D_LIFE), agg(CSet.D_PIN),
            agg(CSet.D_LIFE_V1), agg(CSet.D_BIG_V1), agg(CSet.D_IOS4),
        )
    File(out, "stage-d-report.html").writeText(html)
    println("wrote ${File(out, "stage-d-report.html")}")
}

fun stageDIos3Report(out: File, cap4Dir: File) {
    val rows = RawCsv.read(CRaw.file(out, CSet.D_IOS3)) + RawCsv.read(CRaw.file(cap4Dir, CSet.D_IOS4))
    val notes = File(out, "findings.html").takeIf { it.exists() }?.readText() ?: ""
    File(out, "ios-cap3-report.html").writeText(StageDReport.ios3Html(CAggregate.aggregate(rows), notes))
    println("wrote ${File(out, "ios-cap3-report.html")}")
}
