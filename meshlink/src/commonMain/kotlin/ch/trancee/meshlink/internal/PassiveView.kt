package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import kotlinx.coroutines.delay

/**
 * A node's passive view (ADR 0008 decision 1, spec "Shuffle"): up to 4·d identifiers, for the link
 * budget d that [links] gives, of sighted candidates and of entries received in shuffles, never one
 * the node itself advertises ([NeighborLists.advertises]); a new entry replaces a random one when it
 * is full. Draws every
 * choice from [random]. Not thread-safe: [Links] calls it on the node's serial dispatcher.
 */
internal class PassiveView<T>(
    private val random: RandomSource,
    private val neighbors: NeighborLists,
    private val links: () -> Int,
    private val linked: () -> Map<NodeId, T>,
) {
    private val entries = mutableListOf<ByteArray>()

    /** The passive view's identifiers. */
    val identifiers: List<ByteArray> get() = entries.toList()

    /**
     * Adds each of [identifiers] the view doesn't hold and the node doesn't advertise, after dropping
     * random entries beyond 4·d, as when d shrank.
     */
    fun merge(identifiers: List<ByteArray>) {
        val max = PASSIVE_PER_LINK * links()
        while (entries.size > max) entries.removeAt(random.nextInt(0, entries.size))
        identifiers.filterNot(neighbors::advertises).forEach { id ->
            when {
                entries.any { it.contentEquals(id) } -> Unit
                entries.size < max -> entries += id
                else -> entries[random.nextInt(0, max)] = id
            }
        }
    }

    /** Up to [ENTRIES_MAX] random, distinct identifiers from the passive view. */
    fun sample(): List<ByteArray> = entries.shuffled().take(ENTRIES_MAX)

    /**
     * Every [SHUFFLE_INTERVAL_MILLIS], [send]s a `SHUFFLE` with a [sample] on one random link of
     * [linked], unless the node has no link or an empty passive view. Runs until cancelled.
     */
    suspend fun shuffle(send: (T, LinkFrame.Shuffle) -> Unit): Nothing {
        while (true) {
            delay(SHUFFLE_INTERVAL_MILLIS)
            val kept = linked().values.toList()
            val sample = sample()
            if (kept.isNotEmpty() && sample.isNotEmpty()) {
                send(kept[random.nextInt(0, kept.size)], LinkFrame.Shuffle(sample))
            }
        }
    }

    /** A copy in random order (Fisher–Yates). */
    private fun <E> List<E>.shuffled(): List<E> {
        val copy = toMutableList()
        for (i in copy.lastIndex downTo 1) {
            val j = random.nextInt(0, i + 1)
            copy[i] = copy[j].also { copy[j] = copy[i] }
        }
        return copy
    }

    companion object {
        /** The passive view's entries per unit of the link budget d. */
        const val PASSIVE_PER_LINK: Int = 4

        /** The most entries in a `SHUFFLE` or `SHUFFLE_REPLY` (spec `shuffle-entries-max`). */
        const val ENTRIES_MAX: Int = 5

        /** How often a node shuffles (spec `shuffle-interval`). */
        const val SHUFFLE_INTERVAL_MILLIS: Long = 10_000
    }
}
