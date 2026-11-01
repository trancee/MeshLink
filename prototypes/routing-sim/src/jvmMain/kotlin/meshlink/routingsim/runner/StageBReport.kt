package meshlink.routingsim.runner

import meshlink.routingsim.runner.HtmlReport.barChart
import meshlink.routingsim.runner.HtmlReport.esc
import meshlink.routingsim.runner.HtmlReport.fmt
import meshlink.routingsim.runner.HtmlReport.mc
import java.io.File
import java.util.Locale

/** Stage B report: routing and mesh broadcast on the N1+N2 topology. Inline CSS and SVG only. */
object StageBReport {
    private val unicast = listOf("U1", "U2", "U3", "U4")
    private val broadcast = listOf("B1", "B2", "B3", "gossipsub")
    private val palette =
        mapOf(
            "U1" to "#718096", "U2" to "#d69e2e", "U3" to "#c53030", "U4" to "#2b6cb0",
            "B1" to "#718096", "B2" to "#d69e2e", "B3" to "#2f855a", "gossipsub" to "#6b46c1",
            "N1+N2" to "#2b6cb0", "N1+N2+bridge" to "#2f855a",
            "rotation off" to "#a0aec0", "rotation on" to "#2b6cb0",
        )
    private val names =
        mapOf(
            "U1" to "U1 managed flood",
            "U2" to "U2 scoped topology + source route",
            "U3" to "U3 Babel-like distance vector",
            "U4" to "U4 on-demand (AODV-like)",
            "B1" to "B1 flood",
            "B2" to "B2 fanout subset",
            "B3" to "B3 Plumtree",
            "gossipsub" to "gossipsub",
        )
    private val mobilityOrder = listOf("static", "waypoint", "group", "corridor", "partition-merge")
    private val mobShort = mapOf("static" to "stat", "waypoint" to "walk", "group" to "grp", "corridor" to "corr", "partition-merge" to "merge")

    /** Delivery target of the decision criteria per density class. */
    private fun deliveryTarget(density: String) = if (density == "high") 0.95 else 0.99

    fun render(
        rows: List<AggregateRow>,
        raw: List<Map<String, String>>,
        notes: File?,
        findings: File?,
    ): String {
        val main = rows.filter { it.key["set"] == "main" && it.key["strategy"] == "N1+N2" && !isBridgeE2e(it) }
        val sb = StringBuilder(HtmlReport.head("MeshLink routing simulation — stage B: how should messages find their way?"))
        sb.append(intro(raw, notes))
        if (findings != null) sb.append("<h2>What we found</h2>").append(findings.readText())
        sb.append(criteriaSection(main))
        for (d in listOf("low", "medium", "high")) sb.append(densitySection(main, d))
        sb.append(broadcastSection(main))
        sb.append(firstMessageSection(main))
        sb.append(degreeSection(main))
        sb.append(rotationSection(raw))
        sb.append(lifetimeSection(rows.filter { it.key["set"] == "sweep" }))
        sb.append(bridgeSection(rows))
        sb.append(glossary())
        sb.append(fullTables(rows))
        sb.append("</main></body></html>\n")
        return sb.toString()
    }

    /** Bridge end-to-end runs (low-200 partition-merge) are reported in the bridge section only. */
    private fun isBridgeE2e(r: AggregateRow) = r.key["mobility"] == "partition-merge"

    private fun intro(
        raw: List<Map<String, String>>,
        notes: File?,
    ): String {
        val runs = raw.map { it["runId"] }.toSet().size
        return """
            <h1>MeshLink routing simulation — stage B: how should messages find their way?</h1>
            <p class="warn"><b>Every number on this page is simulated.</b> It comes from a simplified computer model of phones and
            Bluetooth links, calibrated against a few lab measurements. It is not a measurement on real devices.</p>
            <h2>The question</h2>
            <p>Stage A settled <i>which</i> phones connect to each other (N1+N2 “diverse views”, at most d = 5 live links per phone).
            Stage B asks how a message then travels across that mesh, hop by hop, to one recipient (<b>unicast</b>) or to everyone
            (<b>mesh broadcast</b>). Each option trades traffic for reliability and speed:</p>
            <ul>
            <li><b style="color:${palette["U1"]}">U1 managed flood</b>: every phone passes every message on to all its links. No route
            knowledge needed; very robust, but every message costs a transmission on nearly every link of the mesh.</li>
            <li><b style="color:${palette["U2"]}">U2 scoped topology + source route</b>: phones announce their neighbors up to 3 hops
            away; if the sender knows a path it sends along it, otherwise it floods (as in a deployed phone chat app).</li>
            <li><b style="color:${palette["U3"]}">U3 Babel-like distance vector</b>: every phone keeps a table “to reach X, send to
            neighbor Y” and keeps it fresh with periodic and triggered updates (the routing-template choice).</li>
            <li><b style="color:${palette["U4"]}">U4 on-demand (AODV-like)</b>: a phone looks for a route only when it has something to
            send, with a search that widens ring by ring; nothing is sent while the mesh is idle.</li>
            </ul>
            <p>For mesh broadcast: <b style="color:${palette["B1"]}">B1 flood</b>, <b style="color:${palette["B2"]}">B2 fanout subset</b>
            (relays forward to only about log₂(links) links), <b style="color:${palette["B3"]}">B3 Plumtree</b> (payload along a
            self-repairing tree, short “I have it” notes on the other links) and <b style="color:${palette["gossipsub"]}">gossipsub</b>
            (the libp2p publish/subscribe protocol, scaled to BLE link counts).</p>
            <p>Messages carry a <b>message lifetime</b>. Following the accepted design: no phone forwards or holds an expired message,
            and a phone takes a message into <b>store-and-forward</b> (holding it until a route appears) only if at least the
            <b>minimum hold time</b> of lifetime is left. Phones’ clocks may be off by up to ±30 s. Recipients are addressed by a
            <b>routing tag</b> that can rotate every 15 minutes for privacy.</p>
            <p>Each setting was run with several random seeds; tables show the mean and a 95% confidence interval (±). Unless stated
            otherwise: lifetime 5 min, minimum hold 10 s, clock skew ±30 s, rotation off. Every simulated run lasts 15 minutes; the first
            2 minutes are warm-up and not measured. Traffic: every phone sends a chat message every 4 minutes on average (to two nearby
            contacts and one far one), and some phone sends a mesh broadcast every 15 s.</p>
            <p class="note">$runs simulation runs (${raw.size} protocol results; all protocols of one run share the same simulated
            phones, movements and links, so they face exactly the same conditions).</p>
            ${notes?.let { "<details><summary>Run notes (what was run, what was cut and why)</summary><pre>${esc(it.readText())}</pre></details>" } ?: ""}
            """.trimIndent()
    }

    // ---- decision criteria ----

    private class Check(
        val delivery: Boolean,
        val idle: Boolean,
        val first: Boolean?,
    ) {
        val all get() = delivery && idle && (first ?: true)
    }

    private fun check(r: AggregateRow): Check {
        val del = r.m("delivery") >= deliveryTarget(r.key.getValue("density"))
        val idle = r.m("idleCtrlShare") <= 0.10
        val c = r.m("firstP95ClassicalMs")
        val pq = r.m("firstP95PqMs")
        val first = if (c.isNaN() && pq.isNaN()) null else (c.isNaN() || c <= 1000) && (pq.isNaN() || pq <= 1500)
        return Check(del, idle, first)
    }

    private fun rot0(rows: List<AggregateRow>) = rows.filter { it.key["rotation"] == "0" }

    private fun criteriaSection(main: List<AggregateRow>): String {
        val sb = StringBuilder("<h2>Decision criteria scoreboard</h2>")
        sb.append(
            """<p>Criteria from the ticket, checked in every cell (one cell = density × mesh size × movement × platform mix × d).
            A protocol passes a cell if (1) it delivers ≥ 99% of messages at low and medium density and ≥ 95% at high density
            (counting messages whose recipient was in the sender’s connected group when sent), (2) its idle control traffic
            (route upkeep that runs even with no messages) uses ≤ 10% of link capacity, and (3) the first message to a new contact
            arrives within 1 s (95th percentile) for classical and 1.5 s for post-quantum encryption, for recipients ≤ 3 hops away.
            Among protocols that pass, the ranking is: lowest maximum load on any single phone, then lowest 95th-percentile delay.
            Delivery uses the cell mean over seeds.</p>""",
        )
        sb.append("<table><tr><th class=l>density</th><th class=l>protocol</th><th>cells</th><th>delivery ok</th><th>idle ok</th><th>first msg ok</th><th>all ok</th>")
        sb.append("<th>delivery (mean)</th><th>worst cell delivery</th><th>idle share (max)</th><th>max load B/s (mean)</th><th>p95 delay ms (mean)</th><th>rank</th></tr>")
        for (density in listOf("low", "medium", "high")) {
            val ps = unicast + broadcast
            val stats =
                ps.mapNotNull { p ->
                    val cells = rot0(main).filter { it.key["density"] == density && it.key["protocol"] == p }
                    if (cells.isEmpty()) return@mapNotNull null
                    val checks = cells.map { check(it) }
                    p to Triple(cells, checks, checks.count { it.all })
                }
            val ranked =
                stats.filter { (p, s) -> p in unicast && s.third == s.first.size }.sortedWith(
                    compareBy({ it.second.first.map { r -> r.m("loadMaxBps") }.average() }, { it.second.first.map { r -> r.m("latP95Ms") }.average() }),
                ).map { it.first }
            val rankedB =
                stats.filter { (p, s) -> p in broadcast && s.third == s.first.size }.sortedWith(
                    compareBy({ it.second.first.map { r -> r.m("loadMaxBps") }.average() }, { it.second.first.map { r -> r.m("latP95Ms") }.average() }),
                ).map { it.first }
            for ((p, s) in stats) {
                val (cells, checks, allOk) = s
                val firstApplies = checks.any { it.first != null }
                val rank = (if (p in unicast) ranked else rankedB).indexOf(p).let { if (it < 0) "fails" else "#${it + 1}" }
                val cls = if (allOk == cells.size) "better" else "worse"
                sb.append("<tr class=$cls><td class=l>$density</td><td class=l>${names[p]}</td><td>${cells.size}</td>")
                sb.append("<td>${checks.count { it.delivery }}</td><td>${checks.count { it.idle }}</td>")
                sb.append("<td>${if (firstApplies) checks.count { it.first == true }.toString() else "n/a"}</td><td><b>$allOk</b></td>")
                sb.append("<td>${pct(cells.map { it.m("delivery") }.average())}</td><td>${pct(cells.minOf { it.m("delivery") })}</td>")
                sb.append("<td>${pct(cells.maxOf { it.m("idleCtrlShare") })}</td>")
                sb.append("<td>${fmt(cells.map { it.m("loadMaxBps") }.average())}</td><td>${fmt(cells.map { it.m("latP95Ms") }.average())}</td><td>$rank</td></tr>")
            }
        }
        sb.append("</table>")
        sb.append(platformTable(main))
        sb.append("<p class=note>Unicast and mesh broadcast are ranked separately. Rank is given only to protocols that pass every cell of that density; " +
            "“fails” means at least one cell missed a criterion (see the per-density tables for which).</p>")
        return sb.toString()
    }

    /** Delivery criterion per platform mix: where the misses come from. */
    private fun platformTable(main: List<AggregateRow>): String {
        val sb = StringBuilder("<h3>Delivery criterion by platform mix</h3><table><tr><th class=l>density</th><th class=l>protocol</th>")
        sb.append("<th>Android only: cells meeting target</th><th>Android only: mean</th><th>Android only: worst cell</th>")
        sb.append("<th>50/50 Android/iPhone: cells meeting target</th><th>50/50: mean</th><th>50/50: worst cell</th></tr>")
        for (density in listOf("low", "medium", "high")) {
            for (p in unicast + broadcast) {
                val cells = rot0(main).filter { it.key["density"] == density && it.key["protocol"] == p }
                if (cells.isEmpty()) continue
                sb.append("<tr><td class=l>$density</td><td class=l>$p</td>")
                for (platform in listOf("android", "half-ios")) {
                    val c = cells.filter { it.key["platform"] == platform }
                    if (c.isEmpty()) {
                        sb.append("<td>–</td><td>–</td><td>–</td>")
                        continue
                    }
                    val ok = c.count { check(it).delivery }
                    sb.append("<td class=${if (ok == c.size) "better" else "worse"}>$ok / ${c.size}</td><td>${pct(c.map { it.m("delivery") }.average())}</td><td>${pct(c.minOf { it.m("delivery") })}</td>")
                }
                sb.append("</tr>")
            }
        }
        return sb.append("</table>").toString()
    }

    private fun pct(v: Double) = if (v.isNaN()) "–" else String.format(Locale.ROOT, "%.2f%%", v * 100)

    private fun cellLabel(r: AggregateRow) = "N${r.key["nodes"]} ${mobShort[r.key["mobility"]]} ${if (r.key["platform"] == "android") "A" else "A/i"}"

    private fun sortCells(rows: List<AggregateRow>) =
        rows.sortedWith(
            compareBy({ it.key.getValue("nodes").toInt() }, { mobilityOrder.indexOf(it.key["mobility"]) }, { it.key["platform"] }, { it.key.getValue("degree").toInt() }),
        )

    /** Bars per cell (d = 5), one series per protocol. */
    private fun chart(
        rows: List<AggregateRow>,
        protocols: List<String>,
        metric: String,
        title: String,
        unit: String,
        explain: String,
        transform: (Double) -> Double = { it },
        ciScale: Double = 1.0,
        threshold: Double? = null,
    ): String {
        val cells = sortCells(rows.filter { it.key["degree"] == "5" && it.key["protocol"] == protocols.first() })
        val labels = cells.map { "${mobShort[it.key["mobility"]]} ${if (it.key["platform"] == "android") "A" else "A/i"}" to "N${it.key["nodes"]}" }
        val series =
            protocols.associateWith { p ->
                cells.map { c ->
                    rows.firstOrNull { it.key["protocol"] == p && sameCell(it, c) }?.let { transform(it.m(metric)) to it.c(metric) * ciScale }
                }
            }
        return barChart(title, unit, explain, labels, series, palette, threshold)
    }

    private fun sameCell(
        a: AggregateRow,
        b: AggregateRow,
    ) = listOf("density", "nodes", "mobility", "platform", "degree").all { a.key[it] == b.key[it] }

    private fun densitySection(
        main: List<AggregateRow>,
        density: String,
    ): String {
        val rows = rot0(main).filter { it.key["density"] == density }
        if (rows.isEmpty()) return ""
        val target = deliveryTarget(density)
        val sb = StringBuilder("<h2>Unicast at ${density} density</h2>")
        sb.append("<p class=note>Charts show d = 5 (the stage A choice); d = 6 is in the table. A = Android only, A/i = half Android, half iPhone " +
            "(half of the iPhones in the background, reachable ~10 s per minute). stat/walk/grp/corr = static crowd, walking, groups, corridor. " +
            "The red dashed line is the criterion.</p>")
        sb.append(
            chart(
                rows, unicast, "delivery", "Messages lost", "% of messages (recipient reachable when sent)",
                "Share of messages that never arrived although the recipient was in the sender's connected group.",
                transform = { (1 - it) * 100 }, ciScale = 100.0, threshold = (1 - target) * 100,
            ),
        )
        sb.append(chart(rows, unicast, "loadMaxBps", "Busiest phone's load", "bytes per second sent", "Highest per-phone send rate (all messages and control)."))
        sb.append(chart(rows, unicast, "latP95Ms", "Slow messages (95th percentile delay)", "ms", "95% of delivered messages arrived faster than this."))
        sb.append(
            chart(
                rows, unicast, "idleCtrlShare", "Idle control traffic", "% of link capacity",
                "Route-upkeep traffic that runs even when nobody sends, relative to link capacity.", transform = { it * 100 }, ciScale = 100.0, threshold = 10.0,
            ),
        )
        sb.append(chart(rows, unicast, "dataTxPerDelivery", "Transmissions per delivered message", "link transmissions", "How many link sends one delivered message costs."))
        sb.append(unicastTable(rows, target))
        return sb.toString()
    }

    private fun unicastTable(
        rows: List<AggregateRow>,
        target: Double,
    ): String {
        val sb = StringBuilder("<details><summary>Per-cell table</summary><table><tr><th class=l>cell</th><th>d</th><th class=l>protocol</th><th>runs</th>")
        sb.append("<th>delivery</th><th>delivery incl. unreachable</th><th>p50 ms</th><th>p95 ms</th><th>first p95 classical ms</th><th>first p95 PQ ms</th>")
        sb.append("<th>max load B/s</th><th>tx / delivery</th><th>idle share</th><th>hops p95</th><th>criteria</th></tr>")
        for (r in sortCells(rows).sortedWith(compareBy({ it.key.getValue("nodes").toInt() }, { mobilityOrder.indexOf(it.key["mobility"]) }, { it.key["platform"] }, { it.key.getValue("degree").toInt() }, { it.key["protocol"] }))) {
            if (r.key["protocol"] !in unicast) continue
            val ch = check(r)
            val cls = if (ch.all) "better" else "worse"
            val fails = listOfNotNull(if (!ch.delivery) "delivery" else null, if (!ch.idle) "idle" else null, if (ch.first == false) "first msg" else null)
            sb.append("<tr class=$cls><td class=l>${cellLabel(r)}</td><td>${r.key["degree"]}</td><td class=l>${r.key["protocol"]}</td><td>${r.n}</td>")
            for (m in listOf("delivery", "deliveryAll", "latP50Ms", "latP95Ms", "firstP95ClassicalMs", "firstP95PqMs", "loadMaxBps", "dataTxPerDelivery", "idleCtrlShare", "hopsP95")) {
                sb.append("<td>${mc(r, m)}</td>")
            }
            sb.append("<td class=l>${if (fails.isEmpty()) "pass" else "fails " + fails.joinToString()}</td></tr>")
        }
        sb.append("</table><p class=note>Delivery target here: ${pct(target)}. “Delivery incl. unreachable” also counts messages whose recipient was " +
            "not reachable when sent (only store-and-forward can deliver those).</p></details>")
        return sb.toString()
    }

    private fun broadcastSection(main: List<AggregateRow>): String {
        val rows = main.filter { it.key["protocol"] in broadcast }
        if (rows.isEmpty()) return ""
        val sb = StringBuilder("<h2>Mesh broadcast</h2>")
        sb.append("<p>Coverage = share of the phones in the sender’s connected group that received the broadcast. " +
            "Charts show d = 5; low density first, then medium, then high.</p>")
        for (density in listOf("low", "medium", "high")) {
            val d = rows.filter { it.key["density"] == density }
            if (d.isEmpty()) continue
            sb.append("<h3>${density} density</h3>")
            sb.append(
                chart(
                    d, broadcast, "delivery", "Phones missed", "% of reachable phones", "Average share of reachable phones that did not receive a broadcast.",
                    transform = { (1 - it) * 100 }, ciScale = 100.0, threshold = (1 - deliveryTarget(density)) * 100,
                ),
            )
            sb.append(chart(d, broadcast, "dataTxPerDelivery", "Payload transmissions per phone reached", "link transmissions", "Payload sends per receiving phone."))
            sb.append(chart(d, broadcast, "latP95Ms", "Slow arrivals (95th percentile)", "ms", "95% of phones received the broadcast faster than this."))
        }
        sb.append("<details><summary>Per-cell table</summary><table><tr><th class=l>density</th><th class=l>cell</th><th>d</th><th class=l>protocol</th><th>runs</th>")
        sb.append("<th>coverage</th><th>coverage p10</th><th>share fully covered</th><th>p95 ms</th><th>tx / phone reached</th><th>max load B/s</th><th>ctrl B/node·s</th><th>idle share</th></tr>")
        for (r in rows.sortedWith(compareBy({ listOf("low", "medium", "high").indexOf(it.key["density"]) }, { it.key.getValue("nodes").toInt() }, { mobilityOrder.indexOf(it.key["mobility"]) }, { it.key["platform"] }, { it.key["degree"] }, { broadcast.indexOf(it.key["protocol"]) }))) {
            val cls = if (r.m("delivery") >= deliveryTarget(r.key.getValue("density"))) "" else "worse"
            sb.append("<tr class='$cls'><td class=l>${r.key["density"]}</td><td class=l>${cellLabel(r)}</td><td>${r.key["degree"]}</td><td class=l>${r.key["protocol"]}</td><td>${r.n}</td>")
            for (m in listOf("delivery", "coverageP10", "fullCoverShare", "latP95Ms", "dataTxPerDelivery", "loadMaxBps", "ctrlBytesPerNodeS", "idleCtrlShare")) sb.append("<td>${mc(r, m)}</td>")
            sb.append("</tr>")
        }
        sb.append("</table></details>")
        return sb.toString()
    }

    private fun firstMessageSection(main: List<AggregateRow>): String {
        val rows = rot0(main).filter { it.key["protocol"] in unicast }
        val sb = StringBuilder("<h2>First message to a new contact (≤ 3 hops)</h2>")
        sb.append("<p>The first message of a conversation also carries the encryption handshake (post-quantum adds ~2.3 KB). " +
            "Target: 95th percentile ≤ 1 s classical, ≤ 1.5 s post-quantum. Mean over cells of each density, with the worst cell.</p>")
        sb.append("<table><tr><th class=l>density</th><th class=l>protocol</th><th>classical p95 ms (mean)</th><th>classical worst cell</th><th>PQ p95 ms (mean)</th><th>PQ worst cell</th><th>cells passing</th></tr>")
        for (density in listOf("low", "medium", "high")) {
            for (p in unicast) {
                val c = rows.filter { it.key["density"] == density && it.key["protocol"] == p }
                if (c.isEmpty()) continue
                val cl = c.map { it.m("firstP95ClassicalMs") }.filter { !it.isNaN() }
                val pq = c.map { it.m("firstP95PqMs") }.filter { !it.isNaN() }
                val ok = c.count { check(it).first == true }
                sb.append("<tr class=${if (ok == c.size) "better" else "worse"}><td class=l>$density</td><td class=l>${names[p]}</td>")
                sb.append("<td>${fmt(cl.average())}</td><td>${fmt(cl.maxOrNull() ?: Double.NaN)}</td><td>${fmt(pq.average())}</td><td>${fmt(pq.maxOrNull() ?: Double.NaN)}</td><td>$ok / ${c.size}</td></tr>")
            }
        }
        sb.append("</table><p class=note>The handshake itself (55–90 ms classical, 76–159 ms post-quantum median in the bake-off smoke test) " +
            "is part of the link set-up model, not of this per-message delay; this is the routing delay of the larger first message.</p>")
        return sb.toString()
    }

    private fun degreeSection(main: List<AggregateRow>): String {
        val rows = rot0(main).filter { it.key["protocol"] in unicast + broadcast }
        val sb = StringBuilder("<h2>d = 5 versus d = 6</h2><p>Mean over all cells of each density (same cells for both d).</p>")
        sb.append("<table><tr><th class=l>density</th><th class=l>protocol</th><th>delivery d=5</th><th>delivery d=6</th><th>max load d=5</th><th>max load d=6</th><th>p95 ms d=5</th><th>p95 ms d=6</th></tr>")
        for (density in listOf("low", "medium", "high")) {
            for (p in unicast + broadcast) {
                val r5 = rows.filter { it.key["density"] == density && it.key["protocol"] == p && it.key["degree"] == "5" }
                val r6 = rows.filter { it.key["density"] == density && it.key["protocol"] == p && it.key["degree"] == "6" }
                if (r5.isEmpty() || r6.isEmpty()) continue
                fun avg(
                    l: List<AggregateRow>,
                    m: String,
                ) = l.map { it.m(m) }.filter { !it.isNaN() }.average()
                sb.append("<tr><td class=l>$density</td><td class=l>$p</td><td>${pct(avg(r5, "delivery"))}</td><td>${pct(avg(r6, "delivery"))}</td>")
                sb.append("<td>${fmt(avg(r5, "loadMaxBps"))}</td><td>${fmt(avg(r6, "loadMaxBps"))}</td><td>${fmt(avg(r5, "latP95Ms"))}</td><td>${fmt(avg(r6, "latP95Ms"))}</td></tr>")
            }
        }
        return sb.append("</table>").toString()
    }

    /** Paired per-run differences rotation on − off (same run, same traffic, same links). */
    private fun rotationSection(raw: List<Map<String, String>>): String {
        val main = raw.filter { (it["set"] == "main" || it["set"] == "main-no-u3") && it["strategy"] == "N1+N2" && it["mobility"] != "partition-merge" }
        val sb = StringBuilder("<h2>Routing-tag rotation every 15 minutes</h2>")
        sb.append("<p>Recipients are addressed by a routing tag that changes every 15 minutes. Old routes then point at a tag nobody uses " +
            "any more, so routes must be found again. Budget from the ticket: at most +10% transmissions and at most −1 percentage point delivery. " +
            "Each run carries both variants over identical links and traffic, so the differences below are paired per run (mean ± 95% CI over all runs " +
            "of that density, and the worst cell mean). Transmissions = all link sends of data and control packets, counted as bytes sent. " +
            "U1 does not use routes and is unaffected.</p>")
        sb.append("<table><tr><th class=l>density</th><th class=l>protocol</th><th>runs</th><th>Δ delivery (pp)</th><th>worst cell Δ delivery</th><th>Δ data transmissions</th><th>Δ bytes sent (all)</th><th>worst cell Δ bytes</th><th>within budget</th></tr>")
        for (density in listOf("low", "medium", "high")) {
            for (p in listOf("U2", "U3", "U4")) {
                val byRun = main.filter { it["density"] == density && it["protocol"] == p }.groupBy { it.getValue("runId") }
                val pairs = byRun.values.mapNotNull { l -> l.firstOrNull { it["rotation"] == "0" }?.let { a -> l.firstOrNull { it["rotation"] == "1" }?.let { b -> a to b } } }
                if (pairs.isEmpty()) continue
                fun d(m: String) = pairs.mapNotNull { (a, b) -> diff(a, b, m) }
                fun rel(m: String) = pairs.mapNotNull { (a, b) -> relDiff(a, b, m) }
                val dDel = meanCi(d("delivery").map { it * 100 })
                val dTx = meanCi(rel("dataTxPerNodeS").map { it * 100 })
                val dBytes = meanCi(rel("loadMeanBps").map { it * 100 })
                val cells = pairs.groupBy { (a, _) -> listOf("nodes", "mobility", "platform", "degree").joinToString { a.getValue(it) } }
                val worstDel = cells.values.minOf { l -> l.mapNotNull { (a, b) -> diff(a, b, "delivery") }.average() * 100 }
                val worstBytes = cells.values.maxOf { l -> l.mapNotNull { (a, b) -> relDiff(a, b, "loadMeanBps") }.average() * 100 }
                val ok = dDel.first >= -1.0 && dBytes.first <= 10.0
                sb.append("<tr class=${if (ok) "better" else "worse"}><td class=l>$density</td><td class=l>${names[p]}</td><td>${pairs.size}</td>")
                sb.append("<td>${pm(dDel)}</td><td>${fmt2(worstDel)}</td><td>${pm(dTx)}%</td><td>${pm(dBytes)}%</td><td>${fmt2(worstBytes)}%</td><td>${if (ok) "yes" else "no"}</td></tr>")
            }
        }
        return sb.append("</table><p class=note>“Within budget” uses the density mean; check the worst-cell columns for individual cells.</p>").toString()
    }

    private fun diff(
        a: Map<String, String>,
        b: Map<String, String>,
        m: String,
    ): Double? {
        val x = a[m]?.toDoubleOrNull() ?: return null
        val y = b[m]?.toDoubleOrNull() ?: return null
        return if (x.isNaN() || y.isNaN()) null else y - x
    }

    private fun relDiff(
        a: Map<String, String>,
        b: Map<String, String>,
        m: String,
    ): Double? {
        val x = a[m]?.toDoubleOrNull() ?: return null
        val y = b[m]?.toDoubleOrNull() ?: return null
        return if (x.isNaN() || y.isNaN() || x == 0.0) null else (y - x) / x
    }

    private fun pm(p: Pair<Double, Double>) =
        if (p.second.isNaN()) fmt2(p.first) else "${fmt2(p.first)} ± ${String.format(Locale.ROOT, "%.2f", p.second)}"

    private fun fmt2(v: Double) = if (v.isNaN()) "–" else String.format(Locale.ROOT, "%+.2f", v)

    private fun lifetimeSection(sweep: List<AggregateRow>): String {
        if (sweep.isEmpty()) return ""
        val sb = StringBuilder("<h2>Message lifetime, minimum hold time and clock skew</h2>")
        sb.append(
            """<p>Rules under test: never forward or hold an expired message; take a message into store-and-forward only if at least the
            minimum hold time of its lifetime is left. Swept on N = 200, low and medium density, static and walking, Android only, d = 5,
            rotation on: lifetime 30 s / 5 min / 1 h / 24 h × minimum hold 0 / 10 / 60 s × clock skew 0 / ±30 s for U1 and U4;
            U3 only at lifetime 30 s and 5 min with skew ±30 s (cost). A simulated run lasts 15 minutes, so 1 h and 24 h lifetimes
            never expire inside a run: those rows show the “holds until a route appears” limit, not long-term storage behavior.</p>""",
        )
        val protos = sweep.map { it.key.getValue("protocol") }.toSet().sortedBy { unicast.indexOf(it) }
        for (p in protos) {
            val rows = sweep.filter { it.key["protocol"] == p }
            sb.append("<h3>${names[p]}</h3>")
            for (skew in rows.map { it.key.getValue("skewS") }.toSet().sorted()) {
                val r = rows.filter { it.key["skewS"] == skew }
                val labels = ArrayList<Pair<String, String>>()
                val keys = ArrayList<Pair<String, String>>()
                for (cell in r.map { "${it.key["density"]} ${mobShort[it.key["mobility"]]}" }.toSet().sorted()) {
                    for (life in r.map { it.key.getValue("lifetimeS").toInt() }.toSet().sorted()) {
                        labels.add(lifeLabel(life) to cell)
                        keys.add(cell to life.toString())
                    }
                }
                val holds = r.map { it.key.getValue("holdS").toInt() }.toSet().sorted()
                val holdPalette = holds.mapIndexed { i, h -> "hold ${h}s" to listOf("#90cdf4", "#3182ce", "#1a365d")[i % 3] }.toMap()
                val series =
                    holds.associate { h ->
                        "hold ${h}s" to
                            keys.map { (cell, life) ->
                                r.firstOrNull { "${it.key["density"]} ${mobShort[it.key["mobility"]]}" == cell && it.key["lifetimeS"] == life && it.key["holdS"] == h.toString() }
                                    ?.let { (1 - it.m("deliveryAll")) * 100 to it.c("deliveryAll") * 100 }
                            }
                    }
                sb.append(
                    barChart(
                        "${p}: messages lost, skew ${if (skew == "0") "0" else "±${skew}"} s", "% of all messages (incl. unreachable recipients)",
                        "Lost share by lifetime and minimum hold time", labels, series, holdPalette,
                    ),
                )
            }
            sb.append("<details><summary>${p} sweep table</summary><table><tr><th class=l>cell</th><th>lifetime</th><th>hold s</th><th>skew s</th><th>runs</th>")
            sb.append("<th>delivery</th><th>delivery incl. unreachable</th><th>p95 ms</th><th>expired drops / msg</th><th>hold refused / msg</th><th>no route / msg</th><th>held (mean)</th></tr>")
            for (x in rows.sortedWith(compareBy({ it.key["density"] }, { it.key["mobility"] }, { it.key.getValue("lifetimeS").toInt() }, { it.key.getValue("holdS").toInt() }, { it.key["skewS"] }))) {
                sb.append("<tr><td class=l>${x.key["density"]} ${mobShort[x.key["mobility"]]}</td><td>${lifeLabel(x.key.getValue("lifetimeS").toInt())}</td><td>${x.key["holdS"]}</td><td>${x.key["skewS"]}</td><td>${x.n}</td>")
                for (m in listOf("delivery", "deliveryAll", "latP95Ms", "dropExpiredPerSent", "dropHold_refusedPerSent", "dropNo_routePerSent", "heldMean")) sb.append("<td>${mc(x, m)}</td>")
                sb.append("</tr>")
            }
            sb.append("</table></details>")
        }
        return sb.toString()
    }

    private fun lifeLabel(s: Int) =
        when {
            s >= 86_400 -> "${s / 86_400} d"
            s >= 3_600 -> "${s / 3_600} h"
            s >= 60 -> "${s / 60} min"
            else -> "$s s"
        }

    private fun bridgeSection(rows: List<AggregateRow>): String {
        val topo = rows.filter { it.key["set"] == "none" }
        val merge = topo.filter { it.key["mobility"] == "partition-merge" }
        val sb = StringBuilder("<h2>Variant: N1+N2 plus “prefer a newly discovered phone from another group” (bridge)</h2>")
        sb.append(
            """<p>When two groups of phones that had been apart come into radio range, plain N1+N2 may keep all its links inside its own
            group. The bridge variant notices a phone it has just started hearing (silent for ≥ 60 s, first heard within the last 10 s)
            that is not reachable within 2 hops, and connects to it, if necessary by dropping its most redundant link. Measured in the
            partition-and-merge scenario (two groups apart for a while, then merging) without message traffic: time until the first
            link between the groups, and time until the largest connected group recovers. Lower is better.</p>""",
        )
        if (merge.isNotEmpty()) {
            val ref = sortCells(merge.filter { it.key["strategy"] == "N1+N2" })
            val labels = ref.map { "${it.key["density"]} ${if (it.key["platform"] == "android") "A" else "A/i"}" to "N${it.key["nodes"]}" }
            for ((m, title) in listOf("mergeFirstLinkS" to "First link between the groups", "mergeLccRecoverS" to "Largest group recovered")) {
                val series =
                    listOf("N1+N2", "N1+N2+bridge").associateWith { s ->
                        ref.map { c -> merge.firstOrNull { it.key["strategy"] == s && sameCell(it, c) && it.key["density"] == c.key["density"] }?.let { it.m(m) to it.c(m) } }
                    }
                sb.append(barChart(title, "seconds after the groups meet", title, labels, series, palette))
            }
            sb.append("<table><tr><th class=l>density</th><th>N</th><th class=l>platform</th><th class=l>strategy</th><th>runs</th><th>first link s</th><th>group recovered s</th><th>largest group</th><th>link-ups / node·min</th><th>control share</th></tr>")
            for (r in merge.sortedWith(compareBy({ listOf("low", "medium", "high").indexOf(it.key["density"]) }, { it.key.getValue("nodes").toInt() }, { it.key["platform"] }, { it.key["strategy"] }))) {
                sb.append("<tr><td class=l>${r.key["density"]}</td><td>${r.key["nodes"]}</td><td class=l>${r.key["platform"]}</td><td class=l>${r.key["strategy"]}</td><td>${r.n}</td>")
                for (m in listOf("mergeFirstLinkS", "mergeLccRecoverS", "lcc", "linkUpsPerNodeMin", "controlShareOfCapacity")) sb.append("<td>${mc(r, m)}</td>")
                sb.append("</tr>")
            }
            sb.append("</table>")
        }
        val reg = topo.filter { it.key["mobility"] != "partition-merge" }
        if (reg.isNotEmpty()) {
            sb.append("<h3>Side effects on ordinary movement (N = 200, Android only, d = 5)</h3><table><tr><th class=l>density</th><th class=l>movement</th><th class=l>strategy</th><th>runs</th><th>largest group</th><th>separate groups</th><th>diameter</th><th>link-ups / node·min</th><th>dials / node·min</th><th>control share</th></tr>")
            for (r in reg.sortedWith(compareBy({ listOf("low", "medium", "high").indexOf(it.key["density"]) }, { mobilityOrder.indexOf(it.key["mobility"]) }, { it.key["strategy"] }))) {
                sb.append("<tr><td class=l>${r.key["density"]}</td><td class=l>${r.key["mobility"]}</td><td class=l>${r.key["strategy"]}</td><td>${r.n}</td>")
                for (m in listOf("lcc", "components", "diameter", "linkUpsPerNodeMin", "dialsPerNodeMin", "controlShareOfCapacity")) sb.append("<td>${mc(r, m)}</td>")
                sb.append("</tr>")
            }
            sb.append("</table>")
        }
        val e2e = rows.filter { it.key["set"] == "main" && it.key["mobility"] == "partition-merge" && it.key["rotation"] == "0" }
        if (e2e.isNotEmpty()) {
            sb.append("<h3>With message traffic (low density, N = 200, partition and merge)</h3><table><tr><th class=l>platform</th><th class=l>protocol</th><th class=l>strategy</th><th>runs</th><th>delivery</th><th>delivery incl. unreachable</th><th>p95 ms</th><th>max load B/s</th></tr>")
            for (r in e2e.sortedWith(compareBy({ it.key["platform"] }, { (unicast + broadcast).indexOf(it.key["protocol"]) }, { it.key["strategy"] }))) {
                sb.append("<tr><td class=l>${r.key["platform"]}</td><td class=l>${r.key["protocol"]}</td><td class=l>${r.key["strategy"]}</td><td>${r.n}</td>")
                for (m in listOf("delivery", "deliveryAll", "latP95Ms", "loadMaxBps")) sb.append("<td>${mc(r, m)}</td>")
                sb.append("</tr>")
            }
            sb.append("</table>")
        }
        return sb.toString()
    }

    private fun glossary() =
        """
        <h2>Words used here</h2>
        <ul class=note>
        <li><b>node / phone</b>: one device running MeshLink. <b>peer</b>: another node in radio range. <b>link</b>: a live BLE connection.</li>
        <li><b>hop</b>: one link a message crosses. <b>hop limit</b>: the most hops a message may travel.</li>
        <li><b>message lifetime</b>: how long a message stays valid; afterwards nobody forwards or holds it.</li>
        <li><b>store-and-forward</b>: a node holds a message while no route exists and sends it once one appears.</li>
        <li><b>minimum hold time</b>: a node takes a message into store-and-forward only if at least this much lifetime is left.</li>
        <li><b>routing tag</b>: the 8-byte address a message is routed to; it can rotate every 15 minutes.</li>
        <li><b>95% CI (±)</b>: range that would contain the true simulated mean in 95% of repetitions with new seeds.</li>
        </ul>
        """.trimIndent()

    private fun fullTables(rows: List<AggregateRow>): String {
        val sb = StringBuilder("<h2>All aggregated rows</h2><details><summary>Show (${rows.size} rows; also in stage-b-aggregate.csv)</summary><table><tr>")
        val keys = StageBAggregate.groupColumns.filter { it != "instance" }
        val ms = listOf("delivery", "deliveryAll", "latP95Ms", "loadMaxBps", "dataTxPerDelivery", "idleCtrlShare", "lcc")
        for (k in keys) sb.append("<th class=l>$k</th>")
        sb.append("<th>runs</th>")
        for (m in ms) sb.append("<th>$m</th>")
        sb.append("</tr>")
        for (r in rows) {
            sb.append("<tr>")
            for (k in keys) sb.append("<td class=l>${esc(r.key[k] ?: "")}</td>")
            sb.append("<td>${r.n}</td>")
            for (m in ms) sb.append("<td>${mc(r, m)}</td>")
            sb.append("</tr>")
        }
        return sb.append("</table></details>").toString()
    }
}
