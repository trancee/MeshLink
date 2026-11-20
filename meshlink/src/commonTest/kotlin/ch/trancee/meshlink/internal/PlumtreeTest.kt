package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

// The broadcast tree on lanes to three linked peers: eager and lazy links, grafts, and its bounds.
@OptIn(ExperimentalCoroutinesApi::class)
class PlumtreeTest {
    private val a = nodeId(1)
    private val b = nodeId(2)
    private val c = nodeId(3)
    private val d = nodeId(4)
    private val lanes = Lanes().apply { listOf(a, b, c).forEach { open(it) } }
    private var density = DensityClass.MEDIUM

    private fun TestScope.tree() = Plumtree(lanes, SchedulerClock(testScheduler), backgroundScope) { density }

    private suspend fun waiting(vararg peers: NodeId = arrayOf(a, b, c)) =
        peers.associateWith { peer -> lanes.drain(peer).map { it.render().toList() } }

    @Test
    fun aDuplicateOnAnEagerLinkAnswersWithOnePruneAndTheLinkThenGetsIhave() =
        runTest {
            // Arrange
            val tree = tree()
            tree.spread(a, parcel(1), EXPIRY)
            waiting()

            // Act
            tree.duplicate(b)
            val pruned = waiting()
            tree.duplicate(b)
            val again = waiting()
            tree.spread(null, parcel(2), EXPIRY)

            // Assert
            assertEquals(mapOf(a to emptyList(), b to listOf(PRUNE), c to emptyList()), pruned)
            assertEquals(mapOf(a to emptyList<List<Byte>>(), b to emptyList(), c to emptyList()), again)
            assertEquals(mapOf(a to listOf(full(2)), b to listOf(ihave(2)), c to listOf(full(2))), waiting())
        }

    @Test
    fun aDuplicateFromAPeerWithoutALaneLeavesItsNextLinkEager() =
        runTest {
            // Arrange
            val tree = tree()

            // Act
            tree.duplicate(d)
            lanes.open(d)
            tree.spread(null, parcel(1), EXPIRY)

            // Assert
            assertEquals(listOf(full(1)), waiting(d).getValue(d))
        }

    @Test
    fun theLowClassNeverPrunesAndSendsInFullOnLazyLinks() =
        runTest {
            // Arrange
            val tree = tree()
            density = DensityClass.LOW
            tree.pruned(b)

            // Act
            tree.duplicate(c)
            tree.spread(a, parcel(1), EXPIRY)

            // Assert
            assertEquals(mapOf(a to emptyList(), b to listOf(full(1)), c to listOf(full(1))), waiting())
        }

    @Test
    fun aPruneMakesALinkedPeersLinkLazyAndIsIgnoredFromAnUnlinkedPeer() =
        runTest {
            // Arrange
            val tree = tree()

            // Act
            tree.pruned(b)
            tree.pruned(d)
            lanes.open(d)
            tree.spread(null, parcel(1), EXPIRY)

            // Assert
            val expected =
                mapOf(a to listOf(full(1)), b to listOf(ihave(1)), c to listOf(full(1)), d to listOf(full(1)))
            assertEquals(expected, waiting(a, b, c, d))
        }

    @Test
    fun anAnnouncedIdIsGraftedFromEachAnnouncerInTurnAndTheirLinksTurnEager() =
        runTest {
            // Arrange
            val tree = tree()
            listOf(a, b, c).forEach { tree.pruned(it) }

            // Act
            tree.announced(a, messageId(1))
            tree.announced(b, messageId(1))
            tree.announced(a, messageId(1))
            advanceTimeBy(Plumtree.GRAFT_BASE_MILLIS - 1)
            val early = waiting()
            advanceTimeBy(1)
            runCurrent()
            val first = waiting()
            advanceTimeBy(Plumtree.GRAFT_PER_ANNOUNCER_MILLIS - 1)
            val notYet = waiting()
            advanceTimeBy(1)
            runCurrent()
            val second = waiting()
            advanceTimeBy(Plumtree.GRAFT_PER_ANNOUNCER_MILLIS * 2)
            tree.spread(null, parcel(2), EXPIRY)

            // Assert
            val none = mapOf(a to emptyList<List<Byte>>(), b to emptyList(), c to emptyList())
            assertEquals(none, early)
            assertEquals(mapOf(a to listOf(graft(1)), b to emptyList(), c to emptyList()), first)
            assertEquals(none, notYet)
            assertEquals(mapOf(a to emptyList(), b to listOf(graft(1)), c to emptyList()), second)
            assertEquals(mapOf(a to listOf(full(2)), b to listOf(full(2)), c to listOf(ihave(2))), waiting())
        }

    @Test
    fun anIdWhoseGraftsEndedCanBeAnnouncedAndGraftedAgain() =
        runTest {
            // Arrange
            val tree = tree()
            tree.announced(a, messageId(1))
            advanceTimeBy(Plumtree.GRAFT_BASE_MILLIS + Plumtree.GRAFT_PER_ANNOUNCER_MILLIS + 1)
            waiting()

            // Act
            tree.announced(a, messageId(1))
            advanceTimeBy(Plumtree.GRAFT_BASE_MILLIS + 1)

            // Assert
            assertEquals(listOf(graft(1)), waiting(a).getValue(a))
        }

    @Test
    fun anArrivedIdIsNotGraftedUntilItIsAnnouncedAgain() =
        runTest {
            // Arrange
            val tree = tree()
            tree.announced(a, messageId(1))

            // Act
            tree.arrived(messageId(1))
            tree.arrived(messageId(2))
            advanceTimeBy(Plumtree.GRAFT_BASE_MILLIS * 2)
            val none = waiting()
            tree.announced(b, messageId(1))
            advanceTimeBy(Plumtree.GRAFT_BASE_MILLIS + 1)

            // Assert
            assertEquals(mapOf(a to emptyList(), b to emptyList(), c to emptyList()), none)
            assertEquals(mapOf(a to emptyList(), b to listOf(graft(1)), c to emptyList()), waiting())
        }

    @Test
    fun anUnlinkedPeerIsNoAnnouncerUntilItAnnouncesAgainAndItsNextLinkStartsEager() =
        runTest {
            // Arrange
            val tree = tree()
            tree.pruned(a)
            tree.announced(a, messageId(1))
            tree.announced(b, messageId(1))

            // Act
            tree.unlinked(a)
            tree.announced(a, messageId(1))
            advanceTimeBy(Plumtree.GRAFT_BASE_MILLIS + 1)
            val first = waiting()
            advanceTimeBy(Plumtree.GRAFT_PER_ANNOUNCER_MILLIS)
            val second = waiting()
            tree.spread(null, parcel(2), EXPIRY)

            // Assert
            assertEquals(mapOf(a to emptyList(), b to listOf(graft(1)), c to emptyList()), first)
            assertEquals(mapOf(a to listOf(graft(1)), b to emptyList(), c to emptyList()), second)
            assertEquals(listOf(full(2)), waiting(a).getValue(a))
        }

    @Test
    fun aGraftIsAnsweredWithTheHeldFrameAndMakesTheLinkEager() =
        runTest {
            // Arrange
            val tree = tree()
            tree.spread(a, parcel(1), EXPIRY)
            tree.pruned(b)
            waiting()

            // Act
            tree.grafted(b, messageId(1))
            tree.grafted(c, messageId(2))
            val answers = waiting()
            tree.spread(null, parcel(2), EXPIRY)

            // Assert
            assertEquals(mapOf(a to emptyList(), b to listOf(full(1)), c to emptyList()), answers)
            assertEquals(listOf(full(2)), waiting(b).getValue(b))
        }

    @Test
    fun aFrameIsHeldForTheCacheTimeAtMost() =
        runTest {
            // Arrange
            val tree = tree()
            tree.spread(a, parcel(1), EXPIRY)
            waiting()

            // Act
            advanceTimeBy(Plumtree.HOLD_MILLIS - 1)
            tree.grafted(b, messageId(1))
            val last = waiting(b)
            advanceTimeBy(1)
            tree.grafted(b, messageId(1))

            // Assert
            assertEquals(listOf(full(1)), last.getValue(b))
            assertEquals(emptyList(), waiting(b).getValue(b))
        }

    @Test
    fun aFrameIsHeldUntilItsExpiryAtMost() =
        runTest {
            // Arrange
            val tree = tree()
            val expiry = SchedulerClock.WALL_START_MILLIS / MILLIS_PER_SECOND + 1
            tree.spread(a, parcel(1), expiry)
            waiting()

            // Act
            advanceTimeBy(MILLIS_PER_SECOND - 1)
            tree.grafted(b, messageId(1))
            val last = waiting(b)
            advanceTimeBy(1)
            tree.grafted(b, messageId(1))

            // Assert
            assertEquals(listOf(full(1)), last.getValue(b))
            assertEquals(emptyList(), waiting(b).getValue(b))
        }

    @Test
    fun theOldestHeldFrameGoesBeyondTheBound() =
        runTest {
            // Arrange
            val tree = tree()
            lanes.close(b)
            lanes.close(c)

            // Act
            (0..Plumtree.HELD_MAX).forEach { tree.spread(a, parcel(it), EXPIRY) }
            tree.spread(a, parcel(1), EXPIRY)
            tree.spread(a, parcel(Plumtree.HELD_MAX + 1), EXPIRY)
            lanes.open(b)
            listOf(0, 1, 2, 3).forEach { tree.grafted(b, messageId(it)) }

            // Assert
            assertEquals(listOf(full(1), full(3)), waiting(b).getValue(b))
        }

    @Test
    fun theOldestPendingIdGoesBeyondTheBound() =
        runTest {
            // Arrange
            val tree = tree()

            // Act
            (0..Plumtree.PENDING_MAX).forEach { tree.announced(a, messageId(it)) }
            advanceTimeBy(Plumtree.GRAFT_BASE_MILLIS + 1)

            // Assert
            val grafts = waiting(a).getValue(a)
            assertEquals(Plumtree.PENDING_MAX, grafts.size)
            assertContentEquals(graft(1), grafts.first())
            assertContentEquals(graft(Plumtree.PENDING_MAX), grafts.last())
        }

    private fun parcel(n: Int) = Parcel(messageId(n), relayed = true, { full(n).toByteArray() }) {}

    private fun full(n: Int) = listOf(BroadcastFrame.TYPE.toByte()) + messageId(n).toList()

    private fun ihave(n: Int) = LinkFrame.Ihave(listOf(messageId(n))).encode().toList()

    private fun graft(n: Int) = LinkFrame.Graft(messageId(n)).encode().toList()

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val EXPIRY = SchedulerClock.WALL_START_MILLIS / MILLIS_PER_SECOND + 3_600
        val PRUNE = LinkFrame.Prune.encode().toList()
    }
}
