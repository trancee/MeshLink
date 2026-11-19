package ch.trancee.meshlink.internal

/**
 * Which link candidates a node keeps undialed, and until when (v1 parameters, "Dial backoff to one
 * peer"): one whose dial failed for [FIRST_BACKOFF_MILLIS], or [REPEATED_BACKOFF_MILLIS] after an
 * earlier failure until a kept link clears its failures, and one whose peer closed its kept link or
 * whose link the node freed for [COOL_DOWN_MILLIS]. Not thread-safe: [Views] calls it on the node's
 * serial dispatcher.
 */
internal class Backoff(
    private val clock: Clock,
) {
    // Consecutive failed dials to each candidate, and when each backed-off or cooled-down one may be dialed.
    private val failures = mutableMapOf<PeerHandle, Int>()
    private val until = mutableMapOf<PeerHandle, Long>()

    /** Ends every backoff due at [now] and forgets the failures of handles not among [candidates]. */
    fun update(
        candidates: Set<PeerHandle>,
        now: Long,
    ) {
        failures.keys.retainAll(candidates)
        until.values.removeAll { it <= now }
    }

    /** Whether [handle] is backed off or cooled down, as of the last [update]. */
    operator fun contains(handle: PeerHandle): Boolean = handle in until

    /** A dial to [handle] ended: a kept link clears its failures; a failure backs it off. */
    fun dialed(
        handle: PeerHandle,
        kept: Boolean,
    ) {
        if (kept) {
            failures.remove(handle)
            return
        }
        val count = (failures[handle] ?: 0) + 1
        failures[handle] = count
        val backoff = if (count == 1) FIRST_BACKOFF_MILLIS else REPEATED_BACKOFF_MILLIS
        until[handle] = clock.monotonicMillis() + backoff
    }

    /** Keeps [handle] undialed for [COOL_DOWN_MILLIS]: its peer closed its kept link, or the node freed it. */
    fun coolDown(handle: PeerHandle) {
        until[handle] = clock.monotonicMillis() + COOL_DOWN_MILLIS
    }

    companion object {
        /** How long a candidate stays undialed after its first failed dial. */
        const val FIRST_BACKOFF_MILLIS: Long = 3_000

        /** How long a candidate stays undialed after a repeated failed dial. */
        const val REPEATED_BACKOFF_MILLIS: Long = 60_000

        /** How long a candidate stays undialed after its peer closed its kept link or the node freed it. */
        const val COOL_DOWN_MILLIS: Long = 120_000
    }
}
