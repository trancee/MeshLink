package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.FailureReason
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshLinkException
import ch.trancee.meshlink.MeshState
import ch.trancee.meshlink.ReceivedMessage
import ch.trancee.meshlink.RejectionReason
import ch.trancee.meshlink.Requirement
import ch.trancee.meshlink.SendResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DirectMessageTest {
    @Test
    fun directMessageReachesThePeersCollectorWithTheSenderNodeIdAndTheSenderSeesQueuedSentThenDelivered() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val bob = air.platform(StandardTestDispatcher(testScheduler))
            val nodes = listOf(node(alice), node(bob))
            nodes.forEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)
            val received = mutableListOf<ReceivedMessage>()
            backgroundScope.launch { nodes[1].messages.collect { received += it } }
            val payload = "hello, Bob".encodeToByteArray()

            // Act
            val result =
                nodes[0].send(
                    nodes[0]
                        .peers.value
                        .single()
                        .nodeId,
                    payload,
                )
            val statuses = mutableListOf<DeliveryStatus>()
            val accepted = assertIs<SendResult.Accepted>(result)
            backgroundScope.launch { accepted.message.status.toList(statuses) }
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val message = received.single()
            assertContentEquals(nodeId(alice), message.sender.toByteArray())
            assertContentEquals(payload, message.payload)
            assertEquals(listOf(DeliveryStatus.Queued, DeliveryStatus.Sent, DeliveryStatus.Delivered), statuses)
        }

    @Test
    fun sendAndBroadcastAreRejectedAsNotRunningBeforeStartAndAfterStop() =
        runTest {
            // Arrange
            val (nodes) = linkedPair()
            val bob =
                nodes[0]
                    .peers.value
                    .single()
                    .nodeId
            val idle = node(air().platform(StandardTestDispatcher(testScheduler)))
            nodes[0].stop()

            // Act
            val results =
                listOf(idle, nodes[0]).flatMap { listOf(it.send(bob, PAYLOAD), it.broadcast(PAYLOAD)) }

            // Assert
            assertEquals(List(4) { SendResult.Rejected(RejectionReason.NotRunning) }, results)
        }

    @Test
    fun sendToTheNodesOwnNodeIdIsRejectedAsUnknownPeer() =
        runTest {
            // Arrange
            val (nodes) = linkedPair()
            val alice =
                nodes[1]
                    .peers.value
                    .single()
                    .nodeId

            // Act
            val result = nodes[0].send(alice, PAYLOAD)

            // Assert
            assertEquals(SendResult.Rejected(RejectionReason.UnknownPeer), result)
        }

    @Test
    fun sendToANodeIdThatNeverLinkedIsAcceptedAndFloodedToTheLinkedPeers() =
        runTest {
            // Arrange
            val (nodes) = linkedPair()
            val (elsewhere) = linkedPair()
            val stranger =
                elsewhere[0]
                    .peers.value
                    .single()
                    .nodeId
            val received = mutableListOf<ReceivedMessage>()
            backgroundScope.launch { nodes[1].messages.toList(received) }

            // Act
            val status = accepted(nodes[0].send(stranger, PAYLOAD))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(DeliveryStatus.Sent, status.value)
            assertEquals(emptyList(), received)
        }

    @Test
    fun payloadAbove4096BytesIsRejectedAsTooLarge() =
        runTest {
            // Arrange
            val (nodes) = linkedPair()
            val bob =
                nodes[0]
                    .peers.value
                    .single()
                    .nodeId

            // Act
            val result = nodes[0].send(bob, ByteArray(PAYLOAD_MAX + 1))

            // Assert
            assertEquals(SendResult.Rejected(RejectionReason.TooLarge), result)
        }

    @Test
    fun blockedNodeStillAcceptsMessages() =
        runTest {
            // Arrange
            val (nodes, platforms) = linkedPair()
            val bob =
                nodes[0]
                    .peers.value
                    .single()
                    .nodeId
            platforms[0].radio.report(setOf(Requirement.BluetoothOff))
            runCurrent()

            // Act
            val result = nodes[0].send(bob, PAYLOAD)

            // Assert
            assertIs<MeshState.Blocked>(nodes[0].state.value)
            assertIs<SendResult.Accepted>(result)
        }

    @Test
    fun queueRejectsAsQueueFullOnce1MiBWaitsAndAwaitSendCapacityReturnsWhenMessagesExpire() =
        runTest {
            // Arrange
            val (nodes) = linkedPair()
            val bob =
                nodes[0]
                    .peers.value
                    .single()
                    .nodeId
            nodes[1].stop()
            advanceTimeBy(SETTLE_MILLIS)
            val deadline = deadlineOfASendNow()
            val accepted = List(FULL_QUEUE_COUNT) { nodes[0].send(bob, ByteArray(PAYLOAD_MAX)) }

            // Act
            val rejected = nodes[0].send(bob, ByteArray(PAYLOAD_MAX))
            val waiter = async { nodes[0].awaitSendCapacity() }
            advanceTimeBy(deadline - currentTime - 1)
            val waitingBeforeExpiry = waiter.isActive
            advanceTimeBy(2)

            // Assert
            assertTrue(accepted.all { it is SendResult.Accepted })
            assertEquals(SendResult.Rejected(RejectionReason.QueueFull), rejected)
            assertTrue(waitingBeforeExpiry)
            assertTrue(waiter.isCompleted)
        }

    @Test
    fun closedNodeThrowsClosedFromSendBroadcastAndAwaitSendCapacity() =
        runTest {
            // Arrange
            val (nodes) = linkedPair()
            val bob =
                nodes[0]
                    .peers.value
                    .single()
                    .nodeId
            nodes[0].close()

            // Act
            val send = assertFails { nodes[0].send(bob, PAYLOAD) }
            val broadcast = assertFails { nodes[0].broadcast(PAYLOAD) }
            val await = assertFails { nodes[0].awaitSendCapacity() }

            // Assert
            assertIs<MeshLinkException.Closed>(send)
            assertIs<MeshLinkException.Closed>(broadcast)
            assertIs<MeshLinkException.Closed>(await)
        }

    @Test
    fun messageToAStoppedPeerIsHeldThenDeliveredWhenThePeerReturns() =
        runTest {
            // Arrange
            val (nodes) = linkedPair()
            val bob =
                nodes[0]
                    .peers.value
                    .single()
                    .nodeId
            val received = mutableListOf<ReceivedMessage>()
            backgroundScope.launch { nodes[1].messages.collect { received += it } }
            nodes[1].stop()
            advanceTimeBy(SETTLE_MILLIS)
            val status = accepted(nodes[0].send(bob, PAYLOAD))
            runCurrent()
            val held = status.value

            // Act
            nodes[1].start()
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(DeliveryStatus.Held, held)
            assertEquals(DeliveryStatus.Delivered, status.value)
            assertContentEquals(PAYLOAD, received.single().payload)
        }

    @Test
    fun heldMessageExpiresThirtySecondsAfterItsLifetime() =
        runTest {
            // Arrange
            val (nodes) = linkedPair()
            val bob =
                nodes[0]
                    .peers.value
                    .single()
                    .nodeId
            nodes[1].stop()
            advanceTimeBy(SETTLE_MILLIS)
            val deadline = deadlineOfASendNow()
            val status = accepted(nodes[0].send(bob, PAYLOAD))

            // Act
            advanceTimeBy(deadline - currentTime - 1)
            val beforeDeadline = status.value
            advanceTimeBy(2)

            // Assert
            assertEquals(DeliveryStatus.Held, beforeDeadline)
            assertEquals(DeliveryStatus.Expired, status.value)
        }

    @Test
    fun sentMessageEndsExpired() =
        runTest {
            // Arrange
            val (nodes) = linkedPair()
            val bob =
                nodes[0]
                    .peers.value
                    .single()
                    .nodeId
            val deadline = deadlineOfASendNow()
            val status = accepted(nodes[0].send(bob, PAYLOAD))
            advanceTimeBy(SETTLE_MILLIS)

            // Act
            advanceTimeBy(deadline - currentTime - 1)
            val beforeDeadline = status.value
            advanceTimeBy(2)

            // Assert
            assertEquals(DeliveryStatus.Sent, beforeDeadline)
            assertEquals(DeliveryStatus.Expired, status.value)
        }

    @Test
    fun stopFailsHeldAndSentMessagesWithStopped() =
        runTest {
            // Arrange
            val (nodes) = linkedPair()
            val bob =
                nodes[0]
                    .peers.value
                    .single()
                    .nodeId
            val sent = accepted(nodes[0].send(bob, PAYLOAD))
            advanceTimeBy(SETTLE_MILLIS)
            nodes[1].stop()
            advanceTimeBy(SETTLE_MILLIS)
            val held = accepted(nodes[0].send(bob, PAYLOAD))
            runCurrent()

            // Act
            nodes[0].stop()

            // Assert
            assertEquals(List(2) { DeliveryStatus.Failed(FailureReason.Stopped) }, listOf(sent.value, held.value))
        }

    @Test
    fun closeRightAfterSendFailsTheMessageWithStopped() =
        runTest {
            // Arrange
            val (nodes) = linkedPair()
            val bob =
                nodes[0]
                    .peers.value
                    .single()
                    .nodeId
            val status = accepted(nodes[0].send(bob, PAYLOAD))

            // Act
            nodes[0].close()
            runCurrent()

            // Assert
            assertEquals(DeliveryStatus.Failed(FailureReason.Stopped), status.value)
        }

    @Test
    fun secondConcurrentCollectorOfMessagesGetsAlreadyCollected() =
        runTest {
            // Arrange
            val (nodes) = linkedPair()
            backgroundScope.launch { nodes[1].messages.collect {} }
            runCurrent()

            // Act
            val failure = assertFails { nodes[1].messages.first() }

            // Assert
            assertIs<MeshLinkException.AlreadyCollected>(failure)
        }

    @Test
    fun aLaterCollectorGetsTheMessagesThatArrivedWhileNoneCollected() =
        runTest {
            // Arrange
            val (nodes) = linkedPair()
            val bob =
                nodes[0]
                    .peers.value
                    .single()
                    .nodeId
            val first = backgroundScope.launch { nodes[1].messages.collect {} }
            runCurrent()
            first.cancel()
            nodes[0].send(bob, PAYLOAD)
            advanceTimeBy(SETTLE_MILLIS)

            // Act
            val message = nodes[1].messages.first()

            // Assert
            assertContentEquals(PAYLOAD, message.payload)
        }

    @Test
    fun messagesCompletesWhenTheNodeCloses() =
        runTest {
            // Arrange
            val (nodes) = linkedPair()
            val collected = async { nodes[1].messages.toList() }
            runCurrent()

            // Act
            nodes[1].close()

            // Assert
            assertEquals(emptyList(), collected.await())
        }

    private suspend fun TestScope.linkedPair(): Pair<List<MeshLink>, List<VirtualPlatform>> {
        val air = air()
        val platforms = List(2) { air.platform(StandardTestDispatcher(testScheduler)) }
        val nodes = platforms.map { node(it) }
        nodes.forEach { it.start() }
        advanceTimeBy(SETTLE_MILLIS)
        return nodes to platforms
    }

    // Virtual time at which a message sent now expires: its lifetime plus the 30-second skew allowance.
    private fun TestScope.deadlineOfASendNow(): Long =
        (currentTime / MILLIS_PER_SECOND + LIFETIME_SECONDS + SKEW_SECONDS) * MILLIS_PER_SECOND

    private fun accepted(result: SendResult): StateFlow<DeliveryStatus> {
        val accepted = assertIs<SendResult.Accepted>(result)
        return accepted.message.status
    }

    private companion object {
        val PAYLOAD = "hello, Bob".encodeToByteArray()
        const val PAYLOAD_MAX = 4_096
        const val MILLIS_PER_SECOND = 1_000L
        const val LIFETIME_SECONDS = 1_800L
        const val SKEW_SECONDS = 30L

        // 4,096-byte payloads until the waiting bytes reach 1 MiB: the 256th reaches it.
        const val FULL_QUEUE_COUNT = 256
    }
}
