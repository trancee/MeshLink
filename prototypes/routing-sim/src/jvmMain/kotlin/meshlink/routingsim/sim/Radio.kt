package meshlink.routingsim.sim

import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Log-distance path loss with per-pair static log-normal shadowing (deterministic from the seed).
 * Keeps, per node, the list of peers whose mean RSSI is at or above receiver sensitivity.
 */
class Radio(
    private val cal: Calibration,
    val n: Int,
    private val shadowSeed: Long,
    private val boosted: BooleanArray,
) {
    val xs = DoubleArray(n)
    val ys = DoubleArray(n)
    val nbr = Array(n) { IntArray(16) }
    val nbrRssi = Array(n) { DoubleArray(16) }
    val nbrCount = IntArray(n)

    private val anyBoost = boosted.any { it }
    val maxRangeM: Double =
        10.0.pow(
            (cal.txPowerDbm + (if (anyBoost) cal.sybilBoostDb else 0.0) - cal.pathLossAt1mDb - cal.sensitivityDbm +
                3 * cal.shadowingSigmaDb) / (10 * cal.pathLossExponent),
        )

    /** Approximately N(0, σ²) from four uniforms of a pair hash (Irwin–Hall). */
    fun shadowDb(
        i: Int,
        j: Int,
    ): Double {
        val a = if (i < j) i else j
        val b = if (i < j) j else i
        val h = mix64(shadowSeed xor (a.toLong() shl 32) xor b.toLong())
        var s = 0.0
        for (k in 0 until 4) s += ((h ushr (16 * k)) and 0xFFFF).toDouble() / 65535.0
        return (s - 2.0) * sqrt(3.0) * cal.shadowingSigmaDb
    }

    private fun rssiFromDist2(
        i: Int,
        j: Int,
        dist2: Double,
    ): Double {
        val boost = if (boosted[i] || boosted[j]) cal.sybilBoostDb else 0.0
        return cal.txPowerDbm + boost - cal.pathLossAt1mDb - 5 * cal.pathLossExponent * log10(max(dist2, 1.0)) +
            shadowDb(i, j)
    }

    fun meanRssi(
        i: Int,
        j: Int,
    ): Double {
        val dx = xs[i] - xs[j]
        val dy = ys[i] - ys[j]
        return rssiFromDist2(i, j, dx * dx + dy * dy)
    }

    private var cellStart = IntArray(0)
    private var cellItems = IntArray(n)
    private val cellOf = IntArray(n)

    fun rebuild() {
        nbrCount.fill(0)
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (i in 0 until n) {
            minX = minOf(minX, xs[i])
            minY = minOf(minY, ys[i])
            maxX = maxOf(maxX, xs[i])
            maxY = maxOf(maxY, ys[i])
        }
        val cell = maxRangeM
        val ncx = ceil((maxX - minX) / cell).toInt() + 1
        val ncy = ceil((maxY - minY) / cell).toInt() + 1
        val cells = ncx * ncy
        if (cellStart.size < cells + 1) cellStart = IntArray(cells + 1)
        cellStart.fill(0, 0, cells + 1)
        for (i in 0 until n) {
            val c = ((xs[i] - minX) / cell).toInt() + ((ys[i] - minY) / cell).toInt() * ncx
            cellOf[i] = c
            cellStart[c + 1]++
        }
        for (c in 0 until cells) cellStart[c + 1] += cellStart[c]
        val fill = cellStart.copyOf(cells + 1)
        for (i in 0 until n) cellItems[fill[cellOf[i]]++] = i
        val max2 = maxRangeM * maxRangeM
        for (i in 0 until n) {
            val cx = ((xs[i] - minX) / cell).toInt()
            val cy = ((ys[i] - minY) / cell).toInt()
            for (gy in cy - 1..cy + 1) {
                if (gy < 0 || gy >= ncy) continue
                for (gx in cx - 1..cx + 1) {
                    if (gx < 0 || gx >= ncx) continue
                    val c = gx + gy * ncx
                    for (k in cellStart[c] until cellStart[c + 1]) {
                        val j = cellItems[k]
                        if (j <= i) continue
                        val dx = xs[i] - xs[j]
                        val dy = ys[i] - ys[j]
                        val d2 = dx * dx + dy * dy
                        if (d2 > max2) continue
                        val r = rssiFromDist2(i, j, d2)
                        if (r >= cal.sensitivityDbm) {
                            add(i, j, r)
                            add(j, i, r)
                        }
                    }
                }
            }
        }
    }

    private fun add(
        i: Int,
        j: Int,
        r: Double,
    ) {
        val c = nbrCount[i]
        if (c == nbr[i].size) {
            nbr[i] = nbr[i].copyOf(c * 2)
            nbrRssi[i] = nbrRssi[i].copyOf(c * 2)
        }
        nbr[i][c] = j
        nbrRssi[i][c] = r
        nbrCount[i] = c + 1
    }
}
