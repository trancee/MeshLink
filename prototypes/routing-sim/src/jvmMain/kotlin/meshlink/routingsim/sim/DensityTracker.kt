package meshlink.routingsim.sim

import meshlink.routingsim.density.ClassSettings
import meshlink.routingsim.density.ClassifierConfig
import meshlink.routingsim.density.DensityClass
import meshlink.routingsim.density.DensityClassifier
import meshlink.routingsim.density.DensityEstimator
import meshlink.routingsim.density.EstimatorConfig
import kotlin.math.abs

/**
 * Stage C local density: which estimator and classifier drive each phone's behavior, and which
 * per-class settings the topology applies ([settings] = null keeps the topology fixed).
 */
class DensitySetup(
    val estimators: List<EstimatorConfig> = DEFAULT_ESTIMATORS,
    val classifiers: List<ClassifierConfig> = defaultClassifiers(),
    val driveEstimator: Int = estimators.indexOfFirst { it.label == "w10e" },
    val driveClassifier: ClassifierConfig = classifiers.first { it.label == "s1h25/d60" },
    val settings: ClassSettings? = null,
    /** Advertisement identifier rotation period (same as the routing tag epoch). */
    val advertRotationMs: Long = 900_000,
    /** Hear probability per scan for a slow advertiser (fast advertisers use the calibrated 0.8). */
    val slowAdvertisingHear: Double = 0.5,
) {
    companion object {
        val DEFAULT_ESTIMATORS =
            listOf(
                EstimatorConfig("w10", 10_000, 1.0),
                EstimatorConfig("w10e", 10_000, 0.3),
                EstimatorConfig("w20e", 20_000, 0.2),
                EstimatorConfig("w60e", 60_000, 0.2),
            )

        /**
         * Class bounds 10 and 50 multiplied by [scale] (compensates the estimator's over-count) with a
         * hysteresis band: switch up at bound·scale·(1+h), down at bound·scale/(1+h).
         */
        fun band(
            scale: Double,
            h: Double,
            dwellMs: Long,
        ): ClassifierConfig {
            val name = "s${fmt(scale)}h${(h * 100).toInt()}/d${dwellMs / 1000}"
            return ClassifierConfig(name, 10 * scale * (1 + h), 50 * scale * (1 + h), 10 * scale / (1 + h), 50 * scale / (1 + h), dwellMs)
        }

        private fun fmt(v: Double) = if (v == Math.floor(v)) v.toInt().toString() else v.toString()

        fun defaultClassifiers(): List<ClassifierConfig> =
            listOf(30_000L, 60_000L).flatMap { dwell ->
                listOf(1.0, 1.3).flatMap { scale -> listOf(0.0, 0.25, 0.5).map { h -> band(scale, h, dwell) } }
            }

        fun parseDrive(text: String): Pair<String, ClassifierConfig> {
            val (est, rest) = text.split(':')
            val m = Regex("s([0-9.]+)h([0-9]+)/d([0-9]+)").matchEntire(rest) ?: error("bad classifier $rest")
            return est to band(m.groupValues[1].toDouble(), m.groupValues[2].toDouble() / 100, m.groupValues[3].toLong() * 1000)
        }
    }
}

/** Per-node estimators and classifiers, ground-truth comparison and metrics. */
internal class DensityTracker(
    private val setup: DensitySetup,
    private val n: Int,
    private val phase: LongArray,
) {
    private val ne = setup.estimators.size
    private val nc = setup.classifiers.size
    private val est = Array(n) { Array(ne) { DensityEstimator(setup.estimators[it]) } }

    /** Driving estimator with identifiers rotating at one shared boundary (worst case for inflation). */
    private val aligned = Array(n) { DensityEstimator(setup.estimators[setup.driveEstimator]) }
    private val cls = Array(n) { Array(ne * nc) { DensityClassifier(setup.classifiers[it % nc]) } }
    val drive = Array(n) { DensityClassifier(setup.driveClassifier) }
    private val alignedCls = Array(n) { DensityClassifier(setup.driveClassifier) }
    private var alignedSwitches = 0L

    // Ground truth: raw class now, and the "settled" class (raw class held for 10 s), with its entry time.
    private val rawCls = IntArray(n) { -1 }
    private val rawSince = LongArray(n)
    private val settled = IntArray(n) { -1 }
    private val settledSince = LongArray(n)
    private val pendingSince = Array(n) { LongArray(ne * nc + 1) { -1 } }

    // Metrics.
    private val ratioSum = DoubleArray(ne + 1)
    private val absErrSum = DoubleArray(ne + 1)
    private var ratioN = 0L
    private val agree = LongArray(ne * nc + 1)
    private var samples = 0L
    private val switches = LongArray(ne * nc + 1)
    private val lags = Array(ne * nc + 1) { ArrayList<Double>() }
    private val misses = LongArray(ne * nc + 1)
    private var powerSum = 0.0
    private val truthShare = LongArray(3)
    private var truthSwitches = 0L
    private val driveShare = LongArray(3)

    fun advertId(
        peer: Int,
        nowMs: Long,
    ): Long = (peer.toLong() shl 24) or Math.floorDiv(nowMs + phase[peer], setup.advertRotationMs)

    private fun alignedId(
        peer: Int,
        nowMs: Long,
    ): Long = (peer.toLong() shl 24) or Math.floorDiv(nowMs, setup.advertRotationMs)

    fun reset(i: Int) {
        for (e in est[i]) e.reset()
        aligned[i].reset()
        alignedCls[i].reset()
        for (c in cls[i]) c.reset()
        drive[i].reset()
        rawCls[i] = -1
        settled[i] = -1
        pendingSince[i].fill(-1)
    }

    /** Driving class, LOW until the phone's first scan. */
    fun classOf(i: Int) = maxOf(0, drive[i].current)

    /** Driving estimator's smoothed count (−1 before the first scan). */
    fun estimateOf(i: Int) = est[i][setup.driveEstimator].value

    /** Feeds one scan; returns true when the driving class changed. */
    fun onScan(
        i: Int,
        nowMs: Long,
        heard: List<meshlink.routingsim.strategy.ScanResult>,
        linked: IntArray,
        measuring: Boolean,
    ): Boolean {
        for (r in heard) {
            val id = advertId(r.peer, nowMs)
            for (e in est[i]) e.heard(nowMs, id)
            aligned[i].heard(nowMs, alignedId(r.peer, nowMs))
        }
        val ids = LongArray(linked.size) { advertId(linked[it], nowMs) }
        val alignedIds = LongArray(linked.size) { alignedId(linked[it], nowMs) }
        var changed = false
        for (e in 0 until ne) {
            val v = est[i][e].update(nowMs, ids)
            for (c in 0 until nc) {
                val k = cls[i][e * nc + c]
                val before = k.switches
                k.update(nowMs, v)
                if (measuring && k.switches > before) switches[e * nc + c]++
            }
            if (e == setup.driveEstimator) {
                val before = drive[i].switches
                changed = drive[i].update(nowMs, v)
                if (changed && measuring && drive[i].switches > before) switches[ne * nc]++
            }
        }
        val before = alignedCls[i].switches
        alignedCls[i].update(nowMs, aligned[i].update(nowMs, alignedIds))
        if (measuring && alignedCls[i].switches > before) alignedSwitches++
        return changed
    }

    /** Called every tick for an awake honest node: compares with the true peers in range. */
    fun sample(
        i: Int,
        nowMs: Long,
        truth: Int,
        measuring: Boolean,
        power: Double,
    ) {
        val raw = DensityClass.of(truth.toDouble())
        if (raw != rawCls[i]) {
            rawCls[i] = raw
            rawSince[i] = nowMs
        }
        if (settled[i] < 0 && drive[i].current >= 0) {
            settled[i] = raw
            settledSince[i] = nowMs
        } else if (settled[i] >= 0 && raw != settled[i] && nowMs - rawSince[i] >= SETTLE_MS) {
            settled[i] = raw
            settledSince[i] = rawSince[i]
            if (measuring) truthSwitches++
            for (k in 0..ne * nc) {
                if (pendingSince[i][k] >= 0 && measuring) misses[k]++
                pendingSince[i][k] = if (current(i, k) == raw) -1 else rawSince[i]
                if (pendingSince[i][k] < 0 && measuring) lags[k].add(0.0)
            }
        }
        if (settled[i] >= 0) {
            for (k in 0..ne * nc) {
                if (pendingSince[i][k] >= 0 && current(i, k) == settled[i]) {
                    if (measuring) lags[k].add((nowMs - pendingSince[i][k]).toDouble())
                    pendingSince[i][k] = -1
                }
            }
        }
        if (!measuring || drive[i].current < 0) return
        samples++
        powerSum += power
        truthShare[raw]++
        driveShare[drive[i].current]++
        for (k in 0..ne * nc) if (current(i, k) == raw) agree[k]++
        if (truth > 0) {
            ratioN++
            for (e in 0 until ne) {
                val v = est[i][e].value
                ratioSum[e] += v / truth
                absErrSum[e] += abs(v - truth) / truth
            }
            ratioSum[ne] += aligned[i].value / truth
            absErrSum[ne] += abs(aligned[i].value - truth) / truth
        }
    }

    private fun current(
        i: Int,
        k: Int,
    ) = if (k == ne * nc) drive[i].current else cls[i][k].current

    fun results(
        out: LinkedHashMap<String, Double>,
        tickMs: Long,
    ) {
        val s = samples.coerceAtLeast(1).toDouble()
        val nodeHours = samples * tickMs / 3_600_000.0
        for (e in 0..ne) {
            val name = if (e < ne) setup.estimators[e].label else setup.estimators[setup.driveEstimator].label + "-aligned"
            out["est_${name}_ratio"] = if (ratioN == 0L) Double.NaN else ratioSum[e] / ratioN
            out["est_${name}_mape"] = if (ratioN == 0L) Double.NaN else absErrSum[e] / ratioN
        }
        for (k in 0..ne * nc) {
            val name = if (k == ne * nc) "drive" else "${setup.estimators[k / nc].label}/${setup.classifiers[k % nc].label}"
            out["cls_${name}_agree"] = agree[k] / s
            out["cls_${name}_switchesPerHour"] = if (nodeHours <= 0) Double.NaN else switches[k] / nodeHours
            out["cls_${name}_lagP50S"] = percentile(lags[k], 0.5) / 1000
            out["cls_${name}_lagP90S"] = percentile(lags[k], 0.9) / 1000
            out["cls_${name}_lagN"] = lags[k].size.toDouble()
            out["cls_${name}_missed"] = misses[k].toDouble()
        }
        out["alignedDriveSwitchesPerHour"] = if (nodeHours <= 0) Double.NaN else alignedSwitches / nodeHours
        out["truthSwitchesPerHour"] = if (nodeHours <= 0) Double.NaN else truthSwitches / nodeHours
        out["powerIndex"] = powerSum / s
        for (c in 0 until 3) {
            out["truthShare_${DensityClass.labels[c]}"] = truthShare[c] / s
            out["driveShare_${DensityClass.labels[c]}"] = driveShare[c] / s
        }
    }

    companion object {
        const val SETTLE_MS = 10_000L
    }
}
