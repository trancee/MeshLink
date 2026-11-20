package ch.trancee.meshlink.internal

import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.ReceivedMessage
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import ch.trancee.meshlink.meshLinkConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Nodes of different density classes on one link (ADR 0008, "Density adaptation"): a class changes the link budget
 * and scan schedule only, so a low and a medium node exchange every link frame type.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalMeshLinkApi::class)
class MixedDensityTest {
    @Test
    fun aLowAndAMediumNodeExchangeEveryCleartextFrameTypeAndTheMediumOneNamesTheLowOneToItsOtherPeer() =
        runTest {
            // Arrange: the edge hears only the bridge; the bridge and its other peer hear a crowd of 13.
            val air = air()
            val platforms = List(3) { air.platform(StandardTestDispatcher(testScheduler), coc = true) }
            val (edge, bridge, other) = platforms
            air.move(edge.radio, EDGE_X, 0.0)
            air.move(other.radio, OTHER_X, 0.0)
            repeat(CROWD) { air.move(advertiseFake(air, capability = null), CROWD_X, 0.0) }
            val nodes = platforms.map { node(it) }
            nodes.forEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)
            val gaps = platforms.map { lastGap(it) }
            val received = mutableListOf<ReceivedMessage>()
            nodes.forEach { node -> backgroundScope.launch { node.messages.collect { received += it } } }
            val bridgeId =
                nodes[0]
                    .peers.value
                    .single()
                    .nodeId
            val edgeId =
                nodes[1]
                    .peers.value
                    .single { it.nodeId.toByteArray().contentEquals(nodeId(edge)) }
                    .nodeId

            // Act
            nodes[0].send(bridgeId, ByteArray(LONG))
            nodes[1].send(edgeId, ByteArray(LONG))
            advanceTimeBy(SETTLE_MILLIS)
            nodes[0].stop()
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(listOf(LOW_OFF, MEDIUM_OFF, MEDIUM_OFF), gaps)
            assertEquals(listOf(LONG, LONG), received.map { it.payload.size })
            val edgeWrote = edge.radio.ends.flatMap { wrote(it) }
            val bridgeWrote =
                bridge.radio.ends
                    .filter { it.remote === edge.radio }
                    .flatMap { wrote(it) }
            assertEquals(CLEARTEXT_TYPES, (edgeWrote + bridgeWrote).map { it::class.simpleName }.toSet())
            assertTrue(edgeWrote.any { it is LinkFrame.Fragment }, "edge")
            assertTrue(bridgeWrote.any { it is LinkFrame.Fragment }, "bridge")
            val passedOn =
                bridge.radio.ends
                    .filter { it.remote === other.radio }
                    .flatMap { wrote(it) }
                    .filterIsInstance<LinkFrame.NeighborList>()
            val edgeOwn = edgeWrote.filterIsInstance<LinkFrame.NeighborList>().map { it.ownId }
            assertTrue(passedOn.any { list -> list.neighbors.any { id -> edgeOwn.any { it.contentEquals(id) } } })
        }

    @Test
    fun aMediumNodeSendsItsHandshakeRekeysAndNextResolvingKeyToAPeer() =
        runTest(timeout = DAY_TIMEOUT) {
            // Arrange
            val air = air()
            val bridge = air.platform(StandardTestDispatcher(testScheduler))
            repeat(CROWD) { gatedFake(air) }
            gatedNode(bridge).start()
            val clock = TestClock(testScheduler)
            val fake = FakePeer(WireProfile.CLASSICAL, clock, MESH_KEY)
            val link = connectFake(air, bridge)
            val end = bridge.radio.ends.single()
            val transport = initiateGranting(fake, link, end)

            // Act
            advanceTimeBy(25.hours)

            // Assert
            assertEquals(MEDIUM_OFF, lastGap(bridge))
            assertEquals(2, handshakeIn(clearFrames(end).first()).step)
            val types = Sealed(transport, end, clock).all().map { it::class.simpleName }.toSet()
            assertContains(types, LinkFrame.Rekey::class.simpleName)
            assertContains(types, LinkFrame.ResolvingKeyUpdate::class.simpleName)
        }

    /** The frames [end] wrote on its link and on any CoC channel it moved to. */
    private fun wrote(end: VirtualLinkEnd): List<LinkFrame> = frames(end) + end.channels.flatMap { frames(it.written) }

    /** A raw radio of [MESH] gated with [MESH_KEY], advertising a fresh tag every minute so it stays acceptable. */
    private fun TestScope.gatedFake(air: VirtualAir) {
        val radio = air.radio()
        val discovery = Discovery(SodiumCryptoProvider, MESH.encodeToByteArray(), WireProfile.CLASSICAL, MESH_KEY)
        val clock = TestClock(testScheduler)
        backgroundScope.launch {
            while (true) {
                radio.startAdvertising(discovery.advertisement(Derivations.epoch(clock.wallClockMillis() / MILLIS)))
                delay(1.minutes)
            }
        }
    }

    private fun gatedNode(platform: PlatformPort): MeshLink =
        MeshLink.create(
            meshLinkConfig(Preset.OfflineChat, SecurityProfile.Classical) {
                meshId = MESH
                meshKey = MESH_KEY
            }.build(),
            MeshPlatform.of(platform),
        )

    private companion object {
        val DAY_TIMEOUT = 5.minutes
        val MESH_KEY = ByteArray(32) { 0x11 }
        val CLEARTEXT_TYPES =
            setOf(
                "PlainHello",
                "Credit",
                "CatchUp",
                "NeighborList",
                "BearerSwitch",
                "Shuffle",
                "ShuffleReply",
                "Fragment",
                "Close",
            )
        const val SETTLE_MILLIS = 240_000L
        const val CROWD = 13
        const val CROWD_X = -20.0
        const val EDGE_X = 25.0
        const val OTHER_X = -10.0
        const val LONG = 4_000
        const val LOW_OFF = 4_000L
        const val MEDIUM_OFF = 12_000L
        const val MILLIS = 1_000L
    }
}
