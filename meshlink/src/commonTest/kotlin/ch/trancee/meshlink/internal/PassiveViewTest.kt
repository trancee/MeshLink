package ch.trancee.meshlink.internal

import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A node's passive view (ADR 0008 decision 1, spec "Shuffle"). */
class PassiveViewTest {
    @Test
    fun aFullPassiveViewReplacesARandomEntryAndSkipsKnownAndOwnIdentifiers() {
        // Arrange
        val neighbors = NeighborLists(FixedClock, TestScope()) {}
        val view = passive(neighbors)
        neighbors.advertised(id(OWN), id(OWN))
        view.merge(List(PASSIVE_MAX) { id(it) })
        val before = view.identifiers.map { it.toList() }

        // Act
        view.merge(listOf(id(0), id(OWN), id(NEW), id(NEW)))

        // Assert
        val after = view.identifiers.map { it.toList() }
        assertEquals(PASSIVE_MAX, after.size)
        assertTrue(id(NEW).toList() in after)
        assertTrue(id(OWN).toList() !in after)
        assertEquals(PASSIVE_MAX - 1, after.count { it in before })
    }

    @Test
    fun theViewHoldsFourTimesTheBudgetAndDropsRandomEntriesWhenItShrinks() {
        // Arrange
        var budget = LINKS
        val view = passive(NeighborLists(FixedClock, TestScope()) {}) { budget }
        view.merge(List(PASSIVE_MAX + 1) { id(it) })
        val before = view.identifiers.map { it.toList() }

        // Act
        budget = DENSE_LINKS
        view.merge(listOf(id(NEW)))

        // Assert
        val after = view.identifiers.map { it.toList() }
        assertEquals(PASSIVE_MAX, before.size)
        assertEquals(4 * DENSE_LINKS, after.size)
        assertTrue(id(NEW).toList() in after)
        assertEquals(4 * DENSE_LINKS - 1, after.count { it in before })
    }

    @Test
    fun aSampleHoldsAtMostFiveDistinctEntriesOfTheView() {
        // Arrange
        val view = passive(NeighborLists(FixedClock, TestScope()) {})
        val empty = view.sample()
        view.merge(List(3) { id(it) })
        val small = view.sample()
        view.merge(List(PASSIVE_MAX) { id(it) })

        // Act
        val samples = List(SAMPLES) { view.sample().map { it.toList() } }

        // Assert
        assertEquals(emptyList(), empty)
        assertEquals(setOf(0, 1, 2).map { id(it).toList() }.toSet(), small.map { it.toList() }.toSet())
        assertTrue(samples.all { it.size == PassiveView.ENTRIES_MAX && it.toSet().size == PassiveView.ENTRIES_MAX })
        assertTrue(samples.toSet().size > 1, "always the same sample")
    }

    private fun passive(
        neighbors: NeighborLists,
        links: () -> Int = { LINKS },
    ): PassiveView<Int> {
        val random = SeededRandom(SEED)
        return PassiveView(random, neighbors, links) { emptyMap() }
    }

    private fun id(n: Int): ByteArray = ByteArray(Advertisement.RESOLVABLE_TAG_LENGTH) { n.toByte() }

    private object FixedClock : Clock {
        override fun monotonicMillis(): Long = 0

        override fun wallClockMillis(): Long = 0
    }

    private companion object {
        const val SEED = 35L
        const val LINKS = 6
        const val DENSE_LINKS = 5
        const val PASSIVE_MAX = 4 * LINKS
        const val OWN = 99
        const val NEW = 98
        const val SAMPLES = 8
    }
}
