package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId

/** A node's local density class (ADR 0008 decision 2) and the link budget d it sets (decision 1). */
internal enum class DensityClass(
    val links: Int,
) {
    LOW(LOW_LINKS),
    MEDIUM(DENSE_LINKS),
    HIGH(DENSE_LINKS),
}

private const val LOW_LINKS = 6
private const val DENSE_LINKS = 5

/**
 * How long a central rests between its scan windows in each density class (ADR 0008, scan
 * schedule); every window lasts [ON_MILLIS]. `Responsive` rests 2.5 s at low, not 2 s, so that scan
 * starts stay 6.5 s apart (library rule R11).
 */
internal enum class ScanPolicy(
    private val low: Long,
    private val medium: Long,
    private val high: Long,
) {
    BALANCED(BALANCED_LOW_OFF, BALANCED_MEDIUM_OFF, BALANCED_HIGH_OFF),
    RESPONSIVE(RESPONSIVE_LOW_OFF, RESPONSIVE_MEDIUM_OFF, RESPONSIVE_HIGH_OFF),
    ;

    /** How long a central of class [density] rests after a scan window. */
    fun offMillis(density: DensityClass): Long =
        when (density) {
            DensityClass.LOW -> low
            DensityClass.MEDIUM -> medium
            DensityClass.HIGH -> high
        }

    companion object {
        /** How long each scan window lasts, in every class and policy. */
        const val ON_MILLIS: Long = 4_000
    }
}

private const val BALANCED_LOW_OFF = 4_000L
private const val BALANCED_MEDIUM_OFF = 12_000L
private const val BALANCED_HIGH_OFF = 16_000L
private const val RESPONSIVE_LOW_OFF = 2_500L
private const val RESPONSIVE_MEDIUM_OFF = 8_000L
private const val RESPONSIVE_HIGH_OFF = 12_000L

/**
 * A node's density class from its estimates of local density (ADR 0008 decision 2), or always
 * [pinned] if set. The first estimate is classified at once: low under [MEDIUM_BOUND], high over
 * [HIGH_BOUND], else medium. After that the class moves up at [MEDIUM_UP] and [HIGH_UP] and down
 * below [LOW_DOWN] and [MEDIUM_DOWN], each bound × or ÷ 1.25, possibly by two classes at once, and
 * only once it has held for [DWELL_MILLIS].
 */
internal class DensityClassifier(
    private val clock: Clock,
    private val pinned: DensityClass?,
) {
    /** The class; [DensityClass.LOW] until the first estimate. */
    var current: DensityClass = pinned ?: DensityClass.LOW
        private set

    // When the class was last set, or null before the first estimate.
    private var since: Long? = null

    /** Classifies [estimate] and returns the class. */
    fun classify(estimate: Double): DensityClass {
        if (pinned != null) return pinned
        val now = clock.monotonicMillis()
        val held = since
        val target =
            when {
                held == null -> bounded(estimate)
                now - held < DWELL_MILLIS -> current
                else -> switched(estimate)
            }
        if (held == null || target != current) {
            current = target
            since = now
        }
        return current
    }

    private fun bounded(estimate: Double): DensityClass =
        when {
            estimate < MEDIUM_BOUND -> DensityClass.LOW
            estimate <= HIGH_BOUND -> DensityClass.MEDIUM
            else -> DensityClass.HIGH
        }

    // The class the up thresholds reach if above the current one, else the one the down thresholds
    // keep if below it, else the current one.
    private fun switched(estimate: Double): DensityClass {
        val up =
            when {
                estimate >= HIGH_UP -> DensityClass.HIGH
                estimate >= MEDIUM_UP -> DensityClass.MEDIUM
                else -> DensityClass.LOW
            }
        val down =
            when {
                estimate < LOW_DOWN -> DensityClass.LOW
                estimate < MEDIUM_DOWN -> DensityClass.MEDIUM
                else -> DensityClass.HIGH
            }
        return when {
            up > current -> up
            down < current -> down
            else -> current
        }
    }

    companion object {
        /** The lowest estimate of the medium class (ADR 0008 decision 2). */
        const val MEDIUM_BOUND: Double = 10.0

        /** The highest estimate of the medium class. */
        const val HIGH_BOUND: Double = 50.0

        /** The estimate at which a low node moves up to medium: [MEDIUM_BOUND] × 1.25. */
        const val MEDIUM_UP: Double = 12.5

        /** The estimate at which a node moves up to high: [HIGH_BOUND] × 1.25. */
        const val HIGH_UP: Double = 62.5

        /** The estimate below which a node moves down to low: [MEDIUM_BOUND] ÷ 1.25. */
        const val LOW_DOWN: Double = 8.0

        /** The estimate below which a high node moves down to medium: [HIGH_BOUND] ÷ 1.25. */
        const val MEDIUM_DOWN: Double = 40.0

        /** How long a node stays in a class at least, from its first classification or last switch. */
        const val DWELL_MILLIS: Long = 60_000
    }
}

/**
 * A node's estimate of its local density (ADR 0008 decision 2): the distinct advertisement
 * identifiers and identifier-less addresses heard in the last [WINDOW_MILLIS], plus the linked peers
 * not heard, moved [ALPHA] of the way from the previous estimate; the first estimate is the count
 * itself. An identifier-less address that is a link's handle counts as its linked peer.
 */
internal class DensityEstimator(
    private val clock: Clock,
) {
    private val identifiers = mutableMapOf<List<Byte>, Heard>()
    private val anonymous = mutableMapOf<PeerHandle, Long>()
    private var previous: Double? = null

    private class Heard(
        val at: Long,
        val epoch: UInt,
        var id: NodeId?,
    )

    /** Records an accepted sighting of [identifier], advertised in [epoch]. */
    fun heard(
        identifier: ByteArray,
        epoch: UInt,
    ) {
        val id = identifiers[identifier.asList()]?.id
        identifiers[identifier.asList()] = Heard(clock.monotonicMillis(), epoch, id)
    }

    /** Records a sighting without an advertisement from [handle]. */
    fun heardAnonymous(handle: PeerHandle) {
        anonymous[handle] = clock.monotonicMillis()
    }

    /** Stops counting the identifier-less address [handle], as one that failed vetting. */
    fun forget(handle: PeerHandle) {
        anonymous -= handle
    }

    /**
     * The estimate, given this node's [links] by handle; [resolve] names the peer advertising an
     * identifier in an epoch, or `null`.
     */
    fun estimate(
        links: Map<PeerHandle, NodeId>,
        resolve: (ByteArray, UInt) -> NodeId?,
    ): Double {
        val now = clock.monotonicMillis()
        identifiers.values.removeAll { now - it.at > WINDOW_MILLIS }
        anonymous.values.removeAll { now - it > WINDOW_MILLIS }
        identifiers.forEach { (identifier, heard) ->
            heard.id = heard.id ?: resolve(identifier.toByteArray(), heard.epoch)
        }
        val resolved = identifiers.values.mapNotNullTo(mutableSetOf()) { it.id }
        val count =
            identifiers.size + anonymous.keys.count { it !in links } + links.values.count { it !in resolved }
        val estimate = previous?.let { it + ALPHA * (count - it) } ?: count.toDouble()
        previous = estimate
        return estimate
    }

    private companion object {
        /** How long a sighting counts. */
        const val WINDOW_MILLIS = 10_000L

        /** How far each estimate moves from the previous one toward the count. */
        const val ALPHA = 0.3
    }
}
