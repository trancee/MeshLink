package ch.trancee.meshlink.internal

import kotlinx.coroutines.delay

/**
 * A node's neighbor views (ADR 0008 decision 1, spec "Shuffle"). The active view is the node's
 * links within the budget of [LINKS] bearers, kept or opening, in either role: the node dials
 * one candidate at a time, heard in the last [HEARD_MILLIS], while it holds fewer than [LINKS] − 1,
 * which keeps one slot free for newcomers, and accepts a bearer while it holds fewer than [LINKS]. The passive
 * view holds up to [PASSIVE_MAX] identifiers of sighted candidates and of entries received in
 * shuffles, never one the node itself advertises ([own]); a new entry replaces a random one when it
 * is full. Draws every choice from [random]. Not thread-safe: [Links] calls it on the node's serial
 * dispatcher.
 */
internal class Views(
    private val clock: Clock,
    private val random: RandomSource,
    private val own: (ByteArray) -> Boolean,
) {
    private val entries = mutableListOf<ByteArray>()

    /** The passive view's identifiers. */
    val passive: List<ByteArray> get() = entries.toList()

    /** Adds each of [identifiers] the view doesn't hold and the node doesn't advertise. */
    fun merge(identifiers: List<ByteArray>) {
        identifiers.filterNot(own).forEach { id ->
            when {
                entries.any { it.contentEquals(id) } -> Unit
                entries.size < PASSIVE_MAX -> entries += id
                else -> entries[random.nextInt(0, PASSIVE_MAX)] = id
            }
        }
    }

    /** Up to [ENTRIES_MAX] random, distinct identifiers from the passive view. */
    fun sample(): List<ByteArray> = entries.shuffled().take(ENTRIES_MAX)

    /**
     * Which of [dialable] to dial while the node holds [used] bearers: a random one of those
     * [candidates] heard in the last [HEARD_MILLIS], or `null` if none is or no slot below
     * [LINKS] − 1 is free. One dial at a time keeps two nodes from filling their budgets with dials
     * and refusing each other's.
     */
    fun dial(
        dialable: List<PeerHandle>,
        candidates: Map<PeerHandle, Candidate>,
        used: Int,
    ): PeerHandle? {
        if (used >= LINKS - 1) return null
        val now = clock.monotonicMillis()
        val heard = dialable.filter { now - candidates.getValue(it).heard <= HEARD_MILLIS }
        return if (heard.isEmpty()) null else heard[random.nextInt(0, heard.size)]
    }

    /** Whether the node, holding [used] bearers, accepts another. */
    fun accepts(used: Int): Boolean = used < LINKS

    /**
     * Every [SHUFFLE_INTERVAL_MILLIS], [send]s a `SHUFFLE` with a [sample] on one random link of
     * [links], unless the node has no link or an empty passive view. Runs until cancelled.
     */
    suspend fun <T> shuffle(
        links: () -> List<T>,
        send: (T, LinkFrame.Shuffle) -> Unit,
    ): Nothing {
        while (true) {
            delay(SHUFFLE_INTERVAL_MILLIS)
            val kept = links()
            val sample = sample()
            if (kept.isNotEmpty() && sample.isNotEmpty()) {
                send(kept[random.nextInt(0, kept.size)], LinkFrame.Shuffle(sample))
            }
        }
    }

    /** A copy in random order (Fisher–Yates). */
    private fun <T> List<T>.shuffled(): List<T> {
        val copy = toMutableList()
        for (i in copy.lastIndex downTo 1) {
            val j = random.nextInt(0, i + 1)
            copy[i] = copy[j].also { copy[j] = copy[i] }
        }
        return copy
    }

    companion object {
        /** The link budget d, the low-density value (ADR 0008 decision 1; ticket 36 makes it per class). */
        const val LINKS: Int = 6

        /** The passive view's size, 4·d. */
        const val PASSIVE_MAX: Int = 4 * LINKS

        /** The most entries in a `SHUFFLE` or `SHUFFLE_REPLY` (spec `shuffle-entries-max`). */
        const val ENTRIES_MAX: Int = 5

        /** How often a node shuffles (spec `shuffle-interval`). */
        const val SHUFFLE_INTERVAL_MILLIS: Long = 10_000

        /** How recently a candidate must have been heard to be dialed. */
        const val HEARD_MILLIS: Long = 4_000
    }
}

/**
 * A link candidate: its latest mesh [advertisement], or `null` for an identifier-less one, and when
 * it was last [heard] on the monotonic clock.
 */
internal class Candidate(
    val advertisement: Advertisement?,
    val heard: Long,
)
