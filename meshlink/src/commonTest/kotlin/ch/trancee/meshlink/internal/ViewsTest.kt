package ch.trancee.meshlink.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The passive view, dial pool, dial backoff and newcomer reservation of a node (ADR 0008 decision 1). */
@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun aFailedDialBacksOffThreeSecondsThenSixtyUntilAKeptLinkOrTheCandidateLeaves() {
        // Arrange
        val clock = FixedClock(0)
        val views = Views(clock, SeededRandom(SEED)) { false }
        val peer = Handle()
        val dialAt = { at: Long ->
            clock.now = at
            views.dial(listOf(peer), mapOf(peer to Candidate(null, at)), used = 0)
        }

        // Act
        views.dialed(peer, kept = false)
        val first = listOf(dialAt(FIRST_MILLIS - 1), dialAt(FIRST_MILLIS))
        views.dialed(peer, kept = false)
        val repeated = listOf(dialAt(FIRST_MILLIS + REPEATED_MILLIS - 1), dialAt(FIRST_MILLIS + REPEATED_MILLIS))
        views.dialed(peer, kept = true)
        views.dialed(peer, kept = false)
        val keptAt = 2 * FIRST_MILLIS + REPEATED_MILLIS
        val afterKept = listOf(dialAt(keptAt - 1), dialAt(keptAt))
        views.dialed(peer, kept = false)
        clock.now += REPEATED_MILLIS
        views.dial(emptyList(), emptyMap(), used = 0)
        views.dialed(peer, kept = false)
        val afterLeaving = dialAt(clock.now + FIRST_MILLIS)

        // Assert
        assertEquals(listOf(null, peer), first)
        assertEquals(listOf(null, peer), repeated)
        assertEquals(listOf(null, peer), afterKept)
        assertEquals(peer, afterLeaving)
    }

    @Test
    fun aCoolDownKeepsAPeerUndialedFor120SecondsAndAKeptLinkDoesNotEndIt() {
        // Arrange
        val clock = FixedClock(0)
        val views = Views(clock, SeededRandom(SEED)) { false }
        val peer = Handle()
        val dialAt = { at: Long ->
            clock.now = at
            views.dial(listOf(peer), mapOf(peer to Candidate(null, at)), used = 0)
        }

        // Act
        views.coolDown(peer)
        views.dialed(peer, kept = true)
        val dials = listOf(dialAt(COOL_DOWN_MILLIS - 1), dialAt(COOL_DOWN_MILLIS))

        // Assert
        assertEquals(listOf(null, peer), dials)
    }

    @Test
    fun aNodeHoldingItsBudgetForThirtySecondsFreesOneRandomLinkAndADropBelowRestartsTheWait() =
        runTest {
            // Arrange
            val views = Views(FixedClock(0), SeededRandom(SEED)) { false }
            val links = List(Views.LINKS) { it }
            val freed = mutableListOf<Pair<Long, Int>>()
            val hold = { used: Int, held: List<Int> ->
                views.held(used, backgroundScope, { held }) { freed += testScheduler.currentTime to it }
            }

            // Act
            hold(Views.LINKS, links)
            advanceTimeBy(FULL_MILLIS - 1)
            hold(Views.LINKS - 1, links)
            hold(Views.LINKS, links)
            advanceTimeBy(1)
            hold(Views.LINKS, links)
            advanceTimeBy(FULL_MILLIS)
            hold(Views.LINKS, emptyList())
            advanceTimeBy(FULL_MILLIS + 1)
            val frees =
                List(SAMPLES) {
                    hold(Views.LINKS, links)
                    advanceTimeBy(FULL_MILLIS + 1)
                    freed.last().second
                }

            // Assert
            assertEquals(2 * FULL_MILLIS - 1, freed.first().first)
            assertEquals(1 + SAMPLES, freed.size)
            assertTrue(frees.toSet().size > 1, "always the same link")
        }

    private fun id(n: Int): ByteArray = ByteArray(Advertisement.RESOLVABLE_TAG_LENGTH) { n.toByte() }

    private class Handle : PeerHandle

    private class FixedClock(
        var now: Long,
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
        const val FIRST_MILLIS = 3_000L
        const val REPEATED_MILLIS = 60_000L
        const val COOL_DOWN_MILLIS = 120_000L
        const val FULL_MILLIS = 30_000L
    }
}
