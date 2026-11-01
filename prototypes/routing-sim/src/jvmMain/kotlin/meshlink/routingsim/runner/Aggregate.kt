package meshlink.routingsim.runner

import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/** One aggregated cell × strategy: mean and 95% confidence half-width per metric. */
class AggregateRow(
    val key: Map<String, String>,
    val n: Int,
    val mean: Map<String, Double>,
    val ci: Map<String, Double>,
) {
    fun m(metric: String) = mean[metric] ?: Double.NaN

    fun c(metric: String) = ci[metric] ?: Double.NaN
}

object Aggregate {
    val groupColumns = listOf("density", "nodes", "mobility", "platform", "degree", "sybilShare", "dropMeanS", "strategy")
    private val nonMetric = setOf("runId", "seed", "extentM") + groupColumns

    /** Two-sided 97.5% Student t quantiles for df = 1..30; df > 30 uses 1.96. */
    private val t975 =
        doubleArrayOf(
            12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228, 2.201, 2.179, 2.160, 2.145,
            2.131, 2.120, 2.110, 2.101, 2.093, 2.086, 2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048,
            2.045, 2.042,
        )

    fun tQuantile(df: Int) = if (df < 1) Double.NaN else if (df <= 30) t975[df - 1] else 1.96

    fun aggregate(raw: List<Map<String, String>>): List<AggregateRow> {
        if (raw.isEmpty()) return emptyList()
        val metrics = raw.first().keys.filter { it !in nonMetric }
        return raw
            .groupBy { r -> groupColumns.associateWith { r.getValue(it) } }
            .map { (key, rows) ->
                val mean = LinkedHashMap<String, Double>()
                val ci = LinkedHashMap<String, Double>()
                for (metric in metrics) {
                    val xs = rows.mapNotNull { it[metric]?.toDoubleOrNull() }.filter { !it.isNaN() }
                    if (xs.isEmpty()) {
                        mean[metric] = Double.NaN
                        ci[metric] = Double.NaN
                        continue
                    }
                    val mu = xs.average()
                    mean[metric] = mu
                    ci[metric] =
                        if (xs.size < 2) {
                            Double.NaN
                        } else {
                            val sd = sqrt(xs.sumOf { (it - mu) * (it - mu) } / (xs.size - 1))
                            tQuantile(xs.size - 1) * sd / sqrt(xs.size.toDouble())
                        }
                }
                AggregateRow(key, rows.size, mean, ci)
            }.sortedWith(
                compareBy<AggregateRow>(
                    { it.key["sybilShare"]!!.toDouble() },
                    { it.key["density"] },
                    { it.key["nodes"]!!.toInt() },
                    { it.key["mobility"] },
                    { it.key["platform"] },
                    { it.key["degree"]!!.toInt() },
                    { it.key["dropMeanS"] },
                    { it.key["strategy"] },
                ),
            )
    }

    fun write(
        rows: List<AggregateRow>,
        file: File,
    ) {
        if (rows.isEmpty()) return
        val metrics = rows.first().mean.keys
        val header = groupColumns + "runs" + metrics.flatMap { listOf("${it}_mean", "${it}_ci95") }
        val sb = StringBuilder(header.joinToString(",")).append('\n')
        for (r in rows) {
            val vals = groupColumns.map { r.key.getValue(it) } + r.n.toString() +
                metrics.flatMap { listOf(f(r.m(it)), f(r.c(it))) }
            sb.append(vals.joinToString(",")).append('\n')
        }
        file.writeText(sb.toString())
    }

    private fun f(v: Double) = if (v.isNaN()) "NaN" else String.format(Locale.ROOT, "%.6g", v)
}
