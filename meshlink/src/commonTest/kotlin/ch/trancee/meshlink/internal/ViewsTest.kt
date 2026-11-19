package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The dial pool, dial backoff, newcomer reservation, bridge rule and diversity
 * replacement of a node (ADR 0008 decision 1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewsTest {
    @Test
    fun theDialPoolPicksOneCandidateHeardInTheLastFourSecondsWhileASlotBelowTheBudgetIsFree() {
        // Arrange
        val views = Node().views
        val heard = List<PeerHandle>(Views.LINKS) { Handle() }
        val old = Handle()
        val fresh = heard.mapIndexed { n, handle -> handle to known(n, NOW - HEARD_MILLIS) }.toMap()
        val candidates = fresh + (old to known(Views.LINKS, NOW - HEARD_MILLIS - 1))

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
    fun anOrdinaryDialPicksAnIdentifiedCandidateTheFewestLinkedPeersName() {
        // Arrange
        val node = Node()
        node.neighbors.received(nodeId(1), list(OWN_1, NEXT_1, 1, 2))
        node.neighbors.received(nodeId(2), list(OWN_2, NEXT_2, 2))
        val handles = List<PeerHandle>(4) { Handle() }
        val candidates = handles.mapIndexed { n, handle -> handle to known(n + 1, NOW) }.toMap()

        // Act
        val picks = List(SAMPLES) { node.views.dial(handles, candidates, used = 0) }

        // Assert
        assertEquals(setOf(handles[2], handles[3]), picks.toSet())
    }

    @Test
    fun aNewCandidateOutsideTheNeighborhoodOrAnIdentifierlessOneIsDialedFirstAtMostOncePerMinute() {
        // Arrange
        val node = Node()
        node.neighbors.received(nodeId(1), list(OWN_1, NEXT_1, 2))
        val inside = Handle() to Candidate(ad(2), NOW, NOW - SETTLE_MILLIS)
        val next = Handle() to Candidate(ad(NEXT_1), NOW, NOW - SETTLE_MILLIS)
        val known = Handle() to Candidate(ad(3), NOW, NOW - SETTLE_MILLIS, known = true)
        val stale = Handle() to Candidate(ad(4), NOW, NOW - NEW_MILLIS - 1)
        val settling = Handle() to Candidate(ad(6), NOW, NOW - SETTLE_MILLIS + 1)
        val fresh = Handle() to Candidate(ad(5), NOW, NOW - NEW_MILLIS)
        val later = Handle()
        val anonymous = Handle()
        val dialAt = { at: Long, used: Int, candidates: List<Pair<PeerHandle, Candidate>> ->
            node.clock.now = at
            node.views.dial(candidates.map { it.first }, candidates.toMap(), used)
        }
        val heardAt = { at: Long -> later to Candidate(ad(LATER), at, at - SETTLE_MILLIS) }

        // Act
        val withoutNew = dialAt(NOW, Views.LINKS - 1, listOf(inside, next, known, stale, settling))
        val bridge = dialAt(NOW, Views.LINKS - 1, listOf(inside, next, known, stale, settling, fresh))
        val limited = dialAt(NOW + BRIDGE_MILLIS - 1, Views.LINKS - 1, listOf(heardAt(NOW + BRIDGE_MILLIS - 1)))
        val unlimited = dialAt(NOW + BRIDGE_MILLIS, Views.LINKS - 1, listOf(heardAt(NOW + BRIDGE_MILLIS)))
        val untilThen = NOW + 2 * BRIDGE_MILLIS
        val noAnonymous = dialAt(untilThen - 1, 0, listOf(anonymous to old(null, untilThen - 1)))
        val first = dialAt(untilThen, 0, listOf(stale.first to known(4, untilThen), anonymous to old(null, untilThen)))

        // Assert
        assertEquals(listOf(null, fresh.first), listOf(withoutNew, bridge))
        assertEquals(listOf(null, later), listOf(limited, unlimited))
        assertEquals(listOf(null, anonymous), listOf(noAnonymous, first))
    }

    @Test
    fun aFullNodeFreesItsMostRedundantLinkForABridgeWhichFreesTheMostRedundantOtherOnceKept() {
        // Arrange
        val node = Node()
        List(Views.LINKS) { n -> node.links[nodeId(n + 1)] = n + 1 }
        val bridge = Handle()
        val candidates = mapOf<PeerHandle, Candidate>(bridge to Candidate(ad(NEW), NOW, NOW - SETTLE_MILLIS))
        val noneRedundant = node.views.dial(listOf(bridge), candidates, used = Views.LINKS)
        val freedNone = node.freed.toList()
        node.neighbors.received(nodeId(1), list(OWN_1, NEXT_1, OWN_2, OWN_3))
        node.neighbors.received(nodeId(2), list(OWN_2, NEXT_2, OWN_1))
        node.neighbors.received(nodeId(3), list(OWN_3, NEXT_3, OWN_1, UNREACHED))

        // Act
        val evicting = node.views.dial(listOf(bridge), candidates, used = Views.LINKS)
        val whileClosing = node.views.dial(listOf(bridge), candidates, used = Views.LINKS)
        node.links.remove(nodeId(1))
        node.neighbors.unlinked(nodeId(1))
        val pending = node.views.dial(listOf(bridge), candidates, used = Views.LINKS - 1)
        node.links[nodeId(NEW)] = NEW
        node.neighbors.received(nodeId(NEW), list(NEW, LATER, OWN_1, OWN_2, OWN_3, UNREACHED))
        node.views.kept(Handle(), nodeId(NEW), used = Views.LINKS)
        val keptOther = node.freed.toList()
        node.views.kept(bridge, nodeId(NEW), used = Views.LINKS)

        // Assert
        assertEquals(listOf(null, null, null), listOf(noneRedundant, evicting, whileClosing))
        assertEquals(emptyList(), freedNone)
        assertEquals(bridge, pending)
        assertEquals(listOf(1), keptOther)
        assertEquals(listOf(1, 3), node.freed)
    }

    @Test
    fun whileItsBridgeOrReplacementDialIsInFlightANodeAcceptsOneBearerBeyondItsBudget() {
        // Arrange
        val node = Node()
        List(Views.LINKS - 1) { n -> node.links[nodeId(n + 1)] = n + 1 }
        val bridges = List(2) { Handle() }
        val dialAt = { at: Long, bridge: PeerHandle ->
            node.clock.now = at
            val candidates = mapOf(bridge to Candidate(ad(NEW), at, at - SETTLE_MILLIS))
            node.views.dial(listOf(bridge), candidates, used = Views.LINKS - 1)
        }
        val before = node.views.accepts(Views.LINKS)

        // Act
        dialAt(NOW, bridges[0])
        val failing = listOf(node.views.accepts(Views.LINKS), node.views.accepts(Views.LINKS + 1))
        node.views.dialed(bridges[0], kept = false)
        val failed = node.views.accepts(Views.LINKS)
        dialAt(NOW + BRIDGE_MILLIS, bridges[1])
        val keeping = node.views.accepts(Views.LINKS)
        node.views.kept(bridges[1], nodeId(NEW), used = Views.LINKS)
        val kept = node.views.accepts(Views.LINKS)

        // Assert
        assertEquals(listOf(true, false), failing)
        assertEquals(listOf(false, false, true, false), listOf(before, failed, keeping, kept))
    }

    @Test
    fun aFailedBridgeABridgeKeptBelowTheBudgetOrOneKeptWithoutAnotherLinkFreesNothing() {
        // Arrange
        val node = Node()
        List(Views.LINKS - 1) { n -> node.links[nodeId(n + 1)] = n + 1 }
        val bridges = List(2) { Handle() }
        val dialAt = { at: Long, bridge: PeerHandle ->
            node.clock.now = at
            val candidates = mapOf(bridge to Candidate(ad(NEW), at, at - SETTLE_MILLIS))
            node.views.dial(listOf(bridge), candidates, used = Views.LINKS - 1)
        }

        // Act
        val failed = dialAt(NOW, bridges[0])
        node.views.dialed(bridges[0], kept = false)
        node.views.kept(bridges[0], nodeId(NEW), used = Views.LINKS)
        val below = dialAt(NOW + BRIDGE_MILLIS, bridges[1])
        node.views.kept(bridges[1], nodeId(NEW), used = Views.LINKS - 1)
        node.views.kept(bridges[1], nodeId(NEW), used = Views.LINKS)
        node.links.clear()
        node.links[nodeId(NEW)] = NEW
        val alone = dialAt(NOW + 2 * BRIDGE_MILLIS, bridges[0])
        node.views.kept(bridges[0], nodeId(NEW), used = Views.LINKS)

        // Assert
        assertEquals(bridges + bridges[0], listOf(failed, below, alone))
        assertEquals(emptyList(), node.freed)
    }

    @Test
    fun everyThirtySecondsANodeThatCannotDialReplacesItsMostRedundantLinkForACandidateOverlappingTwoFewer() =
        runTest {
            // Arrange
            val node = Node(clock = TestClockOf(this))
            node.neighbors.advertised(id(OWN), id(OWN))
            val peers = List(Views.LINKS - 1) { it + 1 }
            peers.forEach { node.links[nodeId(it)] = it }
            val named = { by: Int ->
                peers.forEach { n ->
                    val others = peers.filter { it != n }.map { OWN_1 + it } + OWN
                    val listed = if (n <= by) others + NEW else others
                    node.neighbors.received(nodeId(n), list(OWN_1 + n, NEXT_1 + n, *listed.toIntArray()))
                }
            }
            val candidate = Handle()
            val unsettled = Handle()
            val picks = mutableListOf<Pair<Long, PeerHandle?>>()
            val dial = {
                val now = testScheduler.currentTime
                val candidates =
                    mapOf<PeerHandle, Candidate>(
                        candidate to Candidate(ad(NEW), now, now - SETTLE_MILLIS),
                        unsettled to Candidate(ad(LATER), now, now - SETTLE_MILLIS + 1),
                    )
                picks += now to node.views.dial(candidates.keys.toList(), candidates, Views.LINKS - 1)
            }
            backgroundScope.launch { node.views.diversify(dial) }

            // Act
            named(4)
            advanceTimeBy(REPLACE_MILLIS - 1)
            dial()
            advanceTimeBy(2)
            named(3)
            dial()
            advanceTimeBy(REPLACE_MILLIS)
            node.links[nodeId(NEW)] = NEW
            node.views.kept(candidate, nodeId(NEW), used = Views.LINKS)

            // Assert
            val expected: List<Pair<Long, PeerHandle?>> =
                listOf(
                    REPLACE_MILLIS - 1 to null,
                    REPLACE_MILLIS to null,
                    REPLACE_MILLIS + 1 to null,
                    2 * REPLACE_MILLIS to candidate,
                )
            assertEquals(expected, picks)
            assertEquals(1, node.freed.size)
            assertTrue(node.freed.single() in 1..3)
        }

    @Test
    fun aFailedDialBacksOffThreeSecondsThenSixtyUntilAKeptLinkOrTheCandidateLeaves() {
        // Arrange
        val node = Node(FixedClock(0))
        val views = node.views
        val peer = Handle()
        val dialAt = { at: Long ->
            node.clock.now = at
            views.dial(listOf(peer), mapOf(peer to old(ad(1), at)), used = 0)
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
        node.clock.now += REPEATED_MILLIS
        views.dial(emptyList(), emptyMap(), used = 0)
        views.dialed(peer, kept = false)
        val afterLeaving = dialAt(node.clock.now + FIRST_MILLIS)

        // Assert
        assertEquals(listOf(null, peer), first)
        assertEquals(listOf(null, peer), repeated)
        assertEquals(listOf(null, peer), afterKept)
        assertEquals(peer, afterLeaving)
    }

    @Test
    fun aCoolDownKeepsAPeerUndialedFor120SecondsAndAKeptLinkDoesNotEndIt() {
        // Arrange
        val node = Node(FixedClock(0))
        val peer = Handle()
        val dialAt = { at: Long ->
            node.clock.now = at
            node.views.dial(listOf(peer), mapOf(peer to old(ad(1), at)), used = 0)
        }

        // Act
        node.views.backoff.coolDown(peer)
        node.views.dialed(peer, kept = true)
        val dials = listOf(dialAt(COOL_DOWN_MILLIS - 1), dialAt(COOL_DOWN_MILLIS))

        // Assert
        assertEquals(listOf(null, peer), dials)
    }

    @Test
    fun aNodeHoldingItsBudgetForThirtySecondsFreesOneRandomLinkAndADropBelowRestartsTheWait() =
        runTest {
            // Arrange
            val links = List(Views.LINKS) { nodeId(it) to it }.toMap()
            var held = emptyMap<NodeId, Int>()
            val freed = mutableListOf<Pair<Long, Int>>()
            val neighbors = NeighborLists(FixedClock(0), TestScope()) {}
            val views =
                Views(FixedClock(0), SeededRandom(SEED), neighbors, { held }) {
                    freed += testScheduler.currentTime to it
                }
            val hold = { used: Int, now: Map<NodeId, Int> ->
                held = now
                views.held(used, backgroundScope)
            }
            val advance = { millis: Long -> advanceTimeBy(millis) }

            // Act
            hold(Views.LINKS, links)
            advance(FULL_MILLIS - 1)
            hold(Views.LINKS - 1, links)
            hold(Views.LINKS, links)
            advance(1)
            hold(Views.LINKS, links)
            advance(FULL_MILLIS)
            hold(Views.LINKS, emptyMap())
            advance(FULL_MILLIS + 1)
            val frees =
                List(SAMPLES) {
                    hold(Views.LINKS, links)
                    advance(FULL_MILLIS + 1)
                    freed.last().second
                }

            // Assert
            assertEquals(2 * FULL_MILLIS - 1, freed.first().first)
            assertEquals(1 + SAMPLES, freed.size)
            assertTrue(frees.toSet().size > 1, "always the same link")
        }

    /** A node's views over its [links], recording each link they [freed]. */
    private class Node(
        val clock: FixedClock = FixedClock(NOW),
    ) {
        val neighbors = NeighborLists(clock, TestScope()) {}
        val links = mutableMapOf<NodeId, Int>()
        val freed = mutableListOf<Int>()
        val views = Views(clock, SeededRandom(SEED), neighbors, { links.toMap() }) { freed += it }
    }

    /** An identified candidate heard at [at], first heard long before. */
    private fun known(
        n: Int,
        at: Long,
    ): Candidate = old(ad(n), at)

    private fun old(
        advertisement: Advertisement?,
        at: Long,
    ): Candidate = Candidate(advertisement, at, at - NEW_MILLIS - 1)

    private fun ad(n: Int): Advertisement =
        Advertisement(0, ByteArray(Advertisement.MESH_TAG_LENGTH), id(n), ByteArray(Advertisement.RANDOM_LENGTH))

    private fun list(
        own: Int,
        next: Int,
        vararg neighbors: Int,
    ): LinkFrame.NeighborList = LinkFrame.NeighborList(id(own), id(next), neighbors.map { id(it) })

    private fun id(n: Int): ByteArray = ByteArray(Advertisement.RESOLVABLE_TAG_LENGTH) { n.toByte() }

    private fun nodeId(n: Int): NodeId = NodeId(ByteArray(NODE_ID_LENGTH) { n.toByte() })

    private class Handle : PeerHandle

    private open class FixedClock(
        var now: Long,
    ) : Clock {
        override fun monotonicMillis(): Long = now

        override fun wallClockMillis(): Long = now
    }

    /** The test scheduler's time. */
    private class TestClockOf(
        private val scope: TestScope,
    ) : FixedClock(0) {
        override fun monotonicMillis(): Long = scope.testScheduler.currentTime
    }

    private companion object {
        const val NOW = 100_000L
        const val SEED = 35L
        const val OWN = 99
        const val NEW = 98
        const val LATER = 97
        const val UNREACHED = 96
        const val OWN_1 = 11
        const val OWN_2 = 21
        const val OWN_3 = 31
        const val NEXT_1 = 41
        const val NEXT_2 = 51
        const val NEXT_3 = 61
        const val NODE_ID_LENGTH = 32
        const val SAMPLES = 8
        const val HEARD_MILLIS = 4_000L
        const val NEW_MILLIS = 13_000L
        const val SETTLE_MILLIS = 5_000L
        const val BRIDGE_MILLIS = 60_000L
        const val REPLACE_MILLIS = 30_000L
        const val FIRST_MILLIS = 3_000L
        const val REPEATED_MILLIS = 60_000L
        const val COOL_DOWN_MILLIS = 120_000L
        const val FULL_MILLIS = 30_000L
    }
}
