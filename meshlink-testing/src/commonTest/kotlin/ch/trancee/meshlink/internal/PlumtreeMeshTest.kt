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
 * Broadcasts spread by Plumtree (spec "Plumtree") over a 4 × 3 grid whose nodes hear their row and column
 * neighbors only, and crowds of advertisers that set their density class.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlumtreeMeshTest {
    @Test
    fun aMediumGridDeliversEveryBroadcastOnceWithFewerFullCopiesThanALowOne() =
        runTest(timeout = 5.minutes) {
            // Arrange
            val low = mesh(SecurityProfile.Unsecured, crowds = emptyList())
            val medium = mesh(SecurityProfile.Unsecured, EVERY_NODE)

            // Act
            listOf(low, medium).forEach { spreadAll(it) }

            // Assert
            listOf(low, medium).forEach { assertEveryBroadcastArrivedOnce(it) }
            assertEquals(List(GRID) { LOW_OFF }, low.gaps())
            assertEquals(List(GRID) { MEDIUM_OFF }, medium.gaps())
            assertEquals(listOf(0, 0, 0), listOf(IHAVE, GRAFT, PRUNE).map { low.count(it) })
            assertTrue(medium.count(PRUNE) > 0, "no PRUNE")
            assertTrue(medium.count(IHAVE) > 0, "no IHAVE")
            val copies = listOf(low, medium).map { it.count(BROADCAST) }
            assertTrue(copies[1] < copies[0], "full copies, low then medium: $copies")
        }

    @Test
    fun aClassicalMediumGridDeliversEveryBroadcastOnce() =
        runTest(timeout = 5.minutes) { deliversEveryBroadcastOnce(SecurityProfile.Classical) }

    @Test
    fun aPostQuantumMediumGridDeliversEveryBroadcastOnce() =
        runTest(timeout = 5.minutes) { deliversEveryBroadcastOnce(SecurityProfile.PostQuantum) }

    @Test
    fun aGridOfMediumAndLowNodesDeliversEveryBroadcastOnce() =
        runTest(timeout = 5.minutes) {
            // Arrange: crowds beside the left column alone.
            val mesh = mesh(SecurityProfile.Unsecured, LEFT_COLUMN)

            // Act
            spreadAll(mesh)

            // Assert
            assertEveryBroadcastArrivedOnce(mesh)
            val gaps = List(GRID) { if (it % COLUMNS == 0) MEDIUM_OFF else LOW_OFF }
            assertEquals(gaps, mesh.gaps())
        }

    @Test
    fun aRelayOfTheTreeLeavingIsBridgedByGraftsOverLazyLinks() =
        runTest(timeout = 5.minutes) {
            // Arrange: the tree settles; a node it relays through leaves.
            val mesh = mesh(SecurityProfile.Unsecured, EVERY_NODE)
            advanceTimeBy(SETTLE_MILLIS)
            val inboxes = mesh.nodes.map { inbox(it) }
            repeat(WARM_UP) { broadcast(mesh, 0, it) }
            val before = mesh.copiesPerPair()
            broadcast(mesh, 0, WARM_UP)
            val after = mesh.copiesPerPair()
            val tree = after.filter { (pair, copies) -> copies > before.getOrElse(pair) { 0 } }.keys
            val relay = (1 until GRID).first { node -> tree.count { node in it } > 1 }
            mesh.air.move(mesh.platforms[relay].radio, AWAY, AWAY)
            advanceTimeBy(SETTLE_MILLIS)
            val grafts = mesh.count(GRAFT)

            // Act
            (WARM_UP + 1..WARM_UP + AFTER).forEach { broadcast(mesh, 0, it) }
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val sent = (0..WARM_UP + AFTER).map(::payload)
            assertEquals(emptyList(), inboxes[0])
            inboxes.forEachIndexed { node, inbox ->
                if (node != 0 && node != relay) assertEquals(sent, inbox.map { it.text() }, "node $node")
            }
            assertTrue(mesh.count(GRAFT) > grafts, "no GRAFT after relay $relay left")
        }

    @Test
    fun aLazyLinkThatClosesComesBackEager() =
        runTest(timeout = 5.minutes) {
            // Arrange: a triangle in one crowd; a first broadcast from A prunes the link between B and C.
            val air = air()
            val platforms = List(3) { air.platform(StandardTestDispatcher(testScheduler)) }
            TRIANGLE.forEachIndexed { index, (x, y) -> air.move(platforms[index].radio, x, y) }
            repeat(CROWD) { air.move(advertiseFake(air, capability = null), CENTROID.first, CENTROID.second) }
            val nodes = platforms.map { node(it, SecurityProfile.Unsecured) }.onEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)
            val (_, b, c) = platforms
            nodes[0].broadcast(payload(0).encodeToByteArray())
            advanceTimeBy(GAP_MILLIS)
            val pruned = between(b, c).sumOf { end -> clearFrames(end).count { it[0] == PRUNE } }

            // Act
            between(b, c).first().drop()
            advanceTimeBy(SETTLE_MILLIS)
            nodes[0].broadcast(payload(1).encodeToByteArray())
            advanceTimeBy(GAP_MILLIS)

            // Assert
            assertTrue(pruned > 0, "no PRUNE between B and C")
            val again = between(b, c)
            assertTrue(again.isNotEmpty(), "B and C did not link again")
            assertTrue(again.any { end -> clearFrames(end).any { it[0] == BROADCAST } }, "the new link stayed lazy")
        }

    // The open link ends between [one] and [other], either way.
    private fun between(
        one: VirtualPlatform,
        other: VirtualPlatform,
    ): List<VirtualLinkEnd> =
        one.radio.ends.filter { it.open && it.remote === other.radio } +
            other.radio.ends.filter { it.open && it.remote === one.radio }

    private suspend fun TestScope.deliversEveryBroadcastOnce(profile: SecurityProfile) {
        // Arrange
        val mesh = mesh(profile, EVERY_NODE)

        // Act
        spreadAll(mesh)

        // Assert
        assertEveryBroadcastArrivedOnce(mesh)
        assertEquals(List(GRID) { MEDIUM_OFF }, mesh.gaps())
    }

    /** Started [profile] nodes on a grid of their own air, with [CROWD] advertisers at each of [crowds]. */
    private suspend fun TestScope.mesh(
        profile: SecurityProfile,
        crowds: List<Pair<Double, Double>>,
    ): Mesh {
        val air = air()
        val platforms =
            List(GRID) { index ->
                air.platform(StandardTestDispatcher(testScheduler)).also {
                    air.move(it.radio, index % COLUMNS * SPACING, index / COLUMNS * SPACING)
                }
            }
        val wire = WIRE.getValue(profile)
        crowds.forEach { (x, y) -> repeat(CROWD) { air.move(advertiseFake(air, null, profile = wire), x, y) } }
        val nodes = platforms.map { node(it, profile) }.onEach { it.start() }
        return Mesh(air, platforms, nodes)
    }

    // Settles [mesh], then broadcasts [BROADCASTS] messages, each from the next node in turn, and lets them spread.
    private fun TestScope.spreadAll(mesh: Mesh) {
        advanceTimeBy(SETTLE_MILLIS)
        mesh.inboxes = mesh.nodes.map { inbox(it) }
        repeat(BROADCASTS) { broadcast(mesh, it % GRID, it) }
        advanceTimeBy(SETTLE_MILLIS)
    }

    private fun TestScope.broadcast(
        mesh: Mesh,
        origin: Int,
        n: Int,
    ) {
        mesh.nodes[origin].broadcast(payload(n).encodeToByteArray())
        advanceTimeBy(GAP_MILLIS)
    }

    private fun assertEveryBroadcastArrivedOnce(mesh: Mesh) {
        mesh.inboxes.forEachIndexed { node, inbox ->
            val expected = (0 until BROADCASTS).filter { it % GRID != node }.map(::payload)
            assertEquals(expected, inbox.map { it.text() }.sortedBy { it.drop(1).toInt() }, "node $node")
        }
    }

    private fun TestScope.inbox(node: MeshLink): List<ReceivedMessage> {
        val received = mutableListOf<ReceivedMessage>()
        backgroundScope.launch { node.messages.collect { received += it } }
        return received
    }

    @OptIn(InternalMeshLinkApi::class)
    private fun node(
        platform: VirtualPlatform,
        profile: SecurityProfile,
    ): MeshLink =
        MeshLink.create(
            meshLinkConfig(Preset.OfflineChat, profile) { meshId = MESH }.build(),
            MeshPlatform.of(platform),
        )

    private fun payload(n: Int) = "b$n"

    private fun ReceivedMessage.text() = payload.decodeToString()

    // Nodes on a grid of [air], and what each received.
    private class Mesh(
        val air: VirtualAir,
        val platforms: List<VirtualPlatform>,
        val nodes: List<MeshLink>,
    ) {
        var inboxes: List<List<ReceivedMessage>> = emptyList()

        fun gaps(): List<Long?> = platforms.map(::lastGap)

        // The frames of [type] every link end wrote.
        fun count(type: Byte): Int = ends().sumOf { (_, end) -> clearFrames(end).count { it[0] == type } }

        // The full broadcast copies written between each pair of grid nodes, either way.
        fun copiesPerPair(): Map<Set<Int>, Int> =
            ends()
                .mapNotNull { (from, end) ->
                    val to = platforms.indexOfFirst { it.radio === end.remote }
                    if (to < 0) null else setOf(from, to) to clearFrames(end).count { it[0] == BROADCAST }
                }.groupBy({ it.first }, { it.second })
                .mapValues { (_, copies) -> copies.sum() }

        private fun ends() = platforms.flatMapIndexed { from, platform -> platform.radio.ends.map { from to it } }
    }

    private companion object {
        const val COLUMNS = 4
        const val GRID = 12
        const val SPACING = 25.0
        const val CROWD = 13
        const val BROADCASTS = 20
        const val WARM_UP = 3
        const val AFTER = 3
        const val AWAY = 1_000.0
        const val SETTLE_MILLIS = 60_000L
        const val GAP_MILLIS = 5_000L
        const val LOW_OFF = 4_000L
        const val MEDIUM_OFF = 12_000L
        const val BROADCAST: Byte = 0x80.toByte()
        const val IHAVE: Byte = 0x0B
        const val GRAFT: Byte = 0x0C
        const val PRUNE: Byte = 0x0D

        val WIRE =
            mapOf(
                SecurityProfile.Unsecured to WireProfile.UNSECURED,
                SecurityProfile.Classical to WireProfile.CLASSICAL,
                SecurityProfile.PostQuantum to WireProfile.POST_QUANTUM,
            )

        // A triangle whose corners all hear each other, and its crowd's spot, 11.2 at most from each corner.
        val TRIANGLE = listOf(0.0 to 0.0, 20.0 to 0.0, 10.0 to 15.0)
        val CENTROID = 10.0 to 5.0

        // Crowds at the centers of four cells, 17.7 from each of their corners: every node hears one or two.
        val EVERY_NODE = listOf(12.5 to 12.5, 62.5 to 12.5, 12.5 to 37.5, 62.5 to 37.5)

        // Crowds left of the left column, 15 from its top and bottom nodes and 29.2 from its middle one.
        val LEFT_COLUMN = listOf(-15.0 to 0.0, -15.0 to 50.0)
    }
}
