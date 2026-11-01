package meshlink.routingsim.runner

import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Self-contained HTML report: inline CSS and SVG, no scripts, no external resources. */
object HtmlReport {
    private const val N0 = "N0"
    private const val N12 = "N1+N2"
    private val colors = mapOf(N0 to "#d9822b", N12 to "#2b6cb0")

    private class Metric(
        val id: String,
        val label: String,
        val unit: String,
        val higherIsBetter: Boolean?,
        val explain: String,
    )

    private val metrics =
        listOf(
            Metric("lcc", "Largest connected group", "share of nodes", true, "Share of online nodes that can reach each other over mesh links (1.0 = everyone is in one mesh)."),
            Metric("radioLcc", "Radio-reachable group (upper bound)", "share of nodes", null, "Same, if every pair in radio range were linked: the best any strategy could do."),
            Metric("components", "Separate groups", "count", false, "Number of disconnected pieces the mesh falls into (single nodes count)."),
            Metric("diameter", "Mesh diameter", "hops", false, "Longest shortest path in the largest group, in hops. Lower means fewer hops to cross the mesh. Above 300 nodes this is a sampled lower bound."),
            Metric("meanPathLen", "Average path length", "hops", false, "Average hops between two nodes in the largest group."),
            Metric("slotOccupancy", "Link slots in use", "share of d", null, "Share of each node's connection budget d that holds a live link."),
            Metric("dialsPerNodeMin", "Dials", "per node-minute", false, "Connection attempts started per node per minute (radio and battery cost)."),
            Metric("dialFailureRate", "Dial failure rate", "share", false, "Share of connection attempts that failed."),
            Metric("linkUpsPerNodeMin", "Connection churn", "links up per node-minute", false, "New links established per node per minute."),
            Metric("linkLifetimeMedianS", "Median link lifetime", "s", null, "How long a link typically lives."),
            Metric("controlBytesPerNodeS", "Control traffic", "bytes per node-second", false, "Bytes spent on topology upkeep (neighbor lists, shuffles)."),
            Metric("controlShareOfCapacity", "Control traffic share", "of link capacity", false, "Control bytes as a share of the link capacity the node has (decision limit: 10%)."),
            Metric("joinConnected60s", "Joiners connected within 60 s", "share", true, "Share of (re)joining nodes with a peer in range that got their first link within 60 s."),
            Metric("joinMedianS", "Time to first link (median)", "s", false, "Median time from joining until the first mesh link."),
            Metric("mergeFirstLinkS", "Partition repair: first bridging link", "s", false, "After two separated groups come back into radio range: time until the first link between them."),
            Metric("mergeLccRecoverS", "Partition repair: mesh recovered", "s", false, "Time until the largest group again holds 90% of what radio range allows."),
            Metric("sybilSlotShare", "Honest slots held by fake identities", "share", false, "Share of honest nodes' links that go to fake identities."),
            Metric("sybilPeerTableShare", "Peer-table entries that are fake", "share", false, "Share of honest nodes' candidate lists (peer table or passive view) taken by fake identities."),
            Metric("peersInRange", "Peers in radio range (measured)", "count", null, "Measured average number of online peers within radio range."),
        ).associateBy { it.id }

    private val mobilityOrder = listOf("static", "waypoint", "group", "corridor", "partition-merge")
    private val mobilityLabel =
        mapOf(
            "static" to "static crowd",
            "waypoint" to "walking (random waypoint)",
            "group" to "groups walking together",
            "corridor" to "corridor / chain",
            "partition-merge" to "split then merge",
        )

    fun render(
        rows: List<AggregateRow>,
        timeseries: File?,
        notes: File?,
        findings: File? = null,
    ): String {
        val main = rows.filter { it.key["sybilShare"]!!.toDouble() == 0.0 && it.key["dropMeanS"] == "default" }
        val sybil = rows.filter { it.key["sybilShare"]!!.toDouble() > 0.0 }
        val drop = rows.filter { it.key["dropMeanS"] != "default" || isDropBaseline(it) }
        val sb = StringBuilder()
        sb.append(head())
        sb.append(intro(rows, notes))
        if (findings != null) sb.append("<h2>What we found</h2>").append(findings.readText())
        sb.append(scoreboard(main))
        for (density in listOf("low", "medium", "high")) sb.append(densitySection(main, density))
        sb.append(degreeSection(main))
        sb.append(platformSection(main))
        sb.append(mergeSection(main))
        sb.append(sybilSection(sybil))
        sb.append(dropSection(drop))
        if (timeseries != null) sb.append(timeseriesSection(timeseries))
        sb.append(glossary())
        sb.append(fullTables(rows))
        sb.append("</main></body></html>\n")
        return sb.toString()
    }

    private fun isDropBaseline(r: AggregateRow) =
        r.key["density"] == "medium" && r.key["nodes"] == "200" && r.key["platform"] == "android" &&
            r.key["degree"] == "5" && r.key["sybilShare"]!!.toDouble() == 0.0 && r.key["mobility"] in setOf("static", "waypoint", "group")

    private fun head() =
        """
        <!DOCTYPE html><html lang="en"><head><meta charset="utf-8">
        <title>MeshLink routing simulation — stage A: who should a phone connect to?</title>
        <style>
        body{font-family:-apple-system,Segoe UI,Helvetica,Arial,sans-serif;color:#1a202c;background:#fff;margin:0}
        main{max-width:1100px;margin:0 auto;padding:1.5em}
        h1{font-size:1.6em}h2{border-bottom:2px solid #e2e8f0;padding-bottom:.2em;margin-top:2em}
        .warn{background:#fffbea;border-left:4px solid #d69e2e;padding:.6em 1em}
        .note{color:#4a5568;font-size:.9em}
        table{border-collapse:collapse;font-size:.82em;margin:.6em 0}
        th,td{border:1px solid #e2e8f0;padding:.25em .5em;text-align:right}
        th{background:#f7fafc}td.l,th.l{text-align:left}
        .better{background:#e6fffa}.worse{background:#fff5f5}
        .chart{display:inline-block;vertical-align:top;margin:.4em}
        details{margin:.6em 0}summary{cursor:pointer;font-weight:600}
        .key span{display:inline-block;width:1em;height:1em;vertical-align:middle;margin:0 .3em 0 1em}
        </style></head><body><main>
        """.trimIndent()

    private fun intro(
        rows: List<AggregateRow>,
        notes: File?,
    ): String {
        val runs = rows.sumOf { it.n }
        return """
            <h1>MeshLink routing simulation — stage A: who should a phone connect to?</h1>
            <p class="warn"><b>Every number on this page is simulated.</b> It comes from a simplified computer model of phones and
            Bluetooth links, calibrated against a few lab measurements. It is not a measurement on real devices.</p>
            <h2>The question</h2>
            <p>MeshLink phones talk to each other over Bluetooth Low Energy. A phone can hold only a few live connections at once
            (its <i>degree budget</i> <b>d</b>, here 3 to 8). Messages travel hop by hop over these connections. So each phone must
            choose <b>which</b> nearby phones to connect to. Good choices give one big, well-connected mesh in which messages need few hops.
            Poor choices give isolated clusters that cannot reach each other although they are in radio range.</p>
            <p>Stage A compares two ways of choosing (the others come in later stages):</p>
            <ul>
            <li><b style="color:${colors[N0]}">N0 — strongest signal</b>: keep the ~¾·d phones with the strongest signal, and rotate the
            remaining slots through other nearby phones from time to time. No extra messages are needed.</li>
            <li><b style="color:${colors[N12]}">N1+N2 — diverse views</b> (HyParView-style): keep a list of known phones besides the live
            links, swap parts of that list with neighbors now and then, and prefer phones whose own neighbors are <i>not</i> already
            reachable. That costs a small amount of extra traffic (neighbor lists).</li>
            </ul>
            <p>Both keep at least one slot free so that a newly arriving phone can always connect (N3, newcomer reservation).</p>
            <p>We vary how crowded the area is (<b>density</b> = phones within radio range: low 2–10, medium 10–50, high 50–200),
            the mesh size N (10 to 1000 phones), how people move, the mix of Android and iPhone (iPhones in the background are reachable only about
            10 s per minute), and d. Each setting was run with 10 different random seeds; charts show the mean and a 95% confidence
            interval (whisker). If whiskers of the two strategies do not overlap, the difference is unlikely to be chance.</p>
            <p class="note">$runs simulation runs in ${rows.size} cells. Each run simulates 15 minutes; the first 2 minutes are ignored
            as warm-up (the mesh settles within about 1 minute, see the time-series chart below).</p>
            ${notes?.let { "<details><summary>Run notes</summary><pre>${esc(it.readText())}</pre></details>" } ?: ""}
            <p class="key">Colors:<span style="background:${colors[N0]}"></span>N0 strongest signal<span style="background:${colors[N12]}"></span>N1+N2 diverse views</p>
            """.trimIndent()
    }

    /** Outcome of N1+N2 vs N0 for one metric in one cell: +1 N1+N2 better, -1 N0 better, 0 overlapping CIs. */
    private fun verdict(
        a: AggregateRow,
        b: AggregateRow,
        m: Metric,
    ): Int {
        val hib = m.higherIsBetter ?: return 0
        val ma = a.m(m.id)
        val mb = b.m(m.id)
        if (ma.isNaN() || mb.isNaN()) return 0
        val ca = a.c(m.id).let { if (it.isNaN()) 0.0 else it }
        val cb = b.c(m.id).let { if (it.isNaN()) 0.0 else it }
        if (abs(ma - mb) <= ca + cb || abs(ma - mb) < 1e-9) return 0
        val n12Higher = mb > ma
        return if (n12Higher == hib) 1 else -1
    }

    private fun pairs(rows: List<AggregateRow>): List<Pair<AggregateRow, AggregateRow>> {
        val byCell = rows.groupBy { it.key - "strategy" }
        return byCell.values.mapNotNull { g ->
            val a = g.firstOrNull { it.key["strategy"] == N0 }
            val b = g.firstOrNull { it.key["strategy"] == N12 }
            if (a != null && b != null) a to b else null
        }
    }

    private fun scoreboard(main: List<AggregateRow>): String {
        val ps = pairs(main)
        val ids = listOf("lcc", "components", "diameter", "meanPathLen", "dialsPerNodeMin", "linkUpsPerNodeMin", "controlBytesPerNodeS", "joinConnected60s", "joinMedianS")
        val sb = StringBuilder("<h2>Scoreboard</h2><p>For each setting (cell) of the main grid, which strategy was better? A cell counts only if the 95% intervals do not overlap.</p>")
        sb.append("<table><tr><th class=l>Metric</th><th>N1+N2 better</th><th>N0 better</th><th>no clear difference</th><th>cells</th></tr>")
        for (id in ids) {
            val m = metrics.getValue(id)
            val v = ps.map { verdict(it.first, it.second, m) }
            sb.append("<tr><td class=l>${m.label}</td><td>${v.count { it > 0 }}</td><td>${v.count { it < 0 }}</td><td>${v.count { it == 0 }}</td><td>${v.size}</td></tr>")
        }
        sb.append("</table>")
        sb.append(scoreByDensity(ps))
        return sb.toString()
    }

    private fun scoreByDensity(ps: List<Pair<AggregateRow, AggregateRow>>): String {
        val sb = StringBuilder("<p>Largest connected group, split by density and mobility (N1+N2 better / N0 better / no clear difference):</p>")
        sb.append("<table><tr><th class=l>density</th>")
        for (mob in mobilityOrder) sb.append("<th>${mobilityLabel[mob]}</th>")
        sb.append("</tr>")
        for (d in listOf("low", "medium", "high")) {
            sb.append("<tr><td class=l>$d</td>")
            for (mob in mobilityOrder) {
                val v = ps.filter { it.first.key["density"] == d && it.first.key["mobility"] == mob }.map { verdict(it.first, it.second, metrics.getValue("lcc")) }
                sb.append("<td>${v.count { it > 0 }} / ${v.count { it < 0 }} / ${v.count { it == 0 }}</td>")
            }
            sb.append("</tr>")
        }
        return sb.append("</table>").toString()
    }

    private fun densitySection(
        main: List<AggregateRow>,
        density: String,
    ): String {
        val sel = main.filter { it.key["density"] == density && it.key["platform"] == "android" && it.key["degree"] == "5" }
        if (sel.isEmpty()) return ""
        val nodes = sel.map { it.key["nodes"]!!.toInt() }.distinct().sorted()
        val cats = nodes.flatMap { n -> mobilityOrder.map { mob -> n to mob } }.filter { (n, mob) -> sel.any { it.key["nodes"] == "$n" && it.key["mobility"] == mob } }
        val range = mapOf("low" to "2–10", "medium" to "10–50", "high" to "50–200")[density]
        val sb = StringBuilder("<h2>${density.replaceFirstChar { it.uppercase() }} density ($range phones in radio range)</h2>")
        sb.append("<p class=note>Android only, d = 5. Groups of bars: mesh size N; within a group, the five movement patterns in the order: ")
        sb.append(mobilityOrder.joinToString(", ") { mobilityLabel[it]!! }).append(".</p>")
        for (id in listOf("lcc", "diameter", "linkUpsPerNodeMin", "controlBytesPerNodeS", "joinMedianS", "dialsPerNodeMin")) {
            val m = metrics.getValue(id)
            val series =
                listOf(N0, N12).associateWith { s ->
                    cats.map { (n, mob) -> sel.firstOrNull { it.key["nodes"] == "$n" && it.key["mobility"] == mob && it.key["strategy"] == s } }
                }
            sb.append(
                barChart(
                    title = m.label,
                    unit = m.unit,
                    explain = m.explain,
                    labels = cats.map { (n, mob) -> "${mobShort(mob)}" to "N=$n" },
                    series = series.mapValues { (_, rs) -> rs.map { r -> r?.let { it.m(id) to it.c(id) } } },
                ),
            )
        }
        sb.append(cellTable(sel, listOf("peersInRange", "radioLcc", "lcc", "components", "diameter", "meanPathLen", "slotOccupancy", "dialsPerNodeMin", "dialFailureRate", "linkUpsPerNodeMin", "linkLifetimeMedianS", "controlBytesPerNodeS", "joinConnected60s", "joinMedianS"), "Numbers behind the charts"))
        return sb.toString()
    }

    private fun mobShort(m: String) = mapOf("static" to "stat", "waypoint" to "walk", "group" to "grp", "corridor" to "corr", "partition-merge" to "merge")[m] ?: m

    private fun degreeSection(main: List<AggregateRow>): String {
        val sb = StringBuilder("<h2>Effect of the degree budget d</h2><p>How many simultaneous links a phone may hold. Platform defaults: Android 5 (API ≤ 34) or 6 (API 35+); iOS 6 is unverified.</p>")
        for ((density, n) in listOf("low" to 200, "medium" to 200, "high" to 1000)) {
            val sel = main.filter { it.key["density"] == density && it.key["nodes"] == "$n" && it.key["platform"] == "android" }
            if (sel.isEmpty()) continue
            val degrees = sel.map { it.key["degree"]!!.toInt() }.distinct().sorted()
            val cats = mobilityOrder.flatMap { mob -> degrees.map { d -> mob to d } }.filter { (mob, d) -> sel.any { it.key["mobility"] == mob && it.key["degree"] == "$d" } }
            for (id in listOf("lcc", "diameter")) {
                val m = metrics.getValue(id)
                sb.append(
                    barChart(
                        title = "${m.label} — $density density, N=$n, Android",
                        unit = m.unit,
                        explain = "Groups: movement pattern; bars within a group: d = ${degrees.joinToString(", ")}.",
                        labels = cats.map { (mob, d) -> "d$d" to mobShort(mob) },
                        series =
                            listOf(N0, N12).associateWith { s ->
                                cats.map { (mob, d) -> sel.firstOrNull { it.key["mobility"] == mob && it.key["degree"] == "$d" && it.key["strategy"] == s }?.let { it.m(id) to it.c(id) } }
                            },
                    ),
                )
            }
        }
        return sb.toString()
    }

    private fun platformSection(main: List<AggregateRow>): String {
        val sel = main.filter { it.key["degree"] == "5" && it.key["mobility"] in setOf("static", "waypoint") }
        val sb = StringBuilder("<h2>Android only vs. half iPhones</h2><p>In the mixed setting, half of the phones are iPhones and half of those are in the background, awake only 10 s per minute. d = 5, static crowd and walking.</p>")
        sb.append("<table><tr><th class=l>density</th><th>N</th><th class=l>movement</th>")
        for (p in listOf("android", "half-ios")) for (s in listOf(N0, N12)) sb.append("<th>LCC $p<br>$s</th>")
        for (p in listOf("android", "half-ios")) for (s in listOf(N0, N12)) sb.append("<th>diameter $p<br>$s</th>")
        sb.append("</tr>")
        val cells = sel.map { Triple(it.key["density"]!!, it.key["nodes"]!!.toInt(), it.key["mobility"]!!) }.distinct().sortedWith(compareBy({ listOf("low", "medium", "high").indexOf(it.first) }, { it.second }, { it.third }))
        for ((d, n, mob) in cells) {
            sb.append("<tr><td class=l>$d</td><td>$n</td><td class=l>${mobilityLabel[mob]}</td>")
            for (id in listOf("lcc", "diameter")) {
                for (p in listOf("android", "half-ios")) {
                    for (s in listOf(N0, N12)) {
                        val r = sel.firstOrNull { it.key["density"] == d && it.key["nodes"] == "$n" && it.key["mobility"] == mob && it.key["platform"] == p && it.key["strategy"] == s }
                        sb.append("<td>${r?.let { mc(it, id) } ?: "–"}</td>")
                    }
                }
            }
            sb.append("</tr>")
        }
        return sb.append("</table>").toString()
    }

    private fun mergeSection(main: List<AggregateRow>): String {
        val sel = main.filter { it.key["mobility"] == "partition-merge" && it.key["degree"] == "5" }
        if (sel.isEmpty()) return ""
        val sb = StringBuilder("<h2>Partition repair</h2><p>In the split-then-merge scenario, the crowd splits into two groups that walk out of radio range (minute 4) and come back (minute 8). How fast does the mesh grow back together? Unrepaired runs count as 420 s.</p>")
        return sb.append(cellTable(sel, listOf("mergeFirstLinkS", "mergeLccRecoverS", "lcc", "radioLcc"), null)).toString()
    }

    private fun sybilSection(sybil: List<AggregateRow>): String {
        if (sybil.isEmpty()) return ""
        val sb = StringBuilder("<h2>Fake identities (Sybil attack)</h2>")
        sb.append(
            "<p>An attacker runs many fake identities (here 10% or 30% of all nodes; one radio can hold up to 16 connections). " +
                "Fakes advertise with a 10 dB stronger signal, connect to every honest phone they hear, never close links, and never forward anything. " +
                "We measure how many honest link slots the fakes capture and whether the honest phones still form one mesh. " +
                "N=200, d=5, Android. No defenses beyond the strategy itself are modeled.</p>",
        )
        val cats = sybil.map { Triple(it.key["sybilShare"]!!, it.key["density"]!!, it.key["mobility"]!!) }.distinct().sortedWith(compareBy({ it.first }, { listOf("low", "medium", "high").indexOf(it.second) }, { it.third }))
        for (id in listOf("sybilSlotShare", "lcc")) {
            val m = metrics.getValue(id)
            sb.append(
                barChart(
                    title = if (id == "lcc") "Honest largest connected group" else m.label,
                    unit = m.unit,
                    explain = m.explain,
                    labels = cats.map { (sh, d, mob) -> "$d ${mobShort(mob)}" to "${(sh.toDouble() * 100).toInt()}% fake" },
                    series = listOf(N0, N12).associateWith { s -> cats.map { (sh, d, mob) -> sybil.firstOrNull { it.key["sybilShare"] == sh && it.key["density"] == d && it.key["mobility"] == mob && it.key["strategy"] == s }?.let { it.m(id) to it.c(id) } } },
                ),
            )
        }
        sb.append(cellTable(sybil, listOf("sybilSlotShare", "sybilPeerTableShare", "lcc", "radioLcc", "components", "diameter"), "Numbers"))
        return sb.toString()
    }

    private fun dropSection(drop: List<AggregateRow>): String {
        if (drop.none { it.key["dropMeanS"] != "default" }) return ""
        val sb = StringBuilder("<h2>Sensitivity: how often links drop on their own</h2>")
        sb.append(
            "<p>Links in the model also drop at random (supervision timeouts), on average every 45 s — fitted to field data from a sibling project. " +
                "That rate is uncertain, so we also ran with an average of 180 s and with no random drops. Medium density, N=200, d=5, Android.</p>",
        )
        return sb.append(cellTable(drop, listOf("lcc", "diameter", "linkUpsPerNodeMin", "dialsPerNodeMin", "linkLifetimeMedianS", "controlBytesPerNodeS"), null)).toString()
    }

    private fun timeseriesSection(file: File): String {
        val lines = file.readLines().drop(1).map { it.split(",") }
        val bySeries = lines.groupBy { it[0] to it[1] }
        val sb = StringBuilder("<h2>Does the mesh settle? (warm-up check)</h2><p>Largest connected group over the 15 simulated minutes for four example settings (seed 1). The mesh settles within about one minute; measurements start at 2 minutes (dashed line).</p>")
        for (scenario in bySeries.keys.map { it.first }.distinct()) {
            val series = listOf(N0, N12).associateWith { s -> bySeries[scenario to s].orEmpty().map { it[3].toDouble() to it[4].toDouble() } }
            sb.append(lineChart(scenario, series))
        }
        return sb.toString()
    }

    private fun glossary(): String {
        val sb = StringBuilder("<h2>What the measures mean</h2><table><tr><th class=l>Measure</th><th class=l>Unit</th><th class=l>Meaning</th><th class=l>Better</th></tr>")
        for (m in metrics.values) {
            sb.append("<tr><td class=l>${m.label} <span class=note>(${m.id})</span></td><td class=l>${m.unit}</td><td class=l>${m.explain}</td><td class=l>${when (m.higherIsBetter) { true -> "higher"; false -> "lower"; null -> "context" }}</td></tr>")
        }
        return sb.append("</table>").toString()
    }

    private fun fullTables(rows: List<AggregateRow>): String {
        val sb = StringBuilder("<h2>All results</h2><p class=note>Mean ± 95% confidence half-width over seeds. Shaded: N1+N2 clearly better (green) or clearly worse (red) than N0 in the same setting.</p>")
        val ids = listOf("peersInRange", "radioLcc", "lcc", "components", "diameter", "meanPathLen", "slotOccupancy", "dialsPerNodeMin", "dialFailureRate", "linkUpsPerNodeMin", "linkLifetimeMedianS", "controlBytesPerNodeS", "controlShareOfCapacity", "joinConnected60s", "joinMedianS", "mergeFirstLinkS", "mergeLccRecoverS", "sybilSlotShare")
        for (density in listOf("low", "medium", "high")) {
            for (platform in listOf("android", "half-ios")) {
                val sel = rows.filter { it.key["density"] == density && it.key["platform"] == platform && it.key["sybilShare"]!!.toDouble() == 0.0 && it.key["dropMeanS"] == "default" }
                if (sel.isEmpty()) continue
                sb.append("<details><summary>$density density, $platform</summary>").append(cellTable(sel, ids, null)).append("</details>")
            }
        }
        return sb.toString()
    }

    private fun cellTable(
        sel: List<AggregateRow>,
        ids: List<String>,
        summary: String?,
    ): String {
        val keys = listOf("density", "nodes", "mobility", "platform", "degree", "sybilShare", "dropMeanS").filter { k -> sel.map { it.key[k] }.distinct().size > 1 }
        val sb = StringBuilder()
        if (summary != null) sb.append("<details><summary>$summary</summary>")
        sb.append("<table><tr>")
        for (k in keys) sb.append("<th class=l>$k</th>")
        sb.append("<th class=l>strategy</th><th>runs</th>")
        for (id in ids) sb.append("<th title=\"${esc(metrics[id]?.explain ?: id)}\">${metrics[id]?.label ?: id}</th>")
        sb.append("</tr>")
        val byCell = sel.groupBy { it.key - "strategy" }
        for (r in sel) {
            val other = byCell[r.key - "strategy"]?.firstOrNull { it.key["strategy"] != r.key["strategy"] }
            sb.append("<tr>")
            for (k in keys) sb.append("<td class=l>${r.key[k]}</td>")
            sb.append("<td class=l style=\"color:${colors[r.key["strategy"]]}\"><b>${r.key["strategy"]}</b></td><td>${r.n}</td>")
            for (id in ids) {
                var cls = ""
                val m = metrics[id]
                if (r.key["strategy"] == N12 && other != null && m != null) {
                    cls = when (verdict(other, r, m)) { 1 -> " class=better"; -1 -> " class=worse"; else -> "" }
                }
                sb.append("<td$cls>${mc(r, id)}</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</table>")
        if (summary != null) sb.append("</details>")
        return sb.toString()
    }

    private fun mc(
        r: AggregateRow,
        id: String,
    ): String {
        val m = r.m(id)
        if (m.isNaN()) return "–"
        val c = r.c(id)
        return if (c.isNaN()) fmt(m) else "${fmt(m)} ± ${fmt(c)}"
    }

    private fun fmt(v: Double): String =
        when {
            v == 0.0 -> "0"
            abs(v) >= 100 -> String.format(Locale.ROOT, "%.0f", v)
            abs(v) >= 10 -> String.format(Locale.ROOT, "%.1f", v)
            abs(v) >= 0.01 -> String.format(Locale.ROOT, "%.3f", v)
            else -> String.format(Locale.ROOT, "%.2g", v)
        }

    private fun niceMax(v: Double): Double {
        if (v <= 0 || v.isNaN()) return 1.0
        val p = 10.0.pow(floor(log10(v)))
        val f = v / p
        val nf = listOf(1.0, 1.2, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0).first { it >= f - 1e-9 }
        return nf * p
    }

    private fun barChart(
        title: String,
        unit: String,
        explain: String,
        labels: List<Pair<String, String>>,
        series: Map<String, List<Pair<Double, Double>?>>,
    ): String {
        val n = labels.size
        val barW = 7.0
        val groupW = barW * series.size + 4
        val left = 48.0
        val top = 34.0
        val plotH = 170.0
        val w = max(360.0, left + n * groupW + 20)
        val h = top + plotH + 48
        val all = series.values.flatten().filterNotNull().map { (m, c) -> m + (if (c.isNaN()) 0.0 else c) }.filter { !it.isNaN() }
        val ymax = niceMax(all.maxOrNull() ?: 1.0)
        fun y(v: Double) = top + plotH - (v / ymax) * plotH
        val sb = StringBuilder("<div class=chart><svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${w.toInt()}\" height=\"${h.toInt()}\" font-size=\"10\">")
        sb.append("<title>${esc(explain)}</title>")
        sb.append("<text x=\"4\" y=\"14\" font-size=\"12\" font-weight=\"bold\">${esc(title)}</text><text x=\"4\" y=\"27\" fill=\"#4a5568\">${esc(unit)}</text>")
        for (k in 0..4) {
            val v = ymax * k / 4
            sb.append("<line x1=\"$left\" x2=\"${w - 10}\" y1=\"${y(v)}\" y2=\"${y(v)}\" stroke=\"#edf2f7\"/><text x=\"${left - 4}\" y=\"${y(v) + 3}\" text-anchor=\"end\">${fmt(v)}</text>")
        }
        var lastGroup: String? = null
        labels.forEachIndexed { i, (inner, outer) ->
            val gx = left + i * groupW
            series.entries.forEachIndexed { si, (s, vals) ->
                val p = vals[i] ?: return@forEachIndexed
                val (m, c) = p
                if (m.isNaN()) return@forEachIndexed
                val x = gx + 2 + si * barW
                sb.append("<rect x=\"$x\" y=\"${y(m)}\" width=\"${barW - 1}\" height=\"${max(0.0, top + plotH - y(m))}\" fill=\"${colors[s]}\"><title>$s $outer $inner: ${fmt(m)}${if (c.isNaN()) "" else " ± ${fmt(c)}"}</title></rect>")
                if (!c.isNaN() && c > 0) {
                    val cx = x + (barW - 1) / 2
                    sb.append("<line x1=\"$cx\" x2=\"$cx\" y1=\"${y(m + c)}\" y2=\"${y(max(0.0, m - c))}\" stroke=\"#1a202c\" stroke-width=\"0.8\"/>")
                }
            }
            sb.append("<text x=\"${gx + groupW / 2}\" y=\"${top + plotH + 4}\" transform=\"rotate(60 ${gx + groupW / 2} ${top + plotH + 4})\" font-size=\"8\">${esc(inner)}</text>")
            if (outer != lastGroup) {
                sb.append("<line x1=\"$gx\" x2=\"$gx\" y1=\"$top\" y2=\"${top + plotH + 40}\" stroke=\"#cbd5e0\"/><text x=\"${gx + 2}\" y=\"${h - 2}\" font-weight=\"bold\">${esc(outer)}</text>")
                lastGroup = outer
            }
        }
        sb.append("<line x1=\"$left\" x2=\"${w - 10}\" y1=\"${top + plotH}\" y2=\"${top + plotH}\" stroke=\"#4a5568\"/>")
        return sb.append("</svg></div>").toString()
    }

    private fun lineChart(
        title: String,
        series: Map<String, List<Pair<Double, Double>>>,
    ): String {
        val w = 520.0
        val h = 220.0
        val left = 40.0
        val top = 24.0
        val plotW = w - left - 12
        val plotH = h - top - 30
        val xmax = ceil(series.values.flatten().maxOfOrNull { it.first } ?: 900.0)
        fun x(t: Double) = left + t / xmax * plotW
        fun y(v: Double) = top + plotH - v * plotH
        val sb = StringBuilder("<div class=chart><svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${w.toInt()}\" height=\"${h.toInt()}\" font-size=\"10\">")
        sb.append("<text x=\"4\" y=\"14\" font-size=\"11\" font-weight=\"bold\">${esc(title)} — largest connected group</text>")
        for (k in 0..4) {
            val v = k / 4.0
            sb.append("<line x1=\"$left\" x2=\"${left + plotW}\" y1=\"${y(v)}\" y2=\"${y(v)}\" stroke=\"#edf2f7\"/><text x=\"${left - 4}\" y=\"${y(v) + 3}\" text-anchor=\"end\">${fmt(v)}</text>")
        }
        var t = 0.0
        while (t <= xmax) {
            sb.append("<text x=\"${x(t)}\" y=\"${h - 12}\" text-anchor=\"middle\">${(t / 60).toInt()} min</text>")
            t += 180
        }
        sb.append("<line x1=\"${x(120.0)}\" x2=\"${x(120.0)}\" y1=\"$top\" y2=\"${top + plotH}\" stroke=\"#718096\" stroke-dasharray=\"4 3\"/>")
        for ((s, pts) in series) {
            if (pts.isEmpty()) continue
            val d = pts.joinToString(" ") { (tt, v) -> "${String.format(Locale.ROOT, "%.1f", x(tt))},${String.format(Locale.ROOT, "%.1f", y(v))}" }
            sb.append("<polyline points=\"$d\" fill=\"none\" stroke=\"${colors[s]}\" stroke-width=\"1.3\"/>")
        }
        return sb.append("</svg></div>").toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
