package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.SendResult
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
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
import kotlin.time.Duration.Companion.minutes

/**
 * Routed unicast across nodes in a line, out of each other's range but for their neighbors (spec
 * "Unicast forwarding").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutedUnicastTest {
    @Test
    fun inTheLowClassAMessageFloodsFiveHopsArrivesOnceIntactAndIsSentThenExpired() =
        runTest(timeout = 5.minutes) {
            // Arrange: F links to A first, then moves to the far end of the line.
            val air = air()
            val platforms = line(air, shortLived = true)
            val (nodes, far) = platforms.map(::shortLivedNode).let { it to it.last() }
            nodes.forEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)
            val f = nodes[0].peerOn(platforms.last())
            air.move(platforms.last().radio, (LINE - 1) * SPACING, 0.0)
            advanceTimeBy(SETTLE_MILLIS)
            val inbox = inbox(far)

            // Act
            val status = statuses(nodes[0].send(f, PAYLOAD))
            advanceTimeBy(EXPIRED_MILLIS)

            // Assert
            assertEquals(1, inbox.size)
            assertContentEquals(PAYLOAD, inbox.single())
            assertEquals(listOf(DeliveryStatus.Queued, DeliveryStatus.Sent, DeliveryStatus.Expired), status)
        }

    @Test
    fun aMediumClassOriginFloodsThreeHopsAndReachesFartherByRouteRequest() =
        runTest(timeout = 5.minutes) {
            // Arrange: thirteen advertisers heard by A alone move it up to medium.
            val air = air()
            val platforms = line(air, shortLived = false)
            val nodes = platforms.map(::shortLivedNode).onEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)
            repeat(MEDIUM_UP_HEARD) { air.move(advertiseFake(air, capability = null), -FAKE_DISTANCE, 0.0) }
            advanceTimeBy(SWITCH_MILLIS)
            val d = nodes[2].peerOn(platforms[3])
            val f = nodes[4].peerOn(platforms[5])
            val (dInbox, fInbox) = inbox(nodes[3]) to inbox(nodes[5])

            // Act
            nodes[0].send(d, PAYLOAD)
            nodes[0].send(f, PAYLOAD)
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(MEDIUM_OFF_MILLIS, lastGap(platforms[0]))
            assertEquals(1, dInbox.size)
            assertEquals(1, fInbox.size)
        }

    @Test
    fun aHeldMessageIsTaggedForTheEpochItIsWrittenIn() =
        runTest(timeout = 5.minutes) {
            // Arrange: past two epochs, the tag of the epoch the message was sent in is no longer F's.
            val air = air()
            val (a, f) = List(2) { air.platform(StandardTestDispatcher(testScheduler)) }
            val (alice, frank) = listOf(a, f).map(::node).onEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)
            val frankId = alice.peerOn(f)
            val inbox = inbox(frank)
            air.move(f.radio, AWAY, 0.0)
            val status = statuses(alice.send(frankId, PAYLOAD))
            advanceTimeBy(TWO_EPOCHS_MILLIS)

            // Act
            air.move(f.radio, 0.0, 0.0)
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(1, inbox.size)
            assertEquals(listOf(DeliveryStatus.Held, DeliveryStatus.Sent), status)
        }

    @Test
    fun aClassicalRelayForwardsSecuredMessagesWithOneHopLessAndDropsUnsecuredOnes() =
        runTest {
            // Arrange: fake X sends routed-frames.json tcIds 1 (IK initiator), 2 (transport) and 3 (unsecured).
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
            val frames = (1..3).map { routedFrame(it) }

            // Act
            frames.forEach { xLink.write(seal(xTransport, it)) }
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val relayed = frames.take(2).map { it.copyOf().also { frame -> frame[1] = (frame[1] - 1).toByte() } }
            assertEquals(relayed.map { it.toHexString() }, sealedFrames(yEnd, yTransport).map { it.toHexString() })
        }

    @Test
    fun aDestinationDropsAMessageWhoseCommitmentDoesNotOpenAndDeliversTheIntactCopy() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val node = node(alice).also { it.start() }
            val inbox = inbox(node)
            val fake = connectFake(air, alice)
            fake.write(record(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID)))
            advanceTimeBy(SETTLE_MILLIS)
            val nowSeconds = (TestClock.WALL_CLOCK_START_MILLIS + testScheduler.currentTime) / MILLIS_PER_SECOND
            val sender = UnsecuredMessaging(SodiumCryptoProvider, FAKE_ID)
            val draft = sender.draft(nodeId(alice), PAYLOAD, (nowSeconds + MINUTE).toUInt())
            val intact = sender.frame(draft, Derivations.epoch(nowSeconds), hopLimit = 1)
            val tampered = intact.copyOf().also { it[COMMITMENT_OFFSET] = (it[COMMITMENT_OFFSET] + 1).toByte() }

            // Act
            fake.write(record(tampered) + record(intact))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(listOf(PAYLOAD.toList()), inbox.map { it.toList() })
        }

    /** [LINE] platforms [SPACING] apart along x, but the last beside the first at the origin. */
    private fun TestScope.line(
        air: VirtualAir,
        shortLived: Boolean,
    ): List<VirtualPlatform> =
        List(LINE) { air.platform(StandardTestDispatcher(testScheduler)) }.onEachIndexed { index, platform ->
            val beside = shortLived && index == LINE - 1
            air.move(platform.radio, if (beside) 0.0 else index * SPACING, 0.0)
        }

    /** A stopped `unsecured` node whose messages live 120 s. */
    private fun shortLivedNode(platform: VirtualPlatform): MeshLink =
        MeshLink.create(
            meshLinkConfig(Preset.ProximityDiscovery, SecurityProfile.Unsecured) { meshId = MESH }.build(),
            meshPlatform(platform),
        )

    @OptIn(InternalMeshLinkApi::class)
    private fun meshPlatform(platform: VirtualPlatform): MeshPlatform = MeshPlatform.of(platform)

    /** The NodeId of this node's linked peer on [platform]. */
    private fun MeshLink.peerOn(platform: VirtualPlatform): NodeId =
        peers.value.single { it.nodeId.toByteArray().contentEquals(nodeId(platform)) }.nodeId

    /** Every payload [node] receives from now on. */
    private fun TestScope.inbox(node: MeshLink): List<ByteArray> {
        val received = mutableListOf<ByteArray>()
        backgroundScope.launch { node.messages.collect { received += it.payload } }
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
        val PAYLOAD = "five hops".encodeToByteArray()
        const val LINE = 6
        const val SPACING = 25.0
        const val AWAY = 1_000.0
        const val FAKE_DISTANCE = 20.0
        const val SETTLE_MILLIS = 60_000L
        const val SWITCH_MILLIS = 120_000L
        const val MEDIUM_UP_HEARD = 13
        const val MEDIUM_OFF_MILLIS = 12_000L

        /** Past the 120-second lifetime and the 30-second skew allowance. */
        const val EXPIRED_MILLIS = 151_000L

        /** Two 300-second epochs and some. */
        const val TWO_EPOCHS_MILLIS = 660_000L
        const val MILLIS_PER_SECOND = 1_000L
        const val MINUTE = 60L
        const val MESSAGE_ID_LAST = 13
        const val EXPIRY_OFFSET = 14
        const val EXPIRY_BYTES = 4
        const val BITS = 8

        /** A `MESSAGE` frame's commitment follows its 34-byte unicast header. */
        const val COMMITMENT_OFFSET = 34
    }
}
