package meshlink.routingsim.density

/** Local density classes (provisional bounds: low < 10 ≤ medium < 50 ≤ high peers in radio range). */
object DensityClass {
    const val LOW = 0
    const val MEDIUM = 1
    const val HIGH = 2
    val labels = listOf("low", "medium", "high")

    fun of(
        peers: Double,
        mediumFrom: Double = 10.0,
        highFrom: Double = 50.0,
    ) = if (peers >= highFrom) HIGH else if (peers >= mediumFrom) MEDIUM else LOW
}

/**
 * Estimator variant: distinct advertisement identifiers heard within [windowMs], plus linked peers
 * whose current identifier was not heard, smoothed by an exponentially weighted moving average
 * with weight [alpha] per scan (1.0 = no smoothing).
 */
data class EstimatorConfig(
    val label: String,
    val windowMs: Long,
    val alpha: Double,
)

/**
 * A phone's own density estimate, from its own scans and link table only (no mesh coordination).
 *
 * Advertisement identifiers rotate, so a peer that rotates inside the window counts twice until its
 * old identifier ages out. Linked peers send their next identifier over the link, so the phone
 * matches linked peers to their current identifier (no double count for them).
 */
class DensityEstimator(
    val config: EstimatorConfig,
) {
    private val lastHeard = HashMap<Long, Long>()
    private var smoothed = -1.0

    val value get() = smoothed

    fun heard(
        nowMs: Long,
        advertId: Long,
    ) {
        lastHeard[advertId] = nowMs
    }

    /** Ages out old identifiers and folds the current raw count into the estimate. */
    fun update(
        nowMs: Long,
        linkedAdvertIds: LongArray,
    ): Double {
        val cutoff = nowMs - config.windowMs
        lastHeard.entries.removeAll { it.value < cutoff }
        var raw = lastHeard.size
        for (id in linkedAdvertIds) if (!lastHeard.containsKey(id)) raw++
        smoothed = if (smoothed < 0) raw.toDouble() else smoothed + config.alpha * (raw - smoothed)
        return smoothed
    }

    fun reset() {
        lastHeard.clear()
        smoothed = -1.0
    }
}

/**
 * Class switching with hysteresis and dwell. Moves up when the estimate reaches the up threshold of
 * the next class, down when it falls below the down threshold, and never sooner than [ClassifierConfig.dwellMs]
 * after the previous switch.
 */
data class ClassifierConfig(
    val label: String,
    val upToMedium: Double,
    val upToHigh: Double,
    val downToLow: Double,
    val downToMedium: Double,
    val dwellMs: Long,
)

class DensityClassifier(
    val config: ClassifierConfig,
) {
    var current = -1
        private set
    private var since = 0L
    var switches = 0
        private set

    /** Returns true when the class changed. */
    fun update(
        nowMs: Long,
        estimate: Double,
    ): Boolean {
        val c = config
        if (current < 0) {
            current = if (estimate >= c.upToHigh) DensityClass.HIGH else if (estimate >= c.upToMedium) DensityClass.MEDIUM else DensityClass.LOW
            since = nowMs
            return true
        }
        if (nowMs - since < c.dwellMs) return false
        var target = current
        if (target == DensityClass.LOW && estimate >= c.upToMedium) target = DensityClass.MEDIUM
        if (target == DensityClass.MEDIUM && estimate >= c.upToHigh) target = DensityClass.HIGH
        if (target == current) {
            if (target == DensityClass.HIGH && estimate < c.downToMedium) target = DensityClass.MEDIUM
            if (target == DensityClass.MEDIUM && estimate < c.downToLow) target = DensityClass.LOW
        }
        if (target == current) return false
        current = target
        since = nowMs
        switches++
        return true
    }

    fun reset() {
        current = -1
        since = 0L
    }
}

/** Per-class settings a phone applies to its own behavior; index by [DensityClass]. */
class ClassSettings(
    val label: String,
    /** Degree budget d. */
    val degree: IntArray,
    /** Scan on every n-th 2 s tick. */
    val scanEvery: IntArray,
    /** Fast (true) or slow (false) advertising. */
    val fastAdvertising: BooleanArray,
    /** Bridge rate limit; negative disables the bridge preference. */
    val bridgeGapMs: LongArray,
)
