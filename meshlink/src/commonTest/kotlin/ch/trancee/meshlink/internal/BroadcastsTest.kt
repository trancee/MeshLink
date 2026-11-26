package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.FailureReason
import ch.trancee.meshlink.MeshState
import ch.trancee.meshlink.OutgoingMessage
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.ReceivedMessage
import ch.trancee.meshlink.RejectionReason
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.SendResult
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import ch.trancee.meshlink.meshLinkConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// This node's broadcasts: validation, frames, status, capacity and the first link.
@OptIn(ExperimentalCoroutinesApi::class)
class BroadcastsTest {
    private val a = nodeId(1)
    private val b = nodeId(2)
    private val capacity = Capacity()
    private val lanes = Lanes()
    private val received = mutableListOf<ReceivedMessage>()
    private val codec = broadcastCodec(nodeId = SELF, random = ::nextId)
    private val floods = Floods(lanes, codec, lowTree(lanes), ::tombstoneCommitment, holds = true) { received += it }

    private fun TestScope.broadcasts(
        secured: Boolean = false,
        payloadMax: Int = BroadcastFrame.PAYLOAD_MAX,
        origination: Boolean = true,
    ): Broadcasts {
        val clock = SchedulerClock(testScheduler)
        val writing = if (secured) broadcastCodec(secured = true, random = ::nextId) else codec
        val flooding = if (secured) Floods(lanes, writing, lowTree(lanes), ::tombstoneCommitment, holds = true) {} else floods
        val broadcasts = Broadcasts(clock, backgroundScope, capacity, flooding, writing, config(payloadMax, origination))
        broadcasts.begin()
        return broadcasts
    }

    private fun config(
        payloadMax: Int = BroadcastFrame.PAYLOAD_MAX,
        origination: Boolean = true,
    ) = meshLinkConfig(PRESET, SecurityProfile.Unsecured) {
        meshId = "Broadcasts"
        limits.maxBroadcastPayload = payloadMax
        broadcastOrigination = origination
    }.build()

    private fun TestScope.accepted(
        broadcasts: Broadcasts,
        payload: ByteArray = byteArrayOf(1, 2, 3),
        hopLimit: Int = 255,
    ): OutgoingMessage {
        val result = broadcasts.send(payload, hopLimit, RUNNING)
        runCurrent()
        return (result as SendResult.Accepted).message
    }

    @Test
    fun aBroadcastIsFramedWithItsHopLimitAndSenderAndGoesToEveryLink() =
        runTest {
            // Arrange
            listOf(a, b).forEach { lanes.open(it) }
            val broadcasts = broadcasts()

            // Act
            val message = accepted(broadcasts, hopLimit = 7)

            // Assert
            val frames = listOf(a, b).map { peer -> lanes.drain(peer).single().render() }
            val expiry = SchedulerClock.WALL_START_MILLIS / 1_000 + LIFETIME
            val header = FloodHeader(7, ByteArray(12) { 7 }, expiry.toUInt())
            val expected = BroadcastFrame(header, Container.Unsecured(SELF, byteArrayOf(1, 2, 3))).encode()
            frames.forEach { assertContentEquals(expected, it) }
            assertNull(message.to)
            assertEquals(DeliveryStatus.Queued, message.status.value)
        }

    @Test
    fun aBroadcastIsSentAtItsFirstWrittenCopyAndExpiresAtItsExpiryPlusClockSkew() =
        runTest {
            // Arrange
            lanes.open(a)
            lanes.open(b)
            val message = accepted(broadcasts())
            val copies = listOf(a, b).map { lanes.drain(it).single() }

            // Act
            copies[0].settle(Settled.BUSY)
            val busy = message.status.value
            copies[0].settle(Settled.SENT)
            val sent = message.status.value
            advanceTimeBy((LIFETIME + SKEW) * 1_000L - 1)
            runCurrent()
            val beforeDeadline = message.status.value
            advanceTimeBy(1)
            runCurrent()
            copies[1].settle(Settled.SENT)

            // Assert
            assertEquals(DeliveryStatus.Queued, busy)
            assertEquals(DeliveryStatus.Sent, sent)
            assertEquals(DeliveryStatus.Sent, beforeDeadline)
            assertEquals(DeliveryStatus.Expired, message.status.value)
        }

    @Test
    fun aBroadcastWithoutALinkWaitsQueuedAndGoesOutOnTheFirstLinkOnly() =
        runTest {
            // Arrange
            val broadcasts = broadcasts()
            val message = accepted(broadcasts)

            // Act
            lanes.open(a)
            floods.linked(a, SchedulerClock(testScheduler).wallClockMillis())
            val first = lanes.drain(a)
            lanes.open(b)
            floods.linked(b, SchedulerClock(testScheduler).wallClockMillis())

            // Assert
            assertEquals(1, first.size)
            assertEquals(emptyList(), lanes.drain(b))
            assertEquals(DeliveryStatus.Queued, message.status.value)
        }

    @Test
    fun aBroadcastThatExpiresWithoutALinkNeverGoesOut() =
        runTest {
            // Arrange
            val broadcasts = broadcasts()
            val message = accepted(broadcasts)
            advanceTimeBy((LIFETIME + SKEW) * 1_000L)
            runCurrent()

            // Act
            lanes.open(a)
            floods.linked(a, SchedulerClock(testScheduler).wallClockMillis())

            // Assert
            assertEquals(DeliveryStatus.Expired, message.status.value)
            assertEquals(emptyList(), lanes.drain(a))
        }

    @Test
    fun theHopLimitMustBeOneTo255AndThePayloadAtMost1024Bytes() =
        runTest {
            // Arrange
            lanes.open(a)
            val broadcasts = broadcasts()

            // Act
            val results =
                listOf(
                    broadcasts.send(ByteArray(1), 0, RUNNING),
                    broadcasts.send(ByteArray(1), 256, RUNNING),
                    broadcasts.send(ByteArray(1_025), 255, RUNNING),
                    broadcasts.send(ByteArray(1), 255, MeshState.Stopped),
                )
            runCurrent()
            val rejected = lanes.drain(a)
            val edges = listOf(1, 255).map { accepted(broadcasts, ByteArray(1_024), it) }

            // Assert
            val reasons = List(2) { RejectionReason.InvalidHopLimit } + RejectionReason.TooLarge
            assertEquals(reasons + RejectionReason.NotRunning, results.map { (it as SendResult.Rejected).reason })
            assertEquals(emptyList(), rejected)
            assertEquals(listOf(1, 255), lanes.drain(a).map { it.render()[1].toInt() and 0xff })
            assertEquals(2, edges.size)
        }

    @Test
    fun withBroadcastOriginationOffARunningNodeRejectsEveryBroadcastAsDisabled() =
        runTest {
            // Arrange
            lanes.open(a)
            val broadcasts = broadcasts(origination = false)

            // Act
            val results = listOf(RUNNING, MeshState.Stopped).map { broadcasts.send(ByteArray(1), 255, it) }
            runCurrent()

            // Assert
            val reasons = listOf(RejectionReason.BroadcastDisabled, RejectionReason.NotRunning)
            assertEquals(reasons, results.map { (it as SendResult.Rejected).reason })
            assertEquals(emptyList(), lanes.drain(a))
            assertTrue(capacity.empty())
        }

    @Test
    fun aLoweredBroadcastCapRejectsALargerPayloadAsTooLarge() =
        runTest {
            // Arrange
            lanes.open(a)
            val broadcasts = broadcasts(payloadMax = 100)

            // Act
            val above = broadcasts.send(ByteArray(101), 255, RUNNING)
            runCurrent()
            val rejected = lanes.drain(a)
            accepted(broadcasts, ByteArray(100))

            // Assert
            assertEquals(SendResult.Rejected(RejectionReason.TooLarge), above)
            assertEquals(emptyList(), rejected)
            assertEquals(1, lanes.drain(a).size)
        }

    @Test
    fun aBroadcastHoldsItsPayloadInTheSendQueueUntilItEnds() =
        runTest {
            // Arrange
            val broadcasts = broadcasts()
            assertTrue(capacity.reserve((Capacity.LIMIT - 1).toInt()))
            accepted(broadcasts, ByteArray(1))

            // Act
            val full = broadcasts.send(ByteArray(1), 255, RUNNING)
            advanceTimeBy((LIFETIME + SKEW) * 1_000L)
            runCurrent()
            capacity.release((Capacity.LIMIT - 1).toInt())
            val freed = capacity.empty()

            // Assert
            assertEquals(SendResult.Rejected(RejectionReason.QueueFull), full)
            assertTrue(freed)
        }

    @Test
    fun anEndedRunFailsItsBroadcastsAndALaterOneWithStopped() =
        runTest {
            // Arrange
            val broadcasts = broadcasts()
            val waiting = accepted(broadcasts, ByteArray(10))

            // Act
            broadcasts.end()
            val late = accepted(broadcasts, ByteArray(10))
            lanes.open(a)
            floods.linked(a, SchedulerClock(testScheduler).wallClockMillis())

            // Assert
            assertEquals(DeliveryStatus.Failed(FailureReason.Stopped), waiting.status.value)
            assertEquals(DeliveryStatus.Failed(FailureReason.Stopped), late.status.value)
            assertEquals(emptyList(), lanes.drain(a))
            assertTrue(capacity.empty())
        }

    @Test
    fun aBroadcastOfANodeClosedBeforeItRunsFailsWithStopped() =
        runTest {
            // Arrange
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
            val clock = SchedulerClock(testScheduler)
            val broadcasts = Broadcasts(clock, scope, capacity, floods, codec, config())
            broadcasts.begin()
            val message = (broadcasts.send(ByteArray(10), 255, RUNNING) as SendResult.Accepted).message

            // Act
            scope.cancel()
            runCurrent()

            // Assert
            assertEquals(DeliveryStatus.Failed(FailureReason.Stopped), message.status.value)
        }

    @Test
    fun aSecuredNodesBroadcastIsSignedByItsIdentityAndSent() =
        runTest {
            // Arrange
            lanes.open(a)

            // Act
            val message = accepted(broadcasts(secured = true), hopLimit = 7)
            val copy = lanes.drain(a).single()
            copy.settle(Settled.SENT)

            // Assert
            val bytes = copy.render()
            val frame = (BroadcastFrame.decode(bytes) as Decoded.Valid).value
            val origin = Derivations(SodiumCryptoProvider).nodeId(identityKey(1).publicKey)
            assertContentEquals(origin, frame.verifiedOrigin(SodiumCryptoProvider))
            assertContentEquals(byteArrayOf(1, 2, 3), (frame.container as Container.SignedBroadcast).payload)
            assertEquals(7, frame.header.hopLimit)
            assertContentEquals(ByteArray(12) { 7 }, frame.header.messageId)
            assertEquals(DeliveryStatus.Sent, message.status.value)
        }

    @Test
    fun anEchoOfThisNodesBroadcastIsDropped() =
        runTest {
            // Arrange
            lanes.open(a)
            lanes.open(b)
            accepted(broadcasts())
            val echo =
                lanes
                    .drain(a)
                    .single()
                    .render()
                    .also { it[1] = 3 }
            lanes.drain(b)

            // Act
            floods.received(a, echo, SchedulerClock(testScheduler).wallClockMillis() / 1_000)

            // Assert
            assertEquals(emptyList(), received)
            assertEquals(emptyList(), lanes.drain(b))
        }

    private var ids = 6

    // Message ids of 7s, then 8s, and so on.
    private fun nextId(size: Int) = ByteArray(size) { (ids + 1).toByte() }.also { ids++ }

    // Whether no bytes are reserved: only then does the cap take its last byte after all the others.
    private fun Capacity.empty() = reserve((Capacity.LIMIT - 1).toInt()) && reserve(1)

    private companion object {
        val RUNNING: MeshState = MeshState.Running(emptySet())
        val PRESET = Preset.ProximityDiscovery
        val LIFETIME = PRESET.values.messageLifetimeSeconds
        const val SKEW = 30
        val SELF = ByteArray(32) { 9 }
    }
}
