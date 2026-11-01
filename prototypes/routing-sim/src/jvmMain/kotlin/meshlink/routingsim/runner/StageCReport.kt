package meshlink.routingsim.runner

import meshlink.routingsim.runner.HtmlReport.barChart
import meshlink.routingsim.runner.HtmlReport.esc
import meshlink.routingsim.runner.HtmlReport.fmt
import meshlink.routingsim.runner.HtmlReport.mc
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/** Stage C report: local, dynamic density and per-phone adaptation. Inline CSS and SVG only. */
object StageCReport {
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
    private val fixedTopos = listOf("fixed-d5", "fixed-d6")
    private val adaptTopos = listOf("adaptive", "adaptive-d5", "adaptive-noduty")
    private val fixedU = listOf("U1", "U4sf", "U6")
    private val fixedB = listOf("B1", "B3")
    private val adaptU = listOf("UA", "UA4", "UA+pref")
    private val adaptB = listOf("BA", "BA+pref")

    /** Short instance name: protocol (B1B3 shown as BA) plus +pref / +rot. */
    fun inst(r: Map<String, String>): String {
        val p = r["protocol"] ?: ""
        val base = if (p == "B1B3") "BA" else p
        return base + (if (r["pref"] == "1") "+pref" else "") + (if (r["rotation"] == "1" && r["set"] == "main") "+rot" else "")
    }

    private fun AggregateRow.k(name: String) = key[name] ?: ""

    private fun AggregateRow.inst() = inst(key)

    private fun AggregateRow.scen() = "${k("scenario")}|${k("density")}"

    private fun cellOf(r: AggregateRow) = listOf(r.k("scenario"), r.k("density"), r.k("nodes"), r.k("platform"), r.k("drive")).joinToString("|")

    private class Config(
        val topo: String,
        val u: String,
        val b: String,
    ) {
        val label get() = "$topo · $u · $b"
    }

    private class Eval(
        val failures: List<String>,
        val load: Double,
        val p95: Double,
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
            for (r in rows) map["${cellOf(r)}|${r.k("topo")}|${r.inst()}"] = r
        }

        fun get(
            cell: String,
            topo: String,
            inst: String,
        ) = map["$cell|$topo|$inst"]
    }

    /** Decision criteria by the sender's true local density at send (amended low-density rule). */
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
        val u = idx.get(cell, c.topo, c.u) ?: return null
        val b = idx.get(cell, c.topo, c.b) ?: return null
        val f = failures(u, idx.get(cell, c.topo, "U1"), "unicast") + failures(b, idx.get(cell, c.topo, "B1"), "broadcast")
        val idle = u.m("idleCtrlShare").orZero() + b.m("idleCtrlShare").orZero() + u.m("t_controlShareOfCapacity").orZero()
        val all = if (idle > 0.10) f + "idle control ${pct(idle)} of capacity" else f
        return Eval(all, u.m("loadMaxBps") + b.m("loadMaxBps"), u.m("latP95Ms"), u, b)
    }

    private fun Double.orZero() = if (isNaN()) 0.0 else this

    private fun pct(v: Double) = if (v.isNaN()) "–" else String.format(Locale.ROOT, "%.2f%%", v * 100)

    private fun pp(v: Double) = if (v.isNaN()) "–" else String.format(Locale.ROOT, "%+.2f pp", v * 100)

    fun render(
        dir: File,
        main: List<AggregateRow>,
        rawMain: List<Map<String, String>>,
        big: List<AggregateRow>,
        life: List<AggregateRow>,
        long: List<AggregateRow>,
        bridge: List<AggregateRow>,
    ): String {
        val idx = Index(main + big)
        val sb = StringBuilder(HtmlReport.head("MeshLink routing simulation — stage C: adapting to local, changing density"))
        sb.append(intro(rawMain, File(dir, "run-notes.txt").takeIf { it.exists() }))
        File(dir, "findings.html").takeIf { it.exists() }?.let { sb.append("<h2>What we found</h2>").append(it.readText()) }
        val dynCells = dynOrder.flatMap { (m, d) -> listOf("android", "half-ios").map { p -> "$m|$d|200|$p|default" } }
        val staticCells = main.filter { it.k("density") in classes && it.k("drive") == "default" }.map { cellOf(it) }.distinct().sorted()
        val candidates =
            fixedTopos.flatMap { t -> fixedU.flatMap { u -> fixedB.map { b -> Config(t, u, b) } } } +
                adaptTopos.flatMap { t -> (adaptU + fixedU).flatMap { u -> (adaptB + fixedB).map { b -> Config(t, u, b) } } }
        // Flooding is option (iii); the best fixed configuration is picked among the others.
        val bestFixed = pick(idx, dynCells + staticCells, candidates.filter { it.topo in fixedTopos && !(it.u == "U1" && it.b == "B1") })
        val bestAdaptive =
            pick(idx, dynCells + staticCells, candidates.filter { it.topo in listOf("adaptive", "adaptive-d5") && it.u in adaptU && it.b in adaptB })
        val flood = Config("fixed-d5", "U1", "B1")
        sb.append(scoreboard(idx, dynCells, staticCells, candidates, bestFixed, bestAdaptive))
        sb.append(scenarioSection(idx, dynCells, listOf("adaptive policy" to bestAdaptive, "best fixed" to bestFixed, "flooding everywhere" to flood)))
        sb.append(staticSection(idx, staticCells, listOf("adaptive policy" to bestAdaptive, "best fixed" to bestFixed, "flooding everywhere" to flood)))
        sb.append(bigSection(big, bestAdaptive, bestFixed))
        sb.append(estimatorSection(main))
        sb.append(classifierSection(main))
        sb.append(driveSection(idx, main, bestAdaptive))
        sb.append(dutySection(idx, dynCells, bestAdaptive))
        sb.append(boundarySection(rawMain))
        sb.append(bridgeSection(bridge))
        sb.append(relaySection(rawMain))
        sb.append(rotationSection(rawMain))
        sb.append(lifetimeSection(life))
        sb.append(longSection(long))
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
            <h1>MeshLink routing simulation — stage C: adapting to local, changing density</h1>
            <p class="warn"><b>Every number on this page is simulated.</b> It comes from a simplified computer model of phones and
            Bluetooth links, calibrated against a few lab measurements. It is not a measurement on real devices.</p>
            <h2>The question</h2>
            <p>Stages A and B chose how phones connect (N1+N2, about 5 links each) and compared ways to move messages. They held one
            crowd density for a whole run. Real crowds change: people walk into a festival (few phones nearby → hundreds), leave again,
            or stand at the dense center while others are at the sparse edge. Stage C asks: <b>can each phone measure its own
            surroundings and adapt on its own, and does that beat one fixed setting for everyone?</b></p>
            <ul>
            <li>Each phone counts the distinct phones it heard in recent Bluetooth scans (plus its own links), smooths the count and
            places itself in a class: <b>low</b> (under ~10 phones in radio range), <b>medium</b> (10–50) or <b>high</b> (50+). There is
            no voting or coordination between phones.</li>
            <li>To avoid flapping, a phone switches up and down at different thresholds (hysteresis) and stays in a class for a minimum
            time (dwell).</li>
            <li>Adapting only changes the phone’s own decisions (how it forwards, how many links it keeps, how often it scans). The
            messages on the air are the same in every mode, and every phone relays every message type, so phones in different modes
            interoperate.</li>
            </ul>
            <p>Per scenario, three options are compared: (i) the <b>adaptive policy</b>, (ii) the <b>best single fixed
            configuration</b> (one setting for every phone and every density), and (iii) <b>flooding everywhere</b> (U1 + B1, the most
            robust and most expensive option). The criteria are applied by the sender’s true local density at the moment of sending:
            delivery within 0.5 pp of flooding (and ≥ 99% where flooding reaches it) at low density, ≥ 99% at medium, ≥ 95% at high;
            first-message p95 ≤ 1 s (≤ 1.5 s post-quantum); idle control traffic ≤ 10% of capacity; then the lowest busiest-phone load,
            then the lowest p95 latency.</p>
            <p class="note">$runs simulation runs in the main set. Tables show the mean over seeds and a 95% confidence interval (±).
            Unless stated otherwise: N = 200 phones, lifetime 5 min, minimum hold 10 s, clock skew ±30 s, identifiers rotate every 15
            min. All protocol variants of one run share the same simulated phones, movements and links.</p>
            ${notes?.let { "<details><summary>Run notes (what was run, what was cut and why)</summary><pre>${esc(it.readText())}</pre></details>" } ?: ""}
            """.trimIndent()
    }

    private fun pick(
        idx: Index,
        cells: List<String>,
        candidates: List<Config>,
    ): Config =
        candidates.maxWithOrNull(
            compareBy<Config>({ c -> cells.count { evaluate(idx, it, c)?.pass == true } })
                .thenBy { c -> -cells.mapNotNull { evaluate(idx, it, c)?.load }.average() }
                .thenBy { c -> -cells.mapNotNull { evaluate(idx, it, c)?.p95 }.filter { !it.isNaN() }.average() },
        ) ?: candidates.first()

    private fun scoreboard(
        idx: Index,
        dyn: List<String>,
        stat: List<String>,
        candidates: List<Config>,
        bestFixed: Config,
        bestAdaptive: Config,
    ): String {
        val sb = StringBuilder("<h2>Scoreboard: every configuration against the criteria</h2>")
        sb.append(
            """<p>Each row is one complete configuration (topology variant · unicast · broadcast). “Cells passed” counts the cells in
            which every criterion holds: dynamic scenarios (N = 200, both platform mixes) and fixed-density cells (N = 50 and 200, four
            movement patterns, both platform mixes). Load is the busiest phone’s routing traffic (unicast + broadcast) averaged over
            the cells. Selection: most cells passed, then lowest load, then lowest p95. Flooding (U1 + B1) is option (iii); the best
            fixed configuration is picked among the other fixed configurations.
            <b>Best fixed: ${esc(bestFixed.label)}</b>. <b>Adaptive policy: ${esc(bestAdaptive.label)}</b>.</p>""",
        )
        val flood = Config("fixed-d5", "U1", "B1")
        val floodFails = (dyn + stat).filter { evaluate(idx, it, flood)?.pass == false }
        if (floodFails.isNotEmpty()) {
            sb.append("<p class=note>Cells that even flooding (fixed-d5 · U1 · B1) fails: ")
            sb.append(floodFails.joinToString("; ") { c -> esc(c.split('|').take(4).joinToString(" ")) + " (" + esc(evaluate(idx, c, flood)!!.failures.joinToString(", ")) + ")" })
            sb.append(". Several cells hold only 150–200 unicast messages from senders in a given class, so a single lost message moves that class’s delivery by about 0.6 pp; read single-cell failures with that in mind.</p>")
        }
        sb.append("<table><tr><th class=l>configuration</th><th>dynamic cells passed</th><th>fixed-density cells passed</th><th>busiest-phone load B/s</th><th>unicast p95 ms</th><th class=l>most common failure</th></tr>")
        val rows =
            candidates.map { c ->
                val evDyn = dyn.mapNotNull { evaluate(idx, it, c) }
                val evStat = stat.mapNotNull { evaluate(idx, it, c) }
                val all = evDyn + evStat
                val fails = all.flatMap { e -> e.failures.map { it.substringBefore(" ").let { t -> t + " " + it.split(' ').getOrElse(1) { "" } } } }
                val common = fails.groupingBy { it }.eachCount().maxByOrNull { it.value }?.let { "${it.key} (${it.value}×)" } ?: ""
                listOf(
                    c.label, "${evDyn.count { it.pass }}/${evDyn.size}", "${evStat.count { it.pass }}/${evStat.size}",
                    fmt(all.map { it.load }.average()), fmt(all.map { it.p95 }.filter { !it.isNaN() }.average()), common,
                ) to all.count { it.pass }
            }.filter { it.first[1] != "0/0" }.sortedByDescending { it.second }
        for ((r, _) in rows) {
            val hl = r[0] == bestFixed.label || r[0] == bestAdaptive.label
            sb.append("<tr${if (hl) " class=better" else ""}><td class=l>${esc(r[0])}</td><td>${r[1]}</td><td>${r[2]}</td><td>${r[3]}</td><td>${r[4]}</td><td class=l>${esc(r[5])}</td></tr>")
        }
        return sb.append("</table>").toString()
    }

    private val compareMetrics =
        listOf(
            "u:delivery" to "unicast delivery", "u:del_low" to "… sender low", "u:del_medium" to "… medium", "u:del_high" to "… high",
            "u:del_trans" to "… in transition", "u:del_cross" to "… across modes", "u:latP95Ms" to "unicast p95 ms",
            "u:firstP95ClassicalMs" to "first-msg p95 ms", "b:delivery" to "broadcast delivery", "b:del_cross" to "… across modes",
            "b:latP95Ms" to "broadcast p95 ms", "load" to "busiest phone B/s", "ctrl" to "control B/node·s",
            "u:t_powerIndex" to "radio power index", "u:t_cls_drive_switchesPerHour" to "class switches /phone·h",
        )

    private fun metricCell(
        e: Eval,
        m: String,
    ): String =
        when {
            m == "load" -> fmt(e.load)
            m == "ctrl" -> fmt(e.u.m("ctrlBytesPerNodeS").orZero() + e.b.m("ctrlBytesPerNodeS").orZero() + e.u.m("t_controlBytesPerNodeS").orZero())
            m.startsWith("u:") -> mcFmt(e.u, m.substring(2))
            else -> mcFmt(e.b, m.substring(2))
        }

    private fun mcFmt(
        r: AggregateRow,
        m: String,
    ): String {
        val v = r.m(m)
        if (v.isNaN()) return "–"
        val c = r.c(m)
        val ratio = m.startsWith("del")
        val s = if (ratio) pct(v) else fmt(v)
        return if (c.isNaN()) s else "$s ± ${if (ratio) String.format(Locale.ROOT, "%.2f", c * 100) else fmt(c)}"
    }

    private fun comparisonTable(
        idx: Index,
        cells: List<String>,
        configs: List<Pair<String, Config>>,
        title: (String) -> String,
    ): String {
        val sb = StringBuilder()
        for (cell in cells) {
            val evs = configs.mapNotNull { (n, c) -> evaluate(idx, cell, c)?.let { Triple(n, c, it) } }
            if (evs.isEmpty()) continue
            sb.append("<h3>${esc(title(cell))}</h3><table><tr><th class=l>metric</th>")
            for ((n, c, e) in evs) sb.append("<th class=${if (e.pass) "better" else "worse"}>${esc(n)}<br><span class=note>${esc(c.label)}</span></th>")
            sb.append("</tr>")
            for ((m, label) in compareMetrics) {
                sb.append("<tr><td class=l>${esc(label)}</td>")
                for ((_, _, e) in evs) sb.append("<td>${metricCell(e, m)}</td>")
                sb.append("</tr>")
            }
            sb.append("<tr><td class=l>criteria</td>")
            for ((_, _, e) in evs) sb.append("<td class=l>${if (e.pass) "all met" else esc(e.failures.joinToString("; "))}</td>")
            sb.append("</tr></table>")
        }
        return sb.toString()
    }

    private fun cellTitle(cell: String): String {
        val p = cell.split('|')
        return "${scenarioNames["${p[0]}|${p[1]}"] ?: "${p[1]} density, ${p[0]}"} — N = ${p[2]}, ${if (p[3] == "android") "Android only" else "half iOS"}"
    }

    private fun scenarioSection(
        idx: Index,
        cells: List<String>,
        configs: List<Pair<String, Config>>,
    ): String {
        val sb = StringBuilder("<h2>Dynamic scenarios: adaptive vs best fixed vs flooding</h2>")
        sb.append(
            """<p>A arrival: phones stand in a sparse area, then walk into a venue over about 9 minutes (low → medium → high). B departure:
            the reverse. C: a dense core and a sparse edge at the same time. D surge: two thirds of the phones switch on inside the venue
            within 60 s (about 3× the peers). E: phones standing or walking at a class boundary. “Sender low/medium/high” splits
            delivery by the sender’s true density at send; “in transition” counts messages sent during the scenario’s transition window;
            “across modes” counts unicast messages whose sender and recipient were in different classes, and broadcast receivers in a
            different class than the sender. Green header: every criterion met.</p>""",
        )
        val labels = cells.map { c -> c.split('|').let { "${it[0].take(5)} ${it[1].removePrefix("dynamic")} ${if (it[3] == "android") "A" else "A/i"}".trim() } to "" }
        for ((m, title) in listOf("load" to "Busiest phone load (B/s)", "u:delivery" to "Unicast delivery")) {
            val series =
                configs.associate { (n, c) ->
                    n to cells.map { cell -> evaluate(idx, cell, c)?.let { e -> if (m == "load") e.load to Double.NaN else e.u.m("delivery") to e.u.c("delivery") } }
                }
            sb.append(barChart(title, if (m == "load") "bytes per second, lower is better" else "share delivered", title, labels, series, palette(configs)))
        }
        sb.append(comparisonTable(idx, cells, configs, ::cellTitle))
        return sb.toString()
    }

    private fun palette(configs: List<Pair<String, Config>>) =
        configs.map { it.first }.zip(listOf("#2f855a", "#2b6cb0", "#718096", "#d69e2e", "#6b46c1")).toMap()

    private fun staticSection(
        idx: Index,
        cells: List<String>,
        configs: List<Pair<String, Config>>,
    ): String {
        val sb = StringBuilder("<h2>Fixed-density cells (one density class for the whole run)</h2>")
        sb.append("<p>The stage B grid conditions (N = 50 and 200; static, walking, group and corridor movement; both platform mixes) with the stage C candidates. Pass counts per density class:</p>")
        sb.append("<table><tr><th class=l>density</th><th>N</th>")
        for ((n, c) in configs) sb.append("<th>${esc(n)}<br><span class=note>${esc(c.label)}</span></th>")
        sb.append("</tr>")
        for (d in classes) {
            for (n in listOf("50", "200")) {
                val cs = cells.filter { it.split('|')[1] == d && it.split('|')[2] == n }
                if (cs.isEmpty()) continue
                sb.append("<tr><td class=l>$d</td><td>$n</td>")
                for ((_, c) in configs) {
                    val ev = cs.mapNotNull { evaluate(idx, it, c) }
                    sb.append("<td>${ev.count { it.pass }}/${ev.size} · load ${fmt(ev.map { it.load }.average())} · p95 ${fmt(ev.map { it.p95 }.filter { !it.isNaN() }.average())} ms</td>")
                }
                sb.append("</tr>")
            }
        }
        sb.append("</table><details><summary>Every fixed-density cell</summary>")
        sb.append(comparisonTable(idx, cells, configs) { cell -> cell.split('|').let { "${it[1]} density, ${it[0]}, N = ${it[2]}, ${if (it[3] == "android") "Android only" else "half iOS"}" } })
        return sb.append("</details>").toString()
    }

    private fun bigSection(
        big: List<AggregateRow>,
        adaptive: Config,
        fixed: Config,
    ): String {
        if (big.isEmpty()) return ""
        val idx = Index(big)
        val cells = big.map { cellOf(it) }.distinct().sorted()
        val configs =
            listOf(
                "adaptive (UA + BA)" to Config("adaptive", "UA", "BA"),
                "fixed d5, ${fixed.u} + ${fixed.b}" to Config("fixed-d5", fixed.u, fixed.b),
                "flooding everywhere" to Config("fixed-d5", "U1", "B1"),
            )
        val sb = StringBuilder("<h2>N = 1000: arrival</h2>")
        sb.append("<p>One dynamic scenario at N = 1000 (the arrival), 5 seeds, a reduced protocol set (U1, U4sf, U6, UA, B1, B3, BA) to fit the time budget. The adaptive policy here uses UA and BA${if (adaptive.u != "UA" || adaptive.b != "BA") " (the N = 200 pick was ${esc(adaptive.label)})" else ""}.</p>")
        sb.append(comparisonTable(idx, cells, configs, ::cellTitle))
        return sb.toString()
    }

    private fun estimatorSection(main: List<AggregateRow>): String {
        val ests = listOf("w10", "w10e", "w20e", "w60e")
        val sb = StringBuilder("<h2>The density estimator: how close is a phone’s own count to the truth?</h2>")
        sb.append(
            """<p>Variants: <b>w10</b> = distinct phones heard in the last 10 s (no smoothing); <b>w10e</b> = the same, smoothed
            (exponential average, weight 0.3 per scan); <b>w20e</b> = 20 s window, weight 0.2; <b>w60e</b> = 60 s window, weight 0.2.
            All add the phone’s linked peers that it did not hear. Ratio = estimate ÷ true peers in radio range (1.00 = unbiased);
            error = mean absolute relative error. Measured on the fixed d = 5 topology, which scans every 2 s.</p>
            <p><b>Identifier rotation.</b> Phones change their advertising identifier every 15 min; after a change the same phone is
            counted twice until its old identifier leaves the window. Linked peers tell each other their next identifier, so only
            non-linked phones are double-counted. With random rotation times the expected inflation is about window ÷ rotation period
            (10 s ÷ 900 s ≈ 1.1%). The “aligned” column is the worst case: every phone rotates at the same instant.</p>""",
        )
        sb.append("<table><tr><th class=l>scenario</th><th class=l>platform</th>")
        for (e in ests) sb.append("<th>$e ratio</th><th>$e error</th>")
        sb.append("<th>w10e aligned ratio</th><th>switches /h (random)</th><th>switches /h (aligned)</th></tr>")
        for ((m, d) in dynOrder) {
            for (p in listOf("android", "half-ios")) {
                val r = main.firstOrNull { it.k("scenario") == m && it.k("density") == d && it.k("platform") == p && it.k("topo") == "fixed-d5" && it.k("drive") == "default" } ?: continue
                sb.append("<tr><td class=l>${esc(scenarioNames["$m|$d"] ?: m)}</td><td class=l>$p</td>")
                for (e in ests) sb.append("<td>${mc(r, "t_est_${e}_ratio")}</td><td>${mc(r, "t_est_${e}_mape")}</td>")
                sb.append("<td>${mc(r, "t_est_w10e-aligned_ratio")}</td><td>${mc(r, "t_cls_drive_switchesPerHour")}</td><td>${mc(r, "t_alignedDriveSwitchesPerHour")}</td></tr>")
            }
        }
        for (d in classes) {
            val rs = main.filter { it.k("density") == d && it.k("topo") == "fixed-d5" && it.k("nodes") == "200" }
            if (rs.isEmpty()) continue
            sb.append("<tr><td class=l>fixed $d (avg of cells)</td><td class=l>both</td>")
            for (e in ests) sb.append("<td>${fmt(rs.map { it.m("t_est_${e}_ratio") }.average())}</td><td>${fmt(rs.map { it.m("t_est_${e}_mape") }.average())}</td>")
            sb.append("<td>${fmt(rs.map { it.m("t_est_w10e-aligned_ratio") }.average())}</td><td>${fmt(rs.map { it.m("t_cls_drive_switchesPerHour") }.average())}</td><td>${fmt(rs.map { it.m("t_alignedDriveSwitchesPerHour") }.average())}</td></tr>")
        }
        return sb.append("</table>").toString()
    }

    private fun classifierSection(main: List<AggregateRow>): String {
        val groups =
            listOf(
                "standing at a boundary (E static 10/50)" to setOf("static|at10", "static|at50"),
                "walking around a boundary (E walking 10/50)" to setOf("waypoint|at10", "waypoint|at50"),
                "transitions (A–D)" to setOf("arrival|dynamic", "departure|dynamic", "core-edge|dynamic", "surge|dynamic"),
            )
        val base = main.filter { it.k("topo") == "fixed-d5" && it.k("drive") == "default" && it.k("nodes") == "200" }
        val variants =
            base.firstOrNull()?.mean?.keys?.filter { it.startsWith("t_cls_") && it.endsWith("_agree") && !it.contains("drive") }
                ?.map { it.removePrefix("t_cls_").removeSuffix("_agree") } ?: emptyList()
        val sb = StringBuilder("<h2>Hysteresis and dwell: switching classes without flapping</h2>")
        sb.append(
            """<p>Variant names: estimator / s<i>scale</i>h<i>band</i>/d<i>dwell</i>. The class bounds 10 and 50 are multiplied by the
            scale (1.3 compensates an estimator that over-counts); the phone moves up at bound·(1+band) and down at bound÷(1+band), and
            stays at least the dwell time in a class. Agreement = share of time the phone’s class equals the true class; switches are
            per phone per hour; lag p90 = 90th percentile of the time from a true class change (held ≥ 10 s) until the phone follows.
            The truth itself changes class this often (per phone per hour, settled for 10 s):
            ${groups.joinToString("; ") { (n, s) -> "$n: ${fmt(base.filter { it.scen() in s }.map { it.m("t_truthSwitchesPerHour") }.average())}" }}.
            Target: a few switches per hour at most for a phone standing at a boundary.</p>""",
        )
        sb.append("<table><tr><th class=l>variant</th>")
        for ((g, _) in groups) sb.append("<th>${esc(g)}<br>agree · switches/h · lag p90 s</th>")
        sb.append("</tr>")
        for (v in variants.sortedBy { it }) {
            if (v.startsWith("w60e")) continue
            sb.append("<tr><td class=l>${esc(v)}</td>")
            for ((_, s) in groups) {
                val rs = base.filter { it.scen() in s }
                sb.append("<td>${fmt(rs.map { it.m("t_cls_${v}_agree") }.average())} · ${fmt(rs.map { it.m("t_cls_${v}_switchesPerHour") }.average())} · ${fmt(rs.map { it.m("t_cls_${v}_lagP90S") }.filter { !it.isNaN() }.average())}</td>")
            }
            sb.append("</tr>")
        }
        return sb.append("</table><p class=note>w60e rows omitted (over-counts 1.3–2.8× while walking); they are in the aggregate CSV.</p>").toString()
    }

    private fun driveSection(
        idx: Index,
        main: List<AggregateRow>,
        adaptive: Config,
    ): String {
        val drives = main.map { it.k("drive") }.distinct().sorted()
        if (drives.size < 2) return ""
        val sb = StringBuilder("<h2>Does the estimator setting change the outcome?</h2>")
        sb.append("<p>The adaptive policy (${esc(adaptive.label)}) run with alternative estimator/classifier settings driving it (5 seeds each, N = 200 dynamic scenarios). Default = w10e:s1h25/d60.</p>")
        sb.append("<table><tr><th class=l>scenario</th><th class=l>platform</th>")
        for (d in drives) sb.append("<th>$d<br>delivery · load · switches/h · lag p90 s</th>")
        sb.append("</tr>")
        for ((m, dd) in dynOrder) {
            for (p in listOf("android", "half-ios")) {
                sb.append("<tr><td class=l>${esc(scenarioNames["$m|$dd"] ?: m)}</td><td class=l>$p</td>")
                for (d in drives) {
                    val e = evaluate(idx, "$m|$dd|200|$p|$d", adaptive)
                    sb.append(
                        if (e == null) "<td>–</td>" else "<td class=${if (e.pass) "better" else "worse"}>${pct(e.u.m("delivery"))} · ${fmt(e.load)} · ${fmt(e.u.m("t_cls_drive_switchesPerHour"))} · ${fmt(e.u.m("t_cls_drive_lagP90S"))}</td>",
                    )
                }
                sb.append("</tr>")
            }
        }
        return sb.append("</table>").toString()
    }

    private fun dutySection(
        idx: Index,
        cells: List<String>,
        adaptive: Config,
    ): String {
        val sb = StringBuilder("<h2>Scan and advertising duty cycle</h2>")
        sb.append(
            """<p>The adaptive topology scans every 2 s at low density, every 4 s at medium and every 6 s at high, and advertises
            slowly at high density (modeled as a 50% instead of 80% chance of being heard per scan). “No duty” keeps the fast setting
            everywhere. Power index: relative radio-on time, 1.0 = always fast scanning and advertising (an assumption: 0.6 × scan duty
            + 0.4 × advertising duty, slow advertising = 0.4). Discovery lag is measured as the time a newly switched-on phone needs for
            its first link.</p>""",
        )
        sb.append("<table><tr><th class=l>scenario</th><th class=l>platform</th><th class=l>variant</th><th>power index</th><th>first link median s</th><th>first link p95 s</th><th>class lag p90 s</th><th>estimator ratio</th><th>unicast delivery</th><th>broadcast delivery</th><th>busiest phone B/s</th></tr>")
        for (cell in cells) {
            for (t in listOf("adaptive", "adaptive-noduty")) {
                val e = evaluate(idx, cell, Config(t, adaptive.u, adaptive.b)) ?: continue
                val p = cell.split('|')
                sb.append("<tr><td class=l>${esc(scenarioNames["${p[0]}|${p[1]}"] ?: p[0])}</td><td class=l>${p[3]}</td><td class=l>$t</td>")
                for (m in listOf("t_powerIndex", "t_joinMedianS", "t_joinP95S", "t_cls_drive_lagP90S", "t_est_w10e_ratio")) sb.append("<td>${mc(e.u, m)}</td>")
                sb.append("<td>${pct(e.u.m("delivery"))}</td><td>${pct(e.b.m("delivery"))}</td><td>${fmt(e.load)}</td></tr>")
            }
        }
        return sb.append("</table>").toString()
    }

    /** Pools delivery by the sender's true peers-in-range bin over the fixed d = 5, N = 200 runs. */
    private fun boundarySection(raw: List<Map<String, String>>): String {
        val bins = listOf("<2", "2–4", "5–9", "10–19", "20–34", "35–49", "50–99", "100+")
        val rows = raw.filter { it["topo"] == "fixed-d5" && it["nodes"] == "200" && (it["drive"] ?: "default") == "default" && it["rotation"] == "0" && it["pref"] == "0" }
        val protos = listOf("U1", "U4sf", "U6", "UA", "UA4", "B1", "B3", "BA")
        val sb = StringBuilder("<h2>Where do the criteria start failing? (class boundaries)</h2>")
        sb.append(
            """<p>All fixed d = 5, N = 200 runs pooled (every scenario), delivery split by how many peers the sender truly had in radio
            range when sending. Red: below 99% for bins under 50 peers or below 95% from 50 peers. Messages per bin in brackets.</p>""",
        )
        sb.append("<table><tr><th class=l>protocol</th>")
        for (b in bins) sb.append("<th>${esc(b)}</th>")
        sb.append("</tr>")
        for (p in protos) {
            val rs = rows.filter { inst(it) == p }
            if (rs.isEmpty()) continue
            sb.append("<tr><td class=l>$p</td>")
            for (k in bins.indices) {
                var n = 0.0
                var d = 0.0
                for (r in rs) {
                    val nn = r["n_bin$k"]?.toDoubleOrNull() ?: continue
                    val dd = r["del_bin$k"]?.toDoubleOrNull() ?: continue
                    if (nn <= 0 || dd.isNaN()) continue
                    n += nn
                    d += nn * dd
                }
                val v = if (n > 0) d / n else Double.NaN
                val target = if (k >= 6) 0.95 else 0.99
                sb.append("<td class=${if (v.isNaN()) "" else if (v >= target) "better" else "worse"}>${pct(v)} <span class=note>(${fmt(n)})</span></td>")
            }
            sb.append("</tr>")
        }
        return sb.append("</table>").toString()
    }

    private fun bridgeSection(rows: List<AggregateRow>): String {
        if (rows.isEmpty()) return ""
        val gaps = listOf("off", "10", "30", "60", "120")
        val sb = StringBuilder("<h2>Bridge rule rate limit</h2>")
        sb.append(
            """<p>The bridge rule (connect to a newly heard phone from another group, dropping the most redundant link if needed) with a
            minimum gap between two bridge dials per phone. Partition-and-merge scenario, N = 200, Android only, d = 5, no message
            traffic, 10 seeds. Repair = time from the groups meeting until the largest group recovers.</p>""",
        )
        val labels = classes.flatMap { d -> gaps.map { g -> (if (g == "off") "off" else "$g s") to d } }
        val series =
            mapOf(
                "repair s" to labels.map { (g, d) -> rows.firstOrNull { it.k("density") == d && it.k("bridgeGapS") == g.removeSuffix(" s") }?.let { it.m("t_mergeLccRecoverS") to it.c("t_mergeLccRecoverS") } },
            )
        sb.append(barChart("Largest group recovered", "seconds after the groups meet (lower is better)", "repair", labels, series, mapOf("repair s" to "#2f855a")))
        sb.append("<table><tr><th class=l>density</th><th class=l>gap</th><th>first link s</th><th>repair s</th><th>link-ups /node·min</th><th>extra link-ups vs off</th><th>dials /node·min</th><th>control B/node·s</th><th>largest group</th></tr>")
        for (d in classes) {
            val off = rows.firstOrNull { it.k("density") == d && it.k("bridgeGapS") == "off" }
            for (g in gaps) {
                val r = rows.firstOrNull { it.k("density") == d && it.k("bridgeGapS") == g } ?: continue
                val extra = if (off == null) Double.NaN else r.m("t_linkUpsPerNodeMin") / off.m("t_linkUpsPerNodeMin") - 1
                sb.append("<tr><td class=l>$d</td><td class=l>${if (g == "off") "off" else "$g s"}</td>")
                for (m in listOf("t_mergeFirstLinkS", "t_mergeLccRecoverS", "t_linkUpsPerNodeMin")) sb.append("<td>${mc(r, m)}</td>")
                sb.append("<td>${if (extra.isNaN()) "–" else String.format(Locale.ROOT, "%+.1f%%", extra * 100)}</td>")
                for (m in listOf("t_dialsPerNodeMin", "t_controlBytesPerNodeS", "t_lcc")) sb.append("<td>${mc(r, m)}</td>")
                sb.append("</tr>")
            }
        }
        return sb.append("</table>").toString()
    }

    /** Paired per-run difference (b − a) of [metric], mean and 95% CI. */
    private fun paired(
        raw: List<Map<String, String>>,
        a: (Map<String, String>) -> Boolean,
        b: (Map<String, String>) -> Boolean,
        metric: String,
        ratio: Boolean = false,
    ): Pair<Double, Double> {
        val aa = raw.filter(a).associateBy { it["runId"] }
        val xs =
            raw.filter(b).mapNotNull { rb ->
                val ra = aa[rb["runId"]] ?: return@mapNotNull null
                val va = ra[metric]?.toDoubleOrNull() ?: return@mapNotNull null
                val vb = rb[metric]?.toDoubleOrNull() ?: return@mapNotNull null
                if (va.isNaN() || vb.isNaN()) null else if (ratio) (if (va == 0.0) null else vb / va - 1) else vb - va
            }
        if (xs.isEmpty()) return Double.NaN to Double.NaN
        val mu = xs.average()
        val ci = if (xs.size < 2) Double.NaN else Aggregate.tQuantile(xs.size - 1) * sqrt(xs.sumOf { (it - mu) * (it - mu) } / (xs.size - 1)) / sqrt(xs.size.toDouble())
        return mu to ci
    }

    private fun pm(
        v: Pair<Double, Double>,
        kind: String,
    ): String =
        when (kind) {
            "pp" -> if (v.first.isNaN()) "–" else String.format(Locale.ROOT, "%+.2f ± %.2f pp", v.first * 100, v.second * 100)
            "pct" -> if (v.first.isNaN()) "–" else String.format(Locale.ROOT, "%+.1f ± %.1f%%", v.first * 100, v.second * 100)
            else -> if (v.first.isNaN()) "–" else String.format(Locale.ROOT, "%+.0f ± %.0f", v.first, v.second)
        }

    private fun groupOf(r: Map<String, String>) = if (r["density"] in classes) "fixed ${r["density"]}" else scenarioNames["${r["scenario"]}|${r["density"]}"] ?: "${r["scenario"]}"

    private fun relaySection(raw: List<Map<String, String>>): String {
        val half = raw.filter { it["platform"] == "half-ios" && it["topo"] == "adaptive" && (it["drive"] ?: "default") == "default" }
        val sb = StringBuilder("<h2>iOS-aware relay preference (half-iOS cells)</h2>")
        sb.append(
            """<p>With relay preference, a phone that is asleep most of the time (an iOS phone in the background) waits a little before
            relaying, so awake phones (Android, or iOS in the foreground) carry the message first. Paired difference with vs without
            preference, same runs (adaptive topology).</p>""",
        )
        sb.append("<table><tr><th class=l>group</th><th>unicast delivery</th><th>unicast first-msg p95 ms</th><th>unicast p95 ms</th><th>unicast busiest phone</th><th>broadcast delivery</th><th>broadcast p95 ms</th><th>broadcast busiest phone</th></tr>")
        for (g in half.map { groupOf(it) }.distinct().sorted() + "all") {
            val rs = if (g == "all") half else half.filter { groupOf(it) == g }
            val ua = { r: Map<String, String> -> inst(r) == "UA" }
            val uap = { r: Map<String, String> -> inst(r) == "UA+pref" }
            val ba = { r: Map<String, String> -> inst(r) == "BA" }
            val bap = { r: Map<String, String> -> inst(r) == "BA+pref" }
            sb.append("<tr><td class=l>${esc(g)}</td><td>${pm(paired(rs, ua, uap, "delivery"), "pp")}</td><td>${pm(paired(rs, ua, uap, "firstP95ClassicalMs"), "")}</td><td>${pm(paired(rs, ua, uap, "latP95Ms"), "")}</td><td>${pm(paired(rs, ua, uap, "loadMaxBps", true), "pct")}</td>")
            sb.append("<td>${pm(paired(rs, ba, bap, "delivery"), "pp")}</td><td>${pm(paired(rs, ba, bap, "latP95Ms"), "")}</td><td>${pm(paired(rs, ba, bap, "loadMaxBps", true), "pct")}</td></tr>")
        }
        return sb.append("</table>").toString()
    }

    private fun rotationSection(raw: List<Map<String, String>>): String {
        val rs0 = raw.filter { (it["drive"] ?: "default") == "default" && it["nodes"] == "200" }
        val sb = StringBuilder("<h2>Routing-tag rotation every 15 minutes</h2>")
        sb.append(
            """<p>Budget: at most 10% more transmissions and at most 1 pp less delivery. Paired difference UA with rotation vs UA without,
            same runs, every topology variant. Transmissions = data plus control bytes per phone per second.</p>""",
        )
        sb.append("<table><tr><th class=l>group</th><th>runs</th><th>extra data transmissions</th><th>extra control bytes</th><th>delivery change</th><th>p95 change ms</th><th>within budget</th></tr>")
        val groups = rs0.map { groupOf(it) }.distinct().sortedBy { if (it.startsWith("fixed")) "0$it" else "1$it" }
        for (g in groups) {
            val rs = rs0.filter { groupOf(it) == g }
            val a = { r: Map<String, String> -> inst(r) == "UA" }
            val b = { r: Map<String, String> -> inst(r) == "UA+rot" }
            val tx = paired(rs, a, b, "dataTxPerNodeS", true)
            val ctrl = paired(rs, a, b, "ctrlBytesPerNodeS", true)
            val del = paired(rs, a, b, "delivery")
            val ok = !tx.first.isNaN() && tx.first <= 0.10 && del.first >= -0.01
            sb.append("<tr><td class=l>${esc(g)}</td><td>${rs.count(b)}</td><td>${pm(tx, "pct")}</td><td>${pm(ctrl, "pct")}</td><td>${pm(del, "pp")}</td><td>${pm(paired(rs, a, b, "latP95Ms"), "")}</td><td class=${if (ok) "better" else "worse"}>${if (ok) "yes" else "no"}</td></tr>")
        }
        return sb.append("</table>").toString()
    }

    private fun lifetimeSection(rows: List<AggregateRow>): String {
        if (rows.isEmpty()) return ""
        val sb = StringBuilder("<h2>Minimum message lifetime (60 s and 120 s, clock skew ±30 s)</h2>")
        sb.append(
            """<p>Adaptive topology, N = 200, 10 seeds. Each message’s lifetime is checked against the relaying phone’s own clock, which
            can be off by up to ±30 s, so a 60 s message can look expired after 30 s. Minimum hold = a phone only takes a message into
            store-and-forward if at least this much lifetime is left.</p>""",
        )
        sb.append("<table><tr><th class=l>scenario</th><th class=l>platform</th><th class=l>protocol</th>")
        val lh = rows.map { it.k("lifetimeS") to it.k("holdS") }.distinct().sortedWith(compareBy({ it.first.toInt() }, { it.second.toInt() }))
        for ((l, h) in lh) sb.append("<th>life $l s, hold $h s</th>")
        sb.append("</tr>")
        for (cell in rows.map { Triple(it.k("scenario"), it.k("density"), it.k("platform")) }.distinct()) {
            for (p in listOf("U1", "UA", "UA4")) {
                sb.append("<tr><td class=l>${esc(scenarioNames["${cell.first}|${cell.second}"] ?: "${cell.second} ${cell.first}")}</td><td class=l>${cell.third}</td><td class=l>$p</td>")
                for ((l, h) in lh) {
                    val r = rows.firstOrNull { it.k("scenario") == cell.first && it.k("density") == cell.second && it.k("platform") == cell.third && it.k("protocol") == p && it.k("lifetimeS") == l && it.k("holdS") == h }
                    val v = r?.m("delivery") ?: Double.NaN
                    sb.append("<td class=${if (v.isNaN()) "" else if (v >= 0.99) "better" else "worse"}>${r?.let { mcFmt(it, "delivery") } ?: "–"}</td>")
                }
                sb.append("</tr>")
            }
        }
        return sb.append("</table>").toString()
    }

    private fun longSection(rows: List<AggregateRow>): String {
        if (rows.isEmpty()) return ""
        val sb = StringBuilder("<h2>Long lifetimes over 1 simulated hour (1 h and 24 h)</h2>")
        sb.append("<p>N = 200, low density, walking and corridor, adaptive topology, 5 seeds, 60-minute runs (warm-up 2 min). Held = messages a phone holds for store-and-forward on average.</p>")
        sb.append("<table><tr><th class=l>movement</th><th class=l>platform</th><th class=l>protocol</th><th>lifetime</th><th>delivery</th><th>delivery incl. unreachable</th><th>p95 ms</th><th>held per phone</th><th>busiest phone B/s</th><th>data B/node·s</th></tr>")
        for (r in rows.sortedWith(compareBy({ it.k("scenario") }, { it.k("platform") }, { it.k("protocol") }, { it.k("lifetimeS").toInt() }))) {
            sb.append("<tr><td class=l>${r.k("scenario")}</td><td class=l>${r.k("platform")}</td><td class=l>${r.k("protocol")}</td><td>${r.k("lifetimeS").toInt() / 3600} h</td>")
            for (m in listOf("delivery", "deliveryAll", "latP95Ms", "heldMean", "loadMaxBps", "dataBytesPerNodeS")) sb.append("<td>${mcFmt(r, m)}</td>")
            sb.append("</tr>")
        }
        return sb.append("</table>").toString()
    }

    private fun glossary() =
        """
        <h2>Words used here</h2>
        <ul>
        <li><b>Peers in range</b>: other phones close enough to hear over Bluetooth. Density classes: low &lt; 10, medium 10–49, high ≥ 50.</li>
        <li><b>U1</b>: managed flood (every phone relays every message once). <b>U4sf</b>: on-demand route search (AODV-like) with tuned
        store-and-forward. <b>U6</b>: hybrid; a short flood (3 hops) carries the message while the route is found, then the route is used.
        <b>UA</b>: adaptive unicast; a phone in the low class floods, in medium and high it behaves as U6. <b>UA4</b>: low floods, medium
        U6, high U4.</li>
        <li><b>B1</b>: broadcast flood. <b>B3</b>: Plumtree (payload along a self-repairing tree). <b>BA</b>: adaptive broadcast; B1
        behavior in the low class, B3 in medium and high, same messages on the air.</li>
        <li><b>fixed-d5 / fixed-d6</b>: N1+N2 topology with 5 or 6 links per phone, bridge rule with the chosen rate limit.
        <b>adaptive</b>: 6 links at low, 5 at medium and high, plus the per-class scan and advertising duty. <b>adaptive-noduty</b>: the
        same without the duty change.</li>
        <li><b>+pref</b>: iOS-aware relay preference. <b>+rot</b>: routing tag rotates every 15 minutes.</li>
        <li><b>Message lifetime, minimum hold, store-and-forward, hop limit</b>: as in the project glossary.</li>
        </ul>
        """.trimIndent()
}

fun stageCReport(out: File) {
    fun agg(f: File) = if (f.exists()) CAggregate.aggregate(RawCsv.read(f)) else emptyList()
    val rawMain = File(out, "raw-main.csv").let { if (it.exists()) RawCsv.read(it) else emptyList() }
    val html =
        StageCReport.render(
            out, CAggregate.aggregate(rawMain), rawMain, agg(File(out, "raw-big.csv")), agg(File(out, "raw-life.csv")),
            agg(File(out, "raw-long.csv")), agg(File(out, "bridge/raw-none.csv")),
        )
    File(out, "stage-c-report.html").writeText(html)
    println("wrote ${File(out, "stage-c-report.html")}")
}
