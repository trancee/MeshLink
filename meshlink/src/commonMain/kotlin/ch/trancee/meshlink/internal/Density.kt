package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DensityClass
import ch.trancee.meshlink.NodeId

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
    /** The class; [DensityClass.Low] until the first estimate. */
    var current: DensityClass = pinned ?: DensityClass.Low
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
            estimate < MEDIUM_BOUND -> DensityClass.Low
            estimate <= HIGH_BOUND -> DensityClass.Medium
            else -> DensityClass.High
        }

    // The class the up thresholds reach if above the current one, else the one the down thresholds
    // keep if below it, else the current one.
    private fun switched(estimate: Double): DensityClass {
        val up =
            when {
                estimate >= HIGH_UP -> DensityClass.High
                estimate >= MEDIUM_UP -> DensityClass.Medium
                else -> DensityClass.Low
            }
        val down =
            when {
                estimate < LOW_DOWN -> DensityClass.Low
                estimate < MEDIUM_DOWN -> DensityClass.Medium
                else -> DensityClass.High
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
