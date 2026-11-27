package ch.trancee.meshlink.internal

import java.io.File
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The scheduled density suite (ADR 0017 decision 11): each cell, pooled over its seeds, must meet the routing
 * simulation's delivery and airtime bands. Runs only through `./gradlew :meshlink-testing:densityTest`;
 * `-Pdensity.cells=n200-high` and `-Pdensity.seeds=1,2` narrow it. A cell's seeds share nothing, so they run
 * on parallel threads. Every seed also writes `<cell>-seed<N>.run`; `-Pdensity.pool=DIR` runs nothing and
 * instead pools every such file under DIR, so CI can run each seed in its own job. `-Pdensity.measureOnly`
 * writes the reports but leaves the bands to the pooling run.
 */
class DensityCellTest {
    @Test
    fun everyCellMeetsTheRoutingBands() {
        val out = File(System.getProperty("density.out") ?: "build/density").apply { mkdirs() }
        ProtoRetry.rule = System.getProperty("proto.rule") ?: "d61"
        ProtoRetry.sink = { key, n -> checkNotNull(protoMaps.get()).merge(key, n.toLong(), Long::plus) }
        val pool = System.getProperty("density.pool")
        val runs = if (pool == null) runCells(out) else pooled(File(pool))
        check(runs.isNotEmpty()) { "no seed runs to pool under $pool" }
        val misses =
            runs.map { (cell, seedRuns) ->
                val report = CellReport(cell, seedRuns.sortedBy { it.seed })
                File(out, "${cell.label}.json").writeText(report.json())
                File(out, "${cell.label}.md").writeText(report.markdown())
                report.misses
            }
        if (System.getProperty("density.measureOnly") == null) {
            assertTrue(misses.flatten().isEmpty(), misses.flatten().joinToString("\n"))
        }
    }

    private fun runCells(out: File): List<Pair<Cell, List<SeedRun>>> {
        val cells = System.getProperty("density.cells")?.split(',')?.map(Cell::labeled) ?: Cell.entries
        val seeds = System.getProperty("density.seeds")?.split(',')?.map { it.trim().toLong() } ?: DEFAULT_SEEDS
        return cells.map { cell ->
            cell to
                inParallel(seeds) { seed ->
                    runCell(cell, seed).also {
                        File(out, "${cell.label}-seed$seed.run").writeText(it.encode(cell))
                        File(out, "${cell.label}-seed$seed.counts").writeText("rule=${ProtoRetry.rule}\n$protoCounts\n")
                    }
                }
        }
    }

    private fun pooled(dir: File): List<Pair<Cell, List<SeedRun>>> =
        dir
            .walk()
            .filter { it.isFile && it.name.endsWith(".run") }
            .map { SeedRun.decode(it.readText()) }
            .groupBy({ it.first }, { it.second })
            .toList()
            .sortedBy { it.first.ordinal }

    private fun <T, R> inParallel(
        items: List<T>,
        block: (T) -> R,
    ): List<R> {
        val pool = Executors.newFixedThreadPool(minOf(items.size, Runtime.getRuntime().availableProcessors()))
        try {
            return items.map { pool.submit<R> { block(it) } }.map { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        val DEFAULT_SEEDS = listOf(1L, 2L, 3L, 4L, 5L)
    }
}

/** One line of `key=value` fields; tallies list recipients, delivered and duplicates per class. */
internal fun SeedRun.encode(cell: Cell): String {
    fun tally(t: Tally) = listOf(t.recipients, t.delivered, t.duplicates).joinToString("/") { it.joinToString(",") }
    return "cell=${cell.label} seed=$seed wallMillis=$wallMillis rejected=$rejected idle=$idleControlShare " +
        "busiest=$busiestBytesPerSecond unicast=${tally(unicast)} broadcast=${tally(broadcast)}\n"
}

/** Reads a line [encode] wrote. */
internal fun SeedRun.Companion.decode(text: String): Pair<Cell, SeedRun> {
    val fields = text.trim().split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }

    fun tally(value: String): Tally {
        val (recipients, delivered, duplicates) = value.split('/').map { part -> part.split(',').map(String::toInt) }
        return Tally().apply {
            recipients.forEachIndexed { i, n -> this.recipients[i] = n }
            delivered.forEachIndexed { i, n -> this.delivered[i] = n }
            duplicates.forEachIndexed { i, n -> this.duplicates[i] = n }
        }
    }
    return Cell.labeled(fields.getValue("cell")) to
        SeedRun(
            seed = fields.getValue("seed").toLong(),
            wallMillis = fields.getValue("wallMillis").toLong(),
            unicast = tally(fields.getValue("unicast")),
            broadcast = tally(fields.getValue("broadcast")),
            rejected = fields.getValue("rejected").toInt(),
            idleControlShare = fields.getValue("idle").toDouble(),
            busiestBytesPerSecond = fields.getValue("busiest").toDouble(),
        )
}

/** A cell's runs pooled over seeds, and the bands they miss. */
internal class CellReport(
    private val cell: Cell,
    private val runs: List<SeedRun>,
) {
    private val unicast = Tally().apply { runs.forEach { this += it.unicast } }
    private val broadcast = Tally().apply { runs.forEach { this += it.broadcast } }
    private val idle = runs.maxOf { it.idleControlShare }
    private val busiest = runs.maxOf { it.busiestBytesPerSecond }

    /** Each band the cell misses; a delivery band applies only to a class of at least [MIN_SAMPLE] recipients. */
    val misses: List<String> =
        buildList {
            listOf("unicast" to unicast, "broadcast" to broadcast).forEach { (kind, tally) ->
                SenderDensity.entries.forEach { density ->
                    val n = tally.recipients[density.ordinal]
                    val share = tally.delivered[density.ordinal].toDouble() / n
                    if (n >= MIN_SAMPLE &&
                        share < density.band
                    ) {
                        add("${cell.label} $kind $density delivery $share < ${density.band} (n = $n)")
                    }
                }
            }
            if (idle > IDLE_CONTROL_MAX) add("${cell.label} idle control $idle > $IDLE_CONTROL_MAX of link capacity")
            if (busiest > BUSIEST_MAX_BYTES_PER_SECOND) {
                add("${cell.label} busiest node $busiest B/s > $BUSIEST_MAX_BYTES_PER_SECOND")
            }
        }

    fun json(): String {
        fun tally(t: Tally) =
            SenderDensity.entries.joinToString(", ", "{", "}") { d ->
                val i = d.ordinal
                "\"${d.name.lowercase()}\": {\"n\": ${t.recipients[i]}, \"delivered\": ${t.delivered[i]}, " +
                    "\"duplicates\": ${t.duplicates[i]}, \"band\": ${d.band}, \"applies\": ${t.recipients[i] >= MIN_SAMPLE}}"
            }
        val seeds =
            runs.joinToString(", ", "[", "]") {
                "{\"seed\": ${it.seed}, \"wallMillis\": ${it.wallMillis}, \"rejected\": ${it.rejected}, " +
                    "\"idleControlShare\": ${it.idleControlShare}, \"busiestBytesPerSecond\": ${it.busiestBytesPerSecond}}"
            }
        val missList = misses.joinToString(", ", "[", "]") { "\"$it\"" }
        return "{\"cell\": \"${cell.label}\", \"nodes\": ${cell.nodes}, \"meanPeers\": ${cell.meanPeers}, " +
            "\"unicast\": ${tally(unicast)}, \"broadcast\": ${tally(broadcast)}, \"idleControlShare\": $idle, " +
            "\"busiestBytesPerSecond\": $busiest, \"seeds\": $seeds, \"misses\": $missList}\n"
    }

    /** One table row: delivery as delivered/n per class, then idle share, busiest node and verdict. */
    fun markdown(): String {
        fun cells(t: Tally) =
            SenderDensity.entries.joinToString(" | ") { d ->
                val n = t.recipients[d.ordinal]
                if (n == 0) "–" else "${t.delivered[d.ordinal]}/$n"
            }
        val verdict = if (misses.isEmpty()) "pass" else "**fail**"
        return "| ${cell.label} | ${cells(unicast)} | ${cells(broadcast)} | ${"%.4f".format(idle * PERCENT)}% | " +
            "${busiest.toInt()} | ${runs.sumOf { it.wallMillis } / MILLIS} s | $verdict |\n"
    }

    private companion object {
        const val MIN_SAMPLE = 100
        const val IDLE_CONTROL_MAX = 0.10
        const val BUSIEST_MAX_BYTES_PER_SECOND = 4_700.0
        const val PERCENT = 100
        const val MILLIS = 1_000
    }
}
