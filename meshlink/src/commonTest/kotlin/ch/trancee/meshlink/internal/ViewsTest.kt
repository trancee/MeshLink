package ch.trancee.meshlink.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The passive view and dial pool of a node (ADR 0008 decision 1, ticket 35a). */
class ViewsTest {
    @Test
    fun aFullPassiveViewReplacesARandomEntryAndSkipsKnownAndOwnIdentifiers() {
        // Arrange
        val views = Views(FixedClock(0), SeededRandom(SEED)) { it.contentEquals(id(OWN)) }
        views.merge(List(Views.PASSIVE_MAX) { id(it) })
        val before = views.passive.map { it.toList() }

        // Act
        views.merge(listOf(id(0), id(OWN), id(NEW), id(NEW)))

        // Assert
        val after = views.passive.map { it.toList() }
        assertEquals(Views.PASSIVE_MAX, after.size)
        assertTrue(id(NEW).toList() in after)
        assertTrue(id(OWN).toList() !in after)
        assertEquals(Views.PASSIVE_MAX - 1, after.count { it in before })
    }

    @Test
    fun aSampleHoldsAtMostFiveDistinctEntriesOfTheView() {
        // Arrange
        val views = Views(FixedClock(0), SeededRandom(SEED)) { false }
        val empty = views.sample()
        views.merge(List(3) { id(it) })
        val small = views.sample()
        views.merge(List(Views.PASSIVE_MAX) { id(it) })

        // Act
        val samples = List(SAMPLES) { views.sample().map { it.toList() } }

        // Assert
        assertEquals(emptyList(), empty)
        assertEquals(setOf(0, 1, 2).map { id(it).toList() }.toSet(), small.map { it.toList() }.toSet())
        assertTrue(samples.all { it.size == Views.ENTRIES_MAX && it.toSet().size == Views.ENTRIES_MAX })
        assertTrue(samples.toSet().size > 1, "always the same sample")
    }

    @Test
    fun theDialPoolPicksOneCandidateHeardInTheLastFourSecondsWhileASlotBelowTheBudgetIsFree() {
        // Arrange
        val views = Views(FixedClock(NOW), SeededRandom(SEED)) { false }
        val heard = List<PeerHandle>(Views.LINKS) { Handle() }
        val old = Handle()
        val fresh = heard.associateWith { Candidate(null, NOW - HEARD_MILLIS) }
        val candidates = fresh + (old to Candidate(null, NOW - HEARD_MILLIS - 1))

        // Act
        val picks = List(SAMPLES) { views.dial(heard + old, candidates, used = Views.LINKS - 2) }
        val onlyOld = views.dial(listOf(old), candidates, used = 0)
        val full = views.dial(heard, candidates, used = Views.LINKS - 1)

        // Assert
        assertTrue(picks.all { it in heard })
        assertTrue(picks.toSet().size > 1, "always the same candidate")
        assertEquals(null, onlyOld)
        assertEquals(null, full)
        assertTrue(views.accepts(Views.LINKS - 1))
        assertTrue(!views.accepts(Views.LINKS))
    }

    private fun id(n: Int): ByteArray = ByteArray(Advertisement.RESOLVABLE_TAG_LENGTH) { n.toByte() }

    private class Handle : PeerHandle

    private class FixedClock(
        private val now: Long,
    ) : Clock {
        override fun monotonicMillis(): Long = now

        override fun wallClockMillis(): Long = now
    }

    private companion object {
        const val NOW = 100_000L
        const val SEED = 35L
        const val OWN = 99
        const val NEW = 98
        const val SAMPLES = 8
        const val HEARD_MILLIS = 4_000L
    }
}
