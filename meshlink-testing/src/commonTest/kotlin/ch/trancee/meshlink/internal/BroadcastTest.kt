package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.FailureReason
import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.ReceivedMessage
import ch.trancee.meshlink.RejectionReason
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.SendResult
import ch.trancee.meshlink.meshLinkConfig
import ch.trancee.meshlink.vectors.vectorFiles
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Flooded `unsecured` broadcasts across nodes that hear their neighbors only (spec "Flood forwarding"). */
@OptIn(ExperimentalCoroutinesApi::class)
class BroadcastTest {
    @Test
    fun aBroadcastReachesEveryNodeOfATwelveNodeGridOnceAndNoLinkCarriesItTwice() =
        runTest(timeout = 5.minutes) {
            // Arrange: a 4 × 3 grid whose nodes hear their row and column neighbors only.
            val air = air()
            val platforms = grid(air)
            val nodes = platforms.map(::node).onEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)
            val inboxes = nodes.drop(1).map { inbox(it) }

            // Act
            assertIs<SendResult.Accepted>(nodes[0].broadcast(PAYLOAD))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val origin = nodeId(platforms[0])
            inboxes.forEach { inbox ->
                val message = inbox.single()
                assertContentEquals(PAYLOAD, message.payload)
                assertContentEquals(origin, message.sender.toByteArray())
                assertTrue(message.broadcast)
            }
            val ends = platforms.flatMap { it.radio.ends }
            val copies = ends.map { end -> clearFrames(end).count { it[0] == BROADCAST } }
            assertTrue(copies.all { it <= 1 }, "copies per link end: $copies")
        }

    @Test
    fun aBroadcastWithHopLimitTwoReachesExactlyTheNodesWithinTwoHops() =
        runTest(timeout = 5.minutes) {
            // Arrange
            val air = air()
            val nodes = line(air, LINE).map(::node).onEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)
            val inboxes = nodes.drop(1).map { inbox(it) }

            // Act
            nodes[0].broadcast(PAYLOAD, hopLimit = 2)
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(listOf(1, 1, 0, 0), inboxes.map { it.size })
        }

    @Test
    fun anInvalidBroadcastIsRejectedWithNoFrameOnAirAndTheLargestIsDelivered() =
        runTest {
            // Arrange
            val air = air()
            val (alice, bob) = line(air, 2).map(::node).onEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)
            val inbox = inbox(bob)

            // Act
            val rejected =
                listOf(
                    alice.broadcast(ByteArray(PAYLOAD_MAX + 1)),
                    alice.broadcast(PAYLOAD, hopLimit = 0),
                    alice.broadcast(PAYLOAD, hopLimit = 256),
                )
            advanceTimeBy(SETTLE_MILLIS)
            val nothing = inbox.size
            alice.broadcast(ByteArray(PAYLOAD_MAX) { 1 })
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val reasons = listOf(RejectionReason.TooLarge) + List(2) { RejectionReason.InvalidHopLimit }
            assertEquals(reasons, rejected.map { assertIs<SendResult.Rejected>(it).reason })
            assertEquals(0, nothing)
            assertContentEquals(ByteArray(PAYLOAD_MAX) { 1 }, inbox.single().payload)
        }

    @Test
    fun aBroadcastWithoutALinkGoesOutOnTheFirstLinkThenIsSentAndExpires() =
        runTest(timeout = 5.minutes) {
            // Arrange
            val air = air()
            val (a, b) = line(air, 2)
            air.move(b.radio, AWAY, 0.0)
            val (alice, bob) = listOf(a, b).map(::shortLivedNode).onEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)
            val inbox = inbox(bob)
            val status = statuses(alice.broadcast(PAYLOAD))
            advanceTimeBy(SETTLE_MILLIS)
            val alone = status.toList()

            // Act
            air.move(b.radio, SPACING, 0.0)
            advanceTimeBy(EXPIRED_MILLIS)

            // Assert
            assertEquals(listOf<DeliveryStatus>(DeliveryStatus.Queued), alone)
            assertEquals(1, inbox.size)
            assertEquals(listOf(DeliveryStatus.Queued, DeliveryStatus.Sent, DeliveryStatus.Expired), status)
        }

    @Test
    fun aBroadcastWaitingForALinkFailsWithStoppedWhenTheNodeStops() =
        runTest {
            // Arrange
            val alice = node(air().platform(StandardTestDispatcher(testScheduler))).also { it.start() }
            advanceTimeBy(SETTLE_MILLIS)
            val status = statuses(alice.broadcast(PAYLOAD))
            advanceTimeBy(SETTLE_MILLIS)

            // Act
            alice.stop()
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(listOf(DeliveryStatus.Queued, DeliveryStatus.Failed(FailureReason.Stopped)), status)
        }

    @Test
    fun anUnsecuredNodeDeliversKindFourOnlyAndRelaysAnUnknownFloodTypeWithOneHopLess() =
        runTest {
            // Arrange: fake X sends routed-frames.json tcIds 11 (signed), 12 (unsecured) and 44 (type 0xbf).
            val air = air()
            val (alice, bob) = line(air, 2)
            val (aliceNode, bobNode) = listOf(alice, bob).map(::node).onEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)
            val (aliceInbox, bobInbox) = inbox(aliceNode) to inbox(bobNode)
            val fake = connectFake(air, alice)
            fake.write(record(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID)))
            advanceTimeBy(SETTLE_MILLIS)
            val toBob = alice.radio.ends.first { it.open && it.remote == bob.radio }
            val before = clearFrames(toBob).size
            val frames = listOf(11, 12, 44).map { routedFrame(it) }

            // Act
            frames.forEach { fake.write(record(it)) }
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val relayed = frames.drop(1).map { it.copyOf().also { frame -> frame[1] = (frame[1] - 1).toByte() } }
            assertEquals(listOf("Lost dog, black"), aliceInbox.map { it.payload.decodeToString() })
            assertEquals(aliceInbox.map { it.payload.toList() }, bobInbox.map { it.payload.toList() })
            val flooded = clearFrames(toBob).drop(before).filter { it[0].toInt() and FLOOD_MASK == FLOOD_TYPES }
            assertEquals(relayed.map { it.toHexString() }, flooded.map { it.toHexString() })
        }

    @Test
    fun aClassicalRelayRelaysNoBroadcastButAnUnknownFloodTypeWithOneHopLess() =
        runTest {
            // Arrange: fake X sends routed-frames.json tcIds 11 (signed), 12 (unsecured) and 44 (type 0xbf).
            val air = air()
            val relay = air.platform(StandardTestDispatcher(testScheduler))
            MeshLink
                .create(
                    meshLinkConfig(Preset.OfflineChat, SecurityProfile.Classical) { meshId = MESH }.build(),
                    meshPlatform(relay),
                ).start()
            val (x, y) = List(2) { FakePeer(WireProfile.CLASSICAL, TestClock(testScheduler)) }
            val xLink = connectFake(air, relay)
            val xTransport = initiate(x, xLink, relay.radio.ends.last())
            val yLink = connectFake(air, relay)
            val yEnd = relay.radio.ends.last()
            val yTransport = initiate(y, yLink, yEnd)
            val frames = listOf(11, 12, 44).map { routedFrame(it) }

            // Act
            frames.forEach { xLink.write(seal(xTransport, it)) }
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val relayed = frames.last().copyOf().also { it[1] = (it[1] - 1).toByte() }
            assertEquals(listOf(relayed.toHexString()), sealedFrames(yEnd, yTransport).map { it.toHexString() })
        }

    /** A 4 × 3 grid of platforms [SPACING] apart. */
    private fun TestScope.grid(air: VirtualAir): List<VirtualPlatform> =
        List(GRID_COLUMNS * GRID_ROWS) { index ->
            air.platform(StandardTestDispatcher(testScheduler)).also {
                air.move(it.radio, index % GRID_COLUMNS * SPACING, index / GRID_COLUMNS * SPACING)
            }
        }

    /** [count] platforms in a line along x, [SPACING] apart from the origin. */
    private fun TestScope.line(
        air: VirtualAir,
        count: Int,
    ): List<VirtualPlatform> =
        List(count) { index ->
            air.platform(StandardTestDispatcher(testScheduler)).also { air.move(it.radio, index * SPACING, 0.0) }
        }

    /** A stopped `unsecured` node whose messages live 120 s. */
    private fun shortLivedNode(platform: VirtualPlatform): MeshLink =
        MeshLink.create(
            meshLinkConfig(Preset.ProximityDiscovery, SecurityProfile.Unsecured) { meshId = MESH }.build(),
            meshPlatform(platform),
        )

    @OptIn(InternalMeshLinkApi::class)
    private fun meshPlatform(platform: VirtualPlatform): MeshPlatform = MeshPlatform.of(platform)

    /** Every message [node] receives from now on. */
    private fun TestScope.inbox(node: MeshLink): List<ReceivedMessage> {
        val received = mutableListOf<ReceivedMessage>()
        backgroundScope.launch { node.messages.collect { received += it } }
        return received
    }

    /** Every status of the message [result] accepted from now on. */
    private fun TestScope.statuses(result: SendResult): List<DeliveryStatus> {
        val seen = mutableListOf<DeliveryStatus>()
        backgroundScope.launch { assertIs<SendResult.Accepted>(result).message.status.toList(seen) }
        return seen
    }

    /** routed-frames.json [tcId], expiring a minute from now, with a `message_id` of its own. */
    private fun TestScope.routedFrame(tcId: Int): ByteArray {
        val json = vectorFiles.getValue("vectors/routed-frames.json")()
        val encoded = Regex("\"tcId\":$tcId,.*?\"encoded\":\"([0-9a-f]+)\"").find(json)!!.groupValues[1]
        val frame = encoded.hexToByteArray()
        frame[MESSAGE_ID_LAST] = tcId.toByte()
        val expiry = (TestClock.WALL_CLOCK_START_MILLIS + testScheduler.currentTime) / MILLIS_PER_SECOND + MINUTE
        repeat(EXPIRY_BYTES) { frame[EXPIRY_OFFSET + it] = (expiry shr (BITS * (EXPIRY_BYTES - 1 - it))).toByte() }
        return frame
    }

    private companion object {
        val PAYLOAD = "to everyone".encodeToByteArray()
        const val PAYLOAD_MAX = 1_024
        const val BROADCAST: Byte = 0x80.toByte()
        const val GRID_COLUMNS = 4
        const val GRID_ROWS = 3
        const val LINE = 5
        const val SPACING = 25.0
        const val AWAY = 1_000.0
        const val SETTLE_MILLIS = 60_000L

        /** Past the 120-second lifetime and the 30-second skew allowance. */
        const val EXPIRED_MILLIS = 151_000L
        const val MILLIS_PER_SECOND = 1_000L
        const val MINUTE = 60L
        const val MESSAGE_ID_LAST = 13
        const val EXPIRY_OFFSET = 14
        const val EXPIRY_BYTES = 4
        const val BITS = 8
        const val FLOOD_MASK = 0xc0
        const val FLOOD_TYPES = 0x80
    }
}
