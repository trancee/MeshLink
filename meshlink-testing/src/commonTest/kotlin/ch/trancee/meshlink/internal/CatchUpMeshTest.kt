package ch.trancee.meshlink.internal

import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.ReceivedMessage
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.meshLinkConfig
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

/**
 * Catch-up (spec "Catch-up") on a 2 × 3 grid whose nodes hear their row and column neighbors only: a node that was
 * out of range while broadcasts went out gets the last minute's ones through `GRAFT` once it links again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatchUpMeshTest {
    @Test
    fun aLowNodeBackWithinAMinuteGetsTheBroadcastsItMissedOnce() =
        runTest(timeout = 5.minutes) {
            backWithinAMinute(crowd = 0, LOW_OFF)
        }

    @Test
    fun aMediumNodeBackWithinAMinuteGetsTheBroadcastsItMissedOnce() =
        runTest(timeout = 5.minutes) {
            backWithinAMinute(MEDIUM_CROWD, MEDIUM_OFF)
        }

    @Test
    fun aHighNodeBackWithinAMinuteGetsTheBroadcastsItMissedOnce() =
        runTest(timeout = 5.minutes) {
            backWithinAMinute(HIGH_CROWD, HIGH_OFF)
        }

    @Test
    fun aNodeBackAfterAMinuteIsOfferedNoneOfTheBroadcastsItMissed() =
        runTest(timeout = 5.minutes) {
            // Arrange
            val mesh = mesh(crowd = 0)
            val away = leave(mesh)

            // Act
            advanceTimeBy(HOLD_MILLIS)
            mesh.air.move(mesh.platforms[F].radio, away.first, away.second)
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(emptyList(), mesh.inboxes[F])
            val offers = mesh.catchUps(to = F)
            assertTrue(offers.isNotEmpty(), "F got no CATCH_UP")
            assertEquals(offers.map { EMPTY_CATCH_UP }, offers)
        }

    private suspend fun TestScope.backWithinAMinute(
        crowd: Int,
        off: Long,
    ) {
        // Arrange
        val mesh = mesh(crowd)
        val away = leave(mesh)

        // Act
        mesh.air.move(mesh.platforms[F].radio, away.first, away.second)
        advanceTimeBy(RETURN_MILLIS)

        // Assert
        assertEquals(List(GRID) { off }, mesh.platforms.map(::lastGap))
        assertEquals(List(MISSED) { "b$it" }, mesh.inboxes[F].map { it.payload.decodeToString() })
        assertTrue(mesh.ends(F).sumOf { (_, end) -> clearFrames(end).count { it[0] == GRAFT } } > 0, "F sent no GRAFT")
        val perEnd = mesh.ends().map { (_, end) -> clearFrames(end).count { it[0] == CATCH_UP } }
        assertTrue(perEnd.all { it <= 1 }, "CATCH_UP per link end: $perEnd")
        val open = mesh.ends().filter { (_, end) -> end.open }
        assertEquals(open.map { 1 }, open.map { (_, end) -> clearFrames(end).count { it[0] == CATCH_UP } })
    }

    // Settles [mesh], takes F out of range until its links close, and sends [MISSED] broadcasts from the far corner;
    // returns where F was.
    private fun TestScope.leave(mesh: Mesh): Pair<Double, Double> {
        advanceTimeBy(SETTLE_MILLIS)
        mesh.inboxes = mesh.nodes.map { inbox(it) }
        val radio = mesh.platforms[F].radio
        val at = position(F)
        mesh.air.move(radio, AWAY, AWAY)
        advanceTimeBy(GONE_MILLIS)
        repeat(MISSED) {
            mesh.nodes[0].broadcast("b$it".encodeToByteArray())
            advanceTimeBy(GAP_MILLIS)
        }
        return at
    }

    /** Started unsecured nodes on a grid of their own air, with [crowd] advertisers at each cell's center. */
    private suspend fun TestScope.mesh(crowd: Int): Mesh {
        val air = air()
        val platforms =
            List(GRID) { index ->
                air.platform(StandardTestDispatcher(testScheduler)).also {
                    val (x, y) = position(index)
                    air.move(it.radio, x, y)
                }
            }
        CELL_CENTERS.forEach { (x, y) ->
            repeat(crowd) { air.move(advertiseFake(air, null, profile = WireProfile.UNSECURED), x, y) }
        }
        val nodes = platforms.map { node(it) }.onEach { it.start() }
        return Mesh(air, platforms, nodes)
    }

    private fun position(index: Int) = index % COLUMNS * SPACING to index / COLUMNS * SPACING

    private fun TestScope.inbox(node: MeshLink): List<ReceivedMessage> {
        val received = mutableListOf<ReceivedMessage>()
        backgroundScope.launch { node.messages.collect { received += it } }
        return received
    }

    @OptIn(InternalMeshLinkApi::class)
    private fun node(platform: VirtualPlatform): MeshLink =
        MeshLink.create(
            meshLinkConfig(Preset.OfflineChat, SecurityProfile.Unsecured) { meshId = MESH }.build(),
            MeshPlatform.of(platform),
        )

    // Nodes on a grid of [air], and what each received.
    private class Mesh(
        val air: VirtualAir,
        val platforms: List<VirtualPlatform>,
        val nodes: List<MeshLink>,
    ) {
        var inboxes: List<List<ReceivedMessage>> = emptyList()

        // Every link end of each grid node, or of node [of] alone.
        fun ends(of: Int? = null): List<Pair<Int, VirtualLinkEnd>> =
            platforms
                .flatMapIndexed { from, platform -> platform.radio.ends.map { from to it } }
                .filter { (from, _) -> of == null || from == of }

        // The CATCH_UP frames written to node [to], from both sides of each of its links.
        fun catchUps(to: Int): List<List<Byte>> {
            val radio = platforms[to].radio
            val toward = ends().filter { (from, end) -> from != to && end.remote === radio }.map { it.second }
            return toward.flatMap { end -> clearFrames(end).filter { it[0] == CATCH_UP }.map { it.toList() } }
        }
    }

    private companion object {
        const val COLUMNS = 3
        const val GRID = 6
        const val F = 5
        const val SPACING = 25.0
        const val MEDIUM_CROWD = 13
        const val HIGH_CROWD = 60
        const val LOW_OFF = 4_000L
        const val MEDIUM_OFF = 12_000L
        const val HIGH_OFF = 16_000L
        const val MISSED = 5
        const val AWAY = 1_000.0
        const val SETTLE_MILLIS = 60_000L
        const val GONE_MILLIS = 10_000L
        const val GAP_MILLIS = 1_000L
        const val RETURN_MILLIS = 40_000L
        const val HOLD_MILLIS = 60_000L
        const val GRAFT: Byte = 0x0C
        const val CATCH_UP: Byte = 0x0A
        val EMPTY_CATCH_UP = listOf<Byte>(CATCH_UP, 0, 0, 0, 0, 0, 0)

        // Crowds at the centers of both cells, 17.7 from each of their corners: every node hears one or two.
        val CELL_CENTERS = listOf(12.5 to 12.5, 37.5 to 12.5)
    }
}
