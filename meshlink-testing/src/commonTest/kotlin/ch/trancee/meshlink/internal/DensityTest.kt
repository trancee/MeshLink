package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DiagnosticEvent
import ch.trancee.meshlink.MeshLink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Density classes on a growing and shrinking mesh (ADR 0008, "Density adaptation"). */
@OptIn(ExperimentalCoroutinesApi::class)
class DensityTest {
    private class Stage(
        val nodes: Int,
        val minutes: Int,
        val offMillis: Long,
        val links: Int,
    )

    private class Seen(
        val offMillis: List<Long?>,
        val settledPeers: List<Int>,
    )

    @Test
    fun eachNodeFollowsTheDensityItHearsWithItsScanScheduleAndLinkBudget() =
        runTest(timeout = 10.minutes) {
            // Arrange
            val air = air()
            val platforms = mutableListOf<VirtualPlatform>()
            val nodes = mutableListOf<MeshLink>()
            val caps = mutableListOf<List<DiagnosticEvent>>()

            // Act
            val seen =
                STAGES.map { stage ->
                    while (nodes.size < stage.nodes) {
                        platforms += air.platform(StandardTestDispatcher(testScheduler))
                        nodes += node(platforms.last()).also { it.start() }
                        diagnostics(nodes.last()).let { seen -> caps += seen }
                    }
                    (nodes.size - 1 downTo stage.nodes).forEach { launch { nodes.removeAt(it).stop() } }
                    observe(platforms.take(stage.nodes), nodes, stage)
                }

            // Assert
            STAGES.zip(seen).forEach { (stage, it) ->
                assertEquals(List(stage.nodes) { stage.offMillis }, it.offMillis, "${stage.nodes} nodes: scan off time")
                assertTrue(it.settledPeers.max() <= stage.links, "${stage.nodes} nodes: over the budget")
            }
            val low = STAGES.zip(seen).filter { (stage, _) -> stage.links == LOW_LINKS }
            assertTrue(low.any { (_, it) -> LOW_LINKS in it.settledPeers }, "the low budget is never used")
            val refusals = caps.flatten().filterIsInstance<DiagnosticEvent.LinkCapReached>()
            val budgets = STAGES.map { it.links }.toSet()
            assertEquals(budgets, refusals.map { it.cap }.toSet(), "the budgets refusals report")
        }

    @Test
    fun aNodeHoldingItsWholeBudgetOnceItsClassGoesDownStillFreesALinkForNewcomers() =
        runTest {
            // Arrange
            val air = air()
            val platforms = List(CLUSTER) { air.platform(StandardTestDispatcher(testScheduler)) }
            val nodes = platforms.map { node(it) }
            nodes.forEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)
            val settled = platforms.map { lastGap(it) to freed(it) } + nodes.map { it.peers.value.size to 0 }

            // Act
            repeat(HEARD) { advertiseFake(air, capability = null) }
            advanceTimeBy(SWITCH_MILLIS)

            // Assert
            val low = List(CLUSTER) { LOW_OFF_MILLIS to 0 } + List(CLUSTER) { CLUSTER - 1 to 0 }
            assertEquals(low, settled, "a full mesh of six under the low budget")
            assertEquals(List(CLUSTER) { MEDIUM_OFF_MILLIS }, platforms.map { lastGap(it) })
            assertTrue(platforms.all { freed(it) > 0 }, "a node that never freed a link")
        }

    @Test
    fun aLinkedPeerNoLongerHeardStillCountsTowardTheDensity() =
        runTest {
            // Arrange
            val air = air()
            val (node, peer) = List(2) { air.platform(StandardTestDispatcher(testScheduler)) }
            listOf(node, peer).map { node(it) }.forEach { it.start() }
            advanceTimeBy(ROTATED_MILLIS)

            // Act
            peer.radio.stopAdvertising()
            repeat(MEDIUM_UP_HEARD - 1) { advertiseFake(air, capability = null) }
            advanceTimeBy(SWITCH_MILLIS)

            // Assert: twelve heard and one linked peer pass the 12.5 that moves a node up to medium.
            assertEquals(MEDIUM_OFF_MILLIS, lastGap(node))
        }

    private fun freed(platform: VirtualPlatform): Int = platform.radio.ends.count { closes(it) == FREED }

    /** Samples each node's peers every second, past the switch dwell and the held timer, and its last scan gap. */
    private fun TestScope.observe(
        platforms: List<VirtualPlatform>,
        nodes: List<MeshLink>,
        stage: Stage,
    ): Seen {
        val peers = mutableListOf<Int>()
        repeat(stage.minutes * SECONDS_PER_MINUTE) { second ->
            advanceTimeBy(MILLIS_PER_SECOND)
            if (second >= SETTLING_SECONDS) peers += nodes.map { it.peers.value.size }
        }
        return Seen(platforms.map(::lastGap), peers)
    }

    private companion object {
        val FREED = listOf(CloseReason.NORMAL)
        const val SECONDS_PER_MINUTE = 60
        const val MILLIS_PER_SECOND = 1_000L
        const val SETTLING_SECONDS = 90
        const val LOW_LINKS = 6
        const val LOW_OFF_MILLIS = 4_000L
        const val MEDIUM_OFF_MILLIS = 12_000L

        /** A full mesh of five links each: under the low budget, the whole medium one. */
        const val CLUSTER = 6
        const val HEARD = 25
        const val SETTLE_MILLIS = 120_000L
        const val SWITCH_MILLIS = 120_000L

        /** Past each node's first re-advertisement, its next one 300 s later. */
        const val ROTATED_MILLIS = 40_000L
        const val MEDIUM_UP_HEARD = 13
        val STAGES =
            listOf(
                Stage(nodes = 2, minutes = 2, offMillis = LOW_OFF_MILLIS, links = LOW_LINKS),
                Stage(nodes = 8, minutes = 3, offMillis = LOW_OFF_MILLIS, links = LOW_LINKS),
                Stage(nodes = 30, minutes = 3, offMillis = MEDIUM_OFF_MILLIS, links = 5),
                Stage(nodes = 70, minutes = 4, offMillis = 16_000, links = 5),
                Stage(nodes = 30, minutes = 4, offMillis = MEDIUM_OFF_MILLIS, links = 5),
                Stage(nodes = 8, minutes = 5, offMillis = LOW_OFF_MILLIS, links = LOW_LINKS),
                Stage(nodes = 2, minutes = 3, offMillis = LOW_OFF_MILLIS, links = LOW_LINKS),
            )
    }
}
