package meshlink.routingsim.runner

import meshlink.routingsim.runner.HtmlReport.barChart
import meshlink.routingsim.runner.HtmlReport.esc
import meshlink.routingsim.runner.HtmlReport.fmt
import java.io.File
import java.util.Locale
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/** Stage E report: security costs of the final v1 configuration. Inline CSS and SVG only. */
object StageEReport {
    private class Run(
        val id: String,
        val cell: String,
        val seed: String,
        val rot: String,
        val set: String,
        val inst: Map<String, Map<String, String>>,
    ) {
        fun v(
            inst: String,
            m: String,
        ) = this.inst[inst]?.get(m)?.toDoubleOrNull() ?: Double.NaN

        fun t(m: String) = inst.values.first()["t_$m"]?.toDoubleOrNull() ?: Double.NaN
    }

    /** Mean and 95% CI half-width (Student t). */
    private class Stat(
        val mean: Double,
        val ci: Double,
        val n: Int,
    ) {
        fun fmt(
            scale: Double = 1.0,
            digits: Int = -1,
        ): String {
            if (mean.isNaN()) return "–"
            fun f(x: Double) = if (digits >= 0) String.format(Locale.ROOT, "%.${digits}f", x * scale) else HtmlReport.fmt(x * scale)
            return if (ci.isNaN()) f(mean) else "${f(mean)} ± ${f(ci)}"
        }
    }

    private fun stat(xs: List<Double>): Stat {
        val v = xs.filter { !it.isNaN() }
        if (v.isEmpty()) return Stat(Double.NaN, Double.NaN, 0)
        val mu = v.average()
        if (v.size < 2) return Stat(mu, Double.NaN, v.size)
        val sd = sqrt(v.sumOf { (it - mu) * (it - mu) } / (v.size - 1))
        return Stat(mu, Aggregate.tQuantile(v.size - 1) * sd / sqrt(v.size.toDouble()), v.size)
    }

    /** Median and range over seeds: robust for per-run percentiles of small subgroups. */
    private fun med(xs: List<Double>): String {
        val v = xs.filter { !it.isNaN() }.sorted()
        if (v.isEmpty()) return "–"
        val m = if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / 2
        return if (v.size < 2) fmt(m) else "${fmt(m)} <small>(${fmt(v.first())}–${fmt(v.last())})</small>"
    }

    private fun medValue(xs: List<Double>): Double {
        val v = xs.filter { !it.isNaN() }.sorted()
        if (v.isEmpty()) return Double.NaN
        return if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / 2
    }

    /** 1 − e^(−x) without underflow for tiny x. */
    private fun oneMinusExp(x: Double) = if (x < 1e-6) x else 1 - exp(-x)

    fun inst(r: Map<String, String>): String {
        val p = r["protocol"] ?: ""
        val base = if (p == "B1B3") "BA" else p
        val v = r["variant"] ?: "-"
        val name = if (v == "-" || v.isEmpty()) base else "$base $v"
        return if (r["rotation"] == "0") "$name r0" else name
    }

    private fun cellOf(r: Map<String, String>) = listOf(r["scenario"], r["density"], r["platform"]).joinToString("|")

    private val cellOrder =
        listOf("static|low", "static|medium", "static|high", "waypoint|medium", "arrival|dynamic").flatMap { c ->
            listOf("android", "half-ios").map { "$c|$it" }
        }

    private fun cellTitle(cell: String): String {
        val p = cell.split('|')
        val what =
            when ("${p[0]}|${p[1]}") {
                "static|low" -> "low static"
                "static|medium" -> "medium static"
                "static|high" -> "high static"
                "waypoint|medium" -> "medium walking"
                else -> "arrival (low → high)"
            }
        return "$what, ${if (p[2] == "android") "Android" else "half iOS"}"
    }

    private fun runs(rows: List<Map<String, String>>): List<Run> =
        rows.groupBy { it.getValue("runId") }.map { (id, rs) ->
            val f = rs.first()
            Run(id, cellOf(f), f.getValue("seed"), f["rot"] ?: "", f.getValue("set"), rs.associateBy { inst(it) })
        }

    private fun pct(v: Double) = if (v.isNaN()) "–" else String.format(Locale.ROOT, "%.2f%%", v * 100)

    private const val V1U = "UA ${StageDPlan.V1}"
    private const val V1B = "BA ${StageDPlan.FINAL_BROADCAST}"

    /** Routing and topology bytes on the air per phone-second for the v1 pair ([u], [b]) in one run. */
    private fun airBytes(
        r: Run,
        u: String,
        b: String,
    ) = r.v(u, "dataBytesPerNodeS") + r.v(u, "ctrlBytesPerNodeS") + r.v(b, "dataBytesPerNodeS") + r.v(b, "ctrlBytesPerNodeS") +
        r.t("controlBytesPerNodeS")

    private fun dataTx(
        r: Run,
        u: String,
        b: String,
    ) = r.v(u, "dataTxPerNodeS") + r.v(b, "dataTxPerNodeS")

    private class RotCheck(
        val cell: String,
        val period: String,
        val dU: Stat,
        val dB: Stat,
        val dBytes: Stat,
        val dTx: Stat,
        val inRunU: Stat,
        val rreq: Stat,
        val p95Post: Stat,
        val p95PostR0: Stat,
        val delPost: Stat,
        val nPost: Double,
        val inflation: Stat,
    ) {
        val txWorst get() = maxOf(dBytes.mean, dTx.mean)

        /** "within" if every mean is inside the budget; "over" if a breach is significant (its CI excludes the budget); else "unclear". */
        val verdict: String get() {
            val loss = listOf(dU, dB)
            val gain = listOf(dBytes, dTx)
            if (loss.all { it.mean >= -0.01 } && gain.all { it.mean <= 0.10 }) return "within"
            val sig = loss.any { it.mean + it.ci.orZero() < -0.01 } || gain.any { it.mean - it.ci.orZero() > 0.10 }
            return if (sig) "over" else "unclear"
        }
        val pass get() = verdict == "within"

        private fun Double.orZero() = if (isNaN()) 0.0 else this
    }

    private val periodNames = mapOf("60" to "1 min", "300" to "5 min", "900" to "15 min (v1)", "3600" to "60 min", "300a" to "5 min aligned")

    private fun rotChecks(all: List<Run>): List<RotCheck> {
        val none = all.filter { it.set == CSet.E_ROT.label && it.rot == "none" }.associateBy { it.cell + "|" + it.seed }
        val out = ArrayList<RotCheck>()
        for (cell in cellOrder) for (p in listOf("60", "300", "900", "3600", "300a")) {
            val rs = all.filter { it.cell == cell && it.rot == p && (it.set == CSet.E_ROT.label || (p == "900" && it.set == CSet.E_BASE.label)) }
            if (rs.isEmpty()) continue
            val pairs = rs.mapNotNull { r -> none["${r.cell}|${r.seed}"]?.let { r to it } }
            fun rel(
                a: Double,
                b: Double,
            ) = a / b - 1
            out +=
                RotCheck(
                    cell, p,
                    stat(pairs.map { (r, n) -> r.v(V1U, "delivery") - n.v("$V1U r0", "delivery") }),
                    stat(pairs.map { (r, n) -> r.v(V1B, "delivery") - n.v("$V1B r0", "delivery") }),
                    stat(pairs.map { (r, n) -> rel(airBytes(r, V1U, V1B), airBytes(n, "$V1U r0", "$V1B r0")) }),
                    stat(pairs.map { (r, n) -> rel(dataTx(r, V1U, V1B), dataTx(n, "$V1U r0", "$V1B r0")) }),
                    stat(rs.map { it.v(V1U, "delivery") - it.v("$V1U r0", "delivery") }),
                    stat(rs.map { rel(it.v(V1U, "rreqBytesPerNodeS") + it.v(V1U, "rrepBytesPerNodeS"), it.v("$V1U r0", "rreqBytesPerNodeS") + it.v("$V1U r0", "rrepBytesPerNodeS")) }),
                    stat(rs.map { it.v(V1U, "p95_postrot") }),
                    stat(rs.map { it.v("$V1U r0", "p95_postrot") }),
                    stat(rs.map { it.v(V1U, "del_postrot") }),
                    rs.sumOf { it.v(V1U, "n_postrot").let { x -> if (x.isNaN()) 0.0 else x } },
                    stat(all.filter { it.cell == cell }.map { it.t("estRot_${p}_ratio") / it.t("estRot_none_ratio") - 1 }),
                )
        }
        return out
    }

    private fun rotationSection(all: List<Run>): String {
        val checks = rotChecks(all)
        val sb = StringBuilder("<h2>1. How often should a phone change its identifiers?</h2>")
        sb.append(
            """<p>Each phone advertises a short-lived identifier and is addressed by a short-lived <b>routing tag</b>. Both change
            together every <i>rotation period</i>, so an observer cannot follow a phone for long. A change has a cost: neighbors briefly
            see the phone as a new one (the local density estimate counts it twice), and routes to the old tag are useless, so the mesh
            must find the phone again. Each phone rotates at its own random moment (its phase), except in the <b>aligned</b> row where
            all phones rotate at the same instant. Tags follow the phone's own clock (±30 s skew); a receiver accepts the previous,
            current and next tag.</p>
            <p><b>Budget</b> (accepted design): at most <b>+10% transmissions</b> and at most <b>−1 percentage point delivery</b> against
            no rotation, judged per density class. "Against no rotation" compares each run with the same cell and seed run without any
            rotation (paired), so it includes every knock-on effect on links and the density estimate. <b>Bytes</b> counts all routing
            and link-control bytes on the air (unicast and broadcast data, route search, neighbor lists); <b>transmissions</b> counts
            data frames sent. "Same run, tags fixed" compares with a copy of the unicast protocol inside the same run whose tags never
            change (the topology is then identical), which isolates the cost of relearning routes.</p>""",
        )
        sb.append("<table><tr><th class=l>cell</th><th class=l>period</th><th>unicast Δ delivery (pp)</th><th>broadcast Δ delivery (pp)</th>")
        sb.append("<th>Δ bytes on air</th><th>Δ data transmissions</th><th>same run, tags fixed: unicast Δ (pp)</th><th>route search bytes vs tags fixed</th>")
        sb.append("<th>unicast p95 after a rotation (ms)</th><th>… same messages, tags fixed</th><th>delivery after a rotation (messages)</th><th>density over-count</th><th class=l>budget</th></tr>")
        for (c in checks) {
            sb.append("<tr><td class=l>${esc(cellTitle(c.cell))}</td><td class=l>${periodNames[c.period]}</td>")
            sb.append("<td>${c.dU.fmt(100.0, 2)}</td><td>${c.dB.fmt(100.0, 2)}</td><td>${c.dBytes.fmt(100.0, 1)}%</td><td>${c.dTx.fmt(100.0, 1)}%</td>")
            sb.append("<td>${c.inRunU.fmt(100.0, 2)}</td><td>${c.rreq.fmt(100.0, 1)}%</td><td>${c.p95Post.fmt()}</td><td>${c.p95PostR0.fmt()}</td>")
            sb.append("<td>${c.delPost.fmt(100.0, 2)}% (${fmt(c.nPost)})</td><td>${c.inflation.fmt(100.0, 1)}%</td>")
            sb.append("<td class=${if (c.pass) "better" else "\"worse l\""}>${c.verdict}</td></tr>")
        }
        sb.append("</table>")
        sb.append("<h3>Budget per density class</h3><table><tr><th class=l>period</th>")
        val classes = listOf("low" to listOf("static|low"), "medium" to listOf("static|medium", "waypoint|medium"), "high" to listOf("static|high"), "arrival" to listOf("arrival|dynamic"))
        for ((k, _) in classes) sb.append("<th>$k</th>")
        sb.append("</tr>")
        for (p in listOf("60", "300", "300a", "900", "3600")) {
            sb.append("<tr><td class=l>${periodNames[p]}</td>")
            for ((_, prefixes) in classes) {
                val cs = checks.filter { it.period == p && prefixes.any { pre -> it.cell.startsWith(pre) } }
                val ok = cs.isNotEmpty() && cs.all { it.pass }
                val over = cs.any { it.verdict == "over" }
                val worstD = cs.minOfOrNull { minOf(it.dU.mean, it.dB.mean) } ?: Double.NaN
                val worstT = cs.maxOfOrNull { it.txWorst } ?: Double.NaN
                sb.append("<td class=${if (ok) "better" else "worse"}>${if (ok) "within" else if (over) "over" else "unclear"}: worst ${String.format(Locale.ROOT, "%+.2f", worstD * 100)} pp, ")
                sb.append("${String.format(Locale.ROOT, "%+.1f", worstT * 100)}%</td>")
            }
            sb.append("</tr>")
        }
        sb.append(
            "</table><p class=note>Worst = the worst cell mean of the class (both platform mixes). Deltas are simulated means over 5 " +
                "paired seeds ± 95% CI. <b>within</b>: every mean inside the budget; <b>over</b>: a breach whose CI excludes the budget; " +
                "<b>unclear</b>: a mean outside the budget whose CI still reaches inside it. Runs with and without rotation diverge " +
                "chaotically after the first changed decision, so the paired \"Δ bytes\" also carries run-to-run noise; the 60 min row " +
                "(about a quarter of the phones rotate once in a 15 min run) shows that noise floor.</p>",
        )
        val labels = checks.map { periodNames[it.period]!!.replace(" (v1)", "") to cellTitle(it.cell).replace(", Android", " A").replace(", half iOS", " ½iOS") }
        sb.append(
            barChart(
                "Extra bytes on the air from rotation", "% more than without rotation (dashed = +10% budget)", "", labels,
                mapOf("bytes" to checks.map { it.dBytes.mean * 100 to it.dBytes.ci * 100 }), mapOf("bytes" to "#3182ce"), 10.0,
            ),
        )
        return sb.toString()
    }

    private fun sizeSection(
        all: List<Run>,
        collision: String,
    ): String {
        val base = all.filter { it.set == CSet.E_BASE.label }
        val sb = StringBuilder("<h2>2. How many bytes for the routing tag and the message ID?</h2>")
        sb.append(
            """<p>Every message carries a <b>routing tag</b> (who it is for) and a <b>message ID</b> (so that phones drop copies they have
            already seen). Shorter fields save bytes on every hop, but random values collide more often. v1 uses an 8-byte tag and a
            16-byte message ID. Each row is a copy of the v1 protocol with other sizes, run on the same links and traffic. Δ values are
            paired against v1 sizes in the same run.</p>""",
        )
        val combos =
            listOf(4, 8, 16).flatMap { t -> listOf(8, 12, 16).map { i -> t to i } }.map { (t, i) ->
                Triple("unicast", "tag $t B, ID $i B", if (t == 8 && i == 16) V1U else "$V1U+T$t+I$i")
            } + listOf("T8+I8", "T8+I12", "T4+I16", "T16+I16").map {
                Triple("broadcast", it.replace("T", "tag ").replace("+I", " B, ID ") + " B", "$V1B+$it")
            } + listOf(Triple("broadcast", "tag 8 B, ID 16 B", V1B))
        sb.append("<table><tr><th class=l>traffic</th><th class=l>sizes</th><th>delivery (all cells)</th><th>Δ delivery vs v1 sizes (pp)</th>")
        sb.append("<th>Δ routing bytes per phone</th><th>Δ busiest phone</th><th>Δ p95 latency (ms)</th></tr>")
        for ((kind, label, key) in combos) {
            val ref = if (kind == "unicast") V1U else V1B
            fun d(m: String) = stat(base.map { it.v(key, m) - it.v(ref, m) })
            fun r(m: String) = stat(base.map { it.v(key, m) / it.v(ref, m) - 1 })
            val bytes = stat(base.map { (it.v(key, "dataBytesPerNodeS") + it.v(key, "ctrlBytesPerNodeS")) / (it.v(ref, "dataBytesPerNodeS") + it.v(ref, "ctrlBytesPerNodeS")) - 1 })
            sb.append("<tr><td class=l>$kind</td><td class=l>$label</td><td>${stat(base.map { it.v(key, "delivery") }).fmt(100.0, 2)}%</td>")
            sb.append("<td>${d("delivery").fmt(100.0, 2)}</td><td>${bytes.fmt(100.0, 1)}%</td><td>${r("loadMaxBps").fmt(100.0, 1)}%</td><td>${d("latP95Ms").fmt()}</td></tr>")
        }
        sb.append("</table><p class=note>Pooled over all 10 cells × 5 seeds (simulated). Busiest-phone Δ is noisy because the busiest phone changes between copies.</p>")
        sb.append(collision)
        return sb.toString()
    }

    /** Birthday bound: probability that at least two of [m] random [bits]-bit values are equal. */
    fun birthday(
        m: Double,
        bits: Int,
    ): Double {
        return oneMinusExp(m * (m - 1) / 2.0.pow(bits + 1))
    }

    private fun sci(p: Double) =
        when {
            p >= 0.01 -> String.format(Locale.ROOT, "%.2f", p)
            p == 0.0 -> "0"
            else -> {
                val e = kotlin.math.floor(kotlin.math.log10(p)).toInt()
                String.format(Locale.ROOT, "%.1f×10<sup>%d</sup>", p / 10.0.pow(e), e)
            }
        }

    /** Analytic collision table; [tableMean] = mean route-table size seen in the grid. */
    fun collisionHtml(tableMean: Double): String {
        val chatRate = 1000.0 / 240.0
        val bcastRate = 1.0 / 15.0
        val rate = chatRate + bcastRate
        val sb = StringBuilder("<h3>Collision probability (analytic, birthday bound)</h3>")
        sb.append(
            """<p>p ≈ 1 − exp(−m²/2<sup>b+1</sup>) for m random b-bit values. <b>Message IDs</b>: m = messages alive in the whole mesh
            within one message lifetime at N = 1000, at the grid's traffic (each phone sends a chat message every 240 s on average, plus
            one mesh broadcast every 15 s: ${fmt(rate)} messages per second). A collision makes phones that saw the first message drop
            the second as a copy. "Per year" is the chance of at least one collision anywhere in the mesh during a year of this traffic
            (expected collisions ≈ rate · m / 2<sup>b</sup> per second).</p>""",
        )
        sb.append("<table><tr><th class=l>message lifetime</th><th>m (alive)</th>")
        for (b in listOf(8, 12, 16)) sb.append("<th>ID $b B: one lifetime</th><th>ID $b B: per year</th>")
        sb.append("</tr>")
        for ((label, s) in listOf("120 s (minimum)" to 120.0, "300 s (default)" to 300.0, "1 h" to 3600.0, "24 h (maximum)" to 86400.0)) {
            val m = rate * s
            sb.append("<tr><td class=l>$label</td><td>${fmt(m)}</td>")
            for (b in listOf(8, 12, 16)) {
                val perSec = rate * m / 2.0.pow(b * 8)
                val year = oneMinusExp(perSec * 365 * 86400)
                sb.append("<td>${sci(birthday(m, b * 8))}</td><td>${sci(year)}</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</table>")
        sb.append(
            """<p><b>Routing tags</b>: a collision makes two phones share a tag, so one can receive the other's unicast (it cannot
            decrypt it; the true destination misses it). Populations: a phone's passive view (4·d ≤ 24 entries), its route table
            (mean ${fmt(tableMean)} entries in the grid, N = 200), and every tag valid in the whole mesh at N = 1000 (each phone has up
            to 3 valid tags: previous, current, next). "Per day" assumes a fresh draw every 15 min (96 rotations).</p>""",
        )
        sb.append("<table><tr><th class=l>population</th><th>m</th>")
        for (b in listOf(4, 8, 16)) sb.append("<th>tag $b B: one period</th><th>tag $b B: per day</th>")
        sb.append("</tr>")
        for ((label, m) in listOf("passive view" to 24.0, "route table" to tableMean, "whole mesh, N = 1000" to 3000.0)) {
            sb.append("<tr><td class=l>$label</td><td>${fmt(m)}</td>")
            for (b in listOf(4, 8, 16)) {
                val p = birthday(m, b * 8)
                val day = oneMinusExp(96 * m * (m - 1) / 2.0.pow(b * 8 + 1))
                sb.append("<td>${sci(p)}</td><td>${sci(day)}</td>")
            }
            sb.append("</tr>")
        }
        sb.append(
            "</table><p class=note>Random collisions only. A malicious phone can reuse a known ID or tag on purpose at any size; that is " +
                "a threat-model question (bind the ID to the content), not a size question.</p>",
        )
        return sb.toString()
    }

    private val fragVariants =
        StageEPlan.fragmentBytes.flatMap { f ->
            StageEPlan.fragmentCounts.flatMap { k ->
                val base = "M${k}x$f"
                if (k > 1) listOf(base, "$base+CT") else listOf(base)
            }
        }

    private fun fragLabel(v: String): String {
        val m = Regex("M([0-9]+)x([0-9]+)").find(v)!!
        val k = m.groupValues[1].toInt()
        val f = m.groupValues[2].toInt()
        val mtu = f + 3
        val mode = if (v.endsWith("+CT")) "cut-through" else "store, then forward"
        return "$k × $f B (MTU $mtu, ${k * f} B) — ${if (k == 1) "single fragment" else mode}"
    }

    private fun fragSection(all: List<Run>): String {
        val base = all.filter { it.set == CSet.E_BASE.label }
        val sb = StringBuilder("<h2>3. Long messages: reassemble at every hop or pass fragments straight on?</h2>")
        sb.append(
            """<p>A BLE link carries at most one ATT payload per write: 182 B at MTU 185 or 514 B at MTU 517. Longer messages are
            split into fragments. With <b>store, then forward</b> (per-hop reassembly) a relay waits for the whole message, checks
            it, and only then sends it on; each hop adds the full message's air time. With <b>cut-through</b> a relay passes each
            fragment on as soon as it arrives; only the first hop pays the full air time. Every message here is exactly k fragments
            on the wire. Link throughput is the measured 47–79 KB/s (Android dials) or 19–31 KB/s (iPhone dials) at both MTUs (the
            bake-off measured throughput at MTU 517 only; assumed). Cut-through is modeled pessimistically: if a link drops
            mid-message, the partial copy downstream is lost and a later complete copy of the same message is still dropped as a
            duplicate.</p>""",
        )
        val hops = 1..5
        fun tableFor(
            kind: String,
            ref: String,
        ) {
            sb.append("<h3>${if (kind == "UA") "Unicast (v1 UA F+K2)" else "Broadcast (v1 BA CU)"} — pooled over all cells</h3>")
            sb.append("<table><tr><th class=l>message</th><th>delivery</th><th>Δ vs 1 fragment of the same MTU (pp)</th>")
            for (h in hops) sb.append("<th>p50 $h hop${if (h > 1) "s" else ""}</th>")
            for (h in hops) sb.append("<th>p95 $h hop${if (h > 1) "s" else ""}</th>")
            sb.append("<th>lost in flight per message</th></tr>")
            for (v in fragVariants) {
                val key = "$ref+$v"
                val one = "$ref+M1x${v.substringAfter('x').substringBefore('+')}"
                sb.append("<tr><td class=l>${esc(fragLabel(v))}</td><td>${stat(base.map { it.v(key, "delivery") }).fmt(100.0, 2)}%</td>")
                sb.append("<td>${stat(base.map { it.v(key, "delivery") - it.v(one, "delivery") }).fmt(100.0, 2)}</td>")
                for (h in hops) sb.append("<td>${stat(base.map { it.v(key, "latH${h}P50Ms") }).fmt()}</td>")
                for (h in hops) sb.append("<td>${med(base.map { it.v(key, "latH${h}P95Ms") })}</td>")
                sb.append("<td>${stat(base.map { it.v(key, "lostInFlightPerSent") }).fmt()}</td></tr>")
            }
            sb.append("</table>")
        }
        tableFor("UA", V1U)
        tableFor("BA", V1B)
        sb.append("<p class=note>Latency in ms by the number of hops the delivered copy took. p50: mean over cells and seeds of each run's median ± 95% CI; p95: median over runs of each run's p95 (range in brackets), because a few runs have only a handful of long-hop messages (simulated). Broadcast latencies include Plumtree's lazy-push repair (graft after a missing announcement), which dominates its 1-hop p95.</p>")
        val labels = ArrayList<Pair<String, String>>()
        val sf = ArrayList<Pair<Double, Double>?>()
        val ct = ArrayList<Pair<Double, Double>?>()
        for (k in listOf(4, 16)) for (f in listOf(182, 514)) for (h in hops) {
            labels += "$h hop${if (h > 1) "s" else ""}" to "$k×$f B"
            fun p(v: String) = stat(base.map { it.v("$V1U+$v", "latH${h}P50Ms") - it.v("$V1U+M1x$f", "latH${h}P50Ms") }).let { it.mean to it.ci }
            sf += p("M${k}x$f")
            ct += p("M${k}x$f+CT")
        }
        sb.append(
            barChart(
                "Unicast: extra median latency of a k-fragment message", "ms more than a 1-fragment message of the same MTU", "", labels,
                mapOf("store, then forward" to sf, "cut-through" to ct), mapOf("store, then forward" to "#dd6b20", "cut-through" to "#3182ce"),
            ),
        )
        sb.append("<p class=key>Colors:<span style=\"background:#dd6b20\"></span>store, then forward<span style=\"background:#3182ce\"></span>cut-through</p>")
        val per = StringBuilder("<details><summary>Per cell: delivery of 16-fragment messages</summary><table><tr><th class=l>cell</th>")
        val pick = listOf("M1x182", "M16x182", "M16x182+CT", "M1x514", "M16x514", "M16x514+CT")
        for (v in pick) per.append("<th>UA ${esc(v)}</th>")
        for (v in pick) per.append("<th>BA ${esc(v)}</th>")
        per.append("</tr>")
        for (cell in cellOrder) {
            val rs = base.filter { it.cell == cell }
            if (rs.isEmpty()) continue
            per.append("<tr><td class=l>${esc(cellTitle(cell))}</td>")
            for (v in pick) per.append("<td>${stat(rs.map { it.v("$V1U+$v", "delivery") }).fmt(100.0, 2)}</td>")
            for (v in pick) per.append("<td>${stat(rs.map { it.v("$V1B+$v", "delivery") }).fmt(100.0, 2)}</td>")
            per.append("</tr>")
        }
        sb.append(per.append("</table></details>"))
        return sb.toString()
    }

    private fun hsSection(all: List<Run>): String {
        val pq = all.filter { it.set == CSet.E_BASE.label }
        val xx = all.filter { it.set == CSet.E_HS.label }
        val sb = StringBuilder("<h2>4. Post-quantum link handshake under churn</h2>")
        sb.append(
            """<p>Every new link starts with a Noise handshake. v1 uses the post-quantum <b>XXhfs</b> (about 2.5 KB, 101–255 ms
            measured) instead of classical <b>XX</b> (about 200 B, 55–90 ms). Phones that walk around or arrive at a venue make many new
            links, so the handshake cost grows with churn. The extra air time of the larger handshake is modeled on top of the measured
            handshake time, slowed by the same crowd factor as data. Separately, the <b>first message</b> between two phones carries an
            end-to-end Noise IK message: about 96 B classical or about 2.4 KB post-quantum. Half the pairs in each run use each kind.</p>
            <p><b>Handshake share</b>: handshake bytes divided by the capacity of all live links over the same time. <b>Dial to
            usable</b>: from the decision to dial until the authenticated link can carry data (includes retries and the GATT
            set-up of 1.3–3.7 s).</p>""",
        )
        sb.append("<table><tr><th class=l>cell</th><th class=l>link handshake</th><th>handshake bytes / phone·s</th><th>handshake share of link capacity</th>")
        sb.append("<th>new links / phone·min</th><th>dial to usable p50 (s)</th><th>… p95 (s)</th><th>unicast delivery</th><th>broadcast delivery</th>")
        sb.append("<th>first message p95, classical IK (ms)</th><th>first message p95, PQ IK (ms)</th></tr>")
        for (cell in cellOrder) for ((name, rs) in listOf("XX (classical)" to xx, "XXhfs (PQ, v1)" to pq)) {
            val c = rs.filter { it.cell == cell }
            if (c.isEmpty()) continue
            sb.append("<tr><td class=l>${esc(cellTitle(cell))}</td><td class=l>$name</td>")
            sb.append("<td>${stat(c.map { it.t("handshakeBytesPerNodeS") }).fmt()}</td><td>${stat(c.map { it.t("handshakeShareOfCapacity") }).fmt(100.0, 3)}%</td>")
            sb.append("<td>${stat(c.map { it.t("linkUpsPerNodeMin") }).fmt()}</td>")
            sb.append("<td>${stat(c.map { it.t("dialToUsableP50S") }).fmt()}</td><td>${stat(c.map { it.t("dialToUsableP95S") }).fmt()}</td>")
            sb.append("<td>${stat(c.map { it.v(V1U, "delivery") }).fmt(100.0, 2)}%</td><td>${stat(c.map { it.v(V1B, "delivery") }).fmt(100.0, 2)}%</td>")
            sb.append("<td>${stat(c.map { it.v(V1U, "firstP95ClassicalMs") }).fmt()}</td><td>${stat(c.map { it.v(V1U, "firstP95PqMs") }).fmt()}</td></tr>")
        }
        sb.append("</table>")
        sb.append(
            """<h3>First message by hops and by density class, against the budget</h3>
            <p>Budget (accepted design): first-message p95 ≤ 1 s classical, ≤ 1.5 s post-quantum, within ≤ 3 hops. "Within budget" is
            the share of all first messages (pooled over seeds) that arrived within 1 s (classical IK) or 1.5 s (PQ IK); the p95
            columns are the median over seeds of each run's p95 (range in brackets; a subgroup of a run can hold only a few messages,
            so one message caught by a 60 s iPhone background gap can set a run's p95). v1 unicast (UA F+K2), links with the PQ XXhfs handshake unless noted.</p>""",
        )
        sb.append("<table><tr><th class=l>cell</th><th class=l>link handshake</th>")
        for (h in 1..3) sb.append("<th>$h hop${if (h > 1) "s" else ""}: classical p95</th><th>PQ p95</th>")
        for (k in listOf("low", "medium", "high")) sb.append("<th>$k sender: classical p95</th><th>PQ p95</th>")
        sb.append("<th>within budget (classical / PQ)</th></tr>")
        for (cell in cellOrder) for ((name, rs) in listOf("XX" to xx, "XXhfs" to pq)) {
            val c = rs.filter { it.cell == cell }
            if (c.isEmpty()) continue
            sb.append("<tr><td class=l>${esc(cellTitle(cell))}</td><td class=l>$name</td>")
            fun p95(m: String): String {
                val xs = c.map { it.v(V1U, m) }
                val s = medValue(xs)
                val bad = (m.startsWith("firstC") && s > 1000) || (m.startsWith("firstP") && s > 1500)
                return "<td${if (bad) " class=worse" else ""}>${med(xs)}</td>"
            }
            for (h in 1..3) sb.append(p95("firstCH${h}P95Ms")).append(p95("firstPH${h}P95Ms"))
            for (k in listOf("low", "medium", "high")) sb.append(p95("firstC_${k}P95Ms")).append(p95("firstP_${k}P95Ms"))
            fun share(kind: String): String {
                val ok = c.sumOf { r -> (1..3).sumOf { h -> r.v(V1U, "first${kind}H${h}Ok").let { if (it.isNaN()) 0.0 else it } } }
                val n = c.sumOf { r -> (1..3).sumOf { h -> r.v(V1U, "first${kind}H${h}N").let { if (it.isNaN()) 0.0 else it } } }
                return if (n == 0.0) "–" else pct(ok / n)
            }
            sb.append("<td>${share("C")} / ${share("P")}</td></tr>")
        }
        sb.append("</table><p class=note>Senders' density class is their true local density at the moment of sending. Cells with no sender in a class show –. All values simulated.</p>")
        return sb.toString()
    }

    fun render(
        rows: List<Map<String, String>>,
        notes: String,
    ): String {
        val all = runs(rows)
        val tableMean = stat(all.filter { it.set == CSet.E_BASE.label }.map { it.v(V1U, "tableMean") }).mean
        val sb = StringBuilder(HtmlReport.head("MeshLink routing simulation — stage E: security costs"))
        sb.append(
            """<h1>MeshLink routing simulation — stage E: what do the security features cost?</h1>
            <p class="warn"><b>Every number on this page is simulated.</b> It comes from a simplified computer model of phones and
            Bluetooth links, calibrated against lab measurements (bearer bake-off, 2026-11). It is not a measurement on real devices.</p>
            <p>Stages A–D chose the v1 routing configuration. Stage E measures what its privacy and security features cost in delivery,
            delay and bytes: (1) how often phones change their identifiers, (2) how long the routing tag and message ID must be,
            (3) how relays handle messages longer than one Bluetooth packet, and (4) the post-quantum link handshake when links come and
            go often. Baseline: final v1 (stage D v1 with iPhones keeping at most 3 incoming links), bake-off calibration. Cells: 200
            phones at low, medium and high density standing still, medium density walking, and the arrival scenario (a crowd converging
            on a venue over 10 min); each in an Android-only and a half-iPhone mix; 5 seeds each.</p>
            $notes""",
        )
        sb.append(rotationSection(all))
        sb.append(sizeSection(all, collisionHtml(tableMean)))
        sb.append(fragSection(all))
        sb.append(hsSection(all))
        sb.append(
            """<h2>Words used here</h2><ul>
            <li><b>UA F+K2</b>: v1 unicast (flood in the low class, route-or-short-flood in medium and high, whole-mesh route search,
            delivery ack refreshes the route). <b>BA CU</b>: v1 broadcast (flood in low, Plumtree tree in medium and high, catch-up on
            link-up). <b>U1</b>, <b>B1</b>: flooding references.</li>
            <li><b>pp</b>: percentage points. <b>p95</b>: 95% of delivered messages arrive within this time. <b>± x</b>: 95% confidence
            interval over seeds.</li></ul></main></body></html>
""",
        )
        return sb.toString()
    }
}

fun stageEReport(out: File) {
    val rows = listOf(CSet.E_BASE, CSet.E_ROT, CSet.E_HS).flatMap { s -> CRaw.file(out, s).let { if (it.exists()) RawCsv.read(it) else emptyList() } }
    val notes = File(out, "findings.html").takeIf { it.exists() }?.readText() ?: ""
    File(out, "stage-e-report.html").writeText(StageEReport.render(rows, notes))
    println("wrote ${File(out, "stage-e-report.html")}")
}
