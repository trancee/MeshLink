package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.FailureReason
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshLinkException
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.OutgoingMessage
import ch.trancee.meshlink.Reachability
import ch.trancee.meshlink.ReceivedMessage
import ch.trancee.meshlink.RejectionReason
import ch.trancee.meshlink.SendResult
import io.github.menjoo.cucumberkmp.core.runner.StepsBuilder
import io.github.menjoo.cucumberkmp.core.runner.given
import io.github.menjoo.cucumberkmp.core.runner.then
import io.github.menjoo.cucumberkmp.core.runner.whenever
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal fun StepsBuilder.directMessageSteps() {
    with(DirectMessageScenario()) { register() }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class DirectMessageScenario {
    private lateinit var scope: TestScope
    private lateinit var air: VirtualAir
    private lateinit var bobPlatform: VirtualPlatform
    private val nodes = mutableListOf<MeshLink>()
    private val aliceReceived = mutableListOf<ReceivedMessage>()
    private val received = mutableListOf<ReceivedMessage>()
    private val queued = mutableListOf<OutgoingMessage>()
    private lateinit var result: SendResult
    private lateinit var bobId: NodeId
    private lateinit var capacityWait: Deferred<Unit>
    private var collectorFailure: MeshLinkException.AlreadyCollected? = null

    fun StepsBuilder.register() {
        after {
            nodes.forEach { it.close() }
            if (::scope.isInitialized) scope.backgroundScope.cancel()
        }
        setupSteps()
        actionSteps()
        assertionSteps()
        statusAssertionSteps()
        capacityAssertionSteps()
    }

    private fun StepsBuilder.setupSteps() {
        given("Alice and Bob are running in the same mesh") {
            val dispatcher = currentCoroutineContext()[ContinuationInterceptor] as TestDispatcher
            scope = TestScope(dispatcher)
            air = scope.air()
            val alice = air.platform(StandardTestDispatcher(scope.testScheduler))
            bobPlatform = air.platform(StandardTestDispatcher(scope.testScheduler))
            nodes += node(alice)
            nodes += node(bobPlatform)
            nodes.forEach { it.start() }
        }

        given("Alice and Bob are directly linked") {
            scope.testScheduler.advanceTimeBy(SETTLE_MILLIS)
            bobId =
                nodes[0]
                    .peers.value
                    .single()
                    .nodeId
            nodes[1].peers.value.single()
        }

        given("Bob is collecting received messages") {
            scope.backgroundScope.launch { nodes[1].messages.collect { received += it } }
            scope.testScheduler.runCurrent()
        }

        given("Alice is collecting received messages") {
            scope.backgroundScope.launch { nodes[0].messages.collect { aliceReceived += it } }
            scope.testScheduler.runCurrent()
        }

        given("Alice has stopped") {
            nodes[0].stop()
        }

        given("Bob is out of direct contact with Alice") {
            air.move(bobPlatform.radio, VirtualAir.DEFAULT_RANGE + 1, 0.0)
            scope.testScheduler.advanceTimeBy(SETTLE_MILLIS)
            nodes.forEach { assertEquals(emptyList(), it.peers.value) }
        }

        given("Alice has sent {string} while disconnected") { payload: String ->
            result = nodes[0].send(bobId, payload.encodeToByteArray())
            scope.testScheduler.advanceTimeBy(SETTLE_MILLIS)
            assertEquals(DeliveryStatus.Held, assertIs<SendResult.Accepted>(result).message.status.value)
            assertEquals(emptyList(), received)
        }

        given("Alice has queued {int} messages of {int} bytes for Bob") { count: Int, size: Int ->
            repeat(count) {
                queued += assertIs<SendResult.Accepted>(nodes[0].send(bobId, ByteArray(size))).message
            }
        }

        given("Alice is waiting for send capacity") {
            capacityWait = scope.backgroundScope.async { nodes[0].awaitSendCapacity() }
            scope.testScheduler.runCurrent()
            assertTrue(capacityWait.isActive)
        }

        given("Alice's held message has expired") {
            scope.testScheduler.advanceTimeBy(EXPIRY_WAIT_MILLIS)
            assertEquals(DeliveryStatus.Expired, assertIs<SendResult.Accepted>(result).message.status.value)
        }
    }

    private fun StepsBuilder.actionSteps() {
        whenever("Alice broadcasts {string} with hop limit {int}") { payload: String, hopLimit: Int ->
            result = nodes[0].broadcast(payload.encodeToByteArray(), hopLimit)
            scope.testScheduler.advanceTimeBy(SETTLE_MILLIS)
        }

        whenever("Alice sends {string} to Bob") { payload: String ->
            result = nodes[0].send(bobId, payload.encodeToByteArray())
            scope.testScheduler.advanceTimeBy(SETTLE_MILLIS)
        }

        whenever("Alice sends {string} to herself") { payload: String ->
            result = nodes[0].send(senderId(nodes[1]), payload.encodeToByteArray())
            scope.testScheduler.advanceTimeBy(SETTLE_MILLIS)
        }

        whenever("Alice sends {int} bytes to Bob") { size: Int ->
            result = nodes[0].send(bobId, ByteArray(size))
            scope.testScheduler.advanceTimeBy(SETTLE_MILLIS)
        }

        whenever("Alice's queued messages expire") {
            scope.testScheduler.advanceTimeBy(EXPIRY_WAIT_MILLIS)
            scope.testScheduler.runCurrent()
        }

        whenever("another collector starts receiving Bob's messages") {
            collectorFailure =
                try {
                    withTimeoutOrNull(1) { nodes[1].messages.first() }
                    null
                } catch (failure: MeshLinkException.AlreadyCollected) {
                    failure
                }
        }

        whenever("Alice stops") {
            nodes[0].stop()
        }

        whenever("Bob returns to direct contact with Alice") {
            air.move(bobPlatform.radio, 0.0, 0.0)
            scope.testScheduler.advanceTimeBy(RECONNECT_WAIT_MILLIS)
        }
    }

    private fun StepsBuilder.assertionSteps() {
        then("the broadcast is rejected as InvalidHopLimit") {
            assertEquals(SendResult.Rejected(RejectionReason.InvalidHopLimit), result)
        }

        then("Bob receives {string} from Alice") { payload: String ->
            assertIs<SendResult.Accepted>(result)
            val message = received.single()
            assertContentEquals(payload.encodeToByteArray(), message.payload)
            assertEquals(senderId(nodes[1]), message.sender)
        }

        then("the send is rejected as NotRunning") {
            assertEquals(SendResult.Rejected(RejectionReason.NotRunning), result)
        }

        then("the send is rejected as UnknownPeer") {
            assertEquals(SendResult.Rejected(RejectionReason.UnknownPeer), result)
        }

        then("the send is rejected as TooLarge") {
            assertEquals(SendResult.Rejected(RejectionReason.TooLarge), result)
        }

        then("the send is rejected as QueueFull") {
            assertEquals(SendResult.Rejected(RejectionReason.QueueFull), result)
        }

        then("the second collector is rejected as AlreadyCollected") {
            assertIs<MeshLinkException.AlreadyCollected>(collectorFailure)
        }

        then("Alice receives no messages") {
            assertEquals(emptyList(), aliceReceived)
        }

        then("Alice and Bob are directly linked again") {
            assertEquals(
                bobId,
                nodes[0]
                    .peers.value
                    .single()
                    .nodeId,
            )
            nodes.forEach {
                assertEquals(
                    Reachability.Direct,
                    it.peers.value
                        .single()
                        .reachability,
                )
            }
        }

        then("Bob receives no messages") {
            assertEquals(emptyList(), received)
        }
    }

    private fun StepsBuilder.statusAssertionSteps() {
        then("Alice's message is held") {
            assertEquals(DeliveryStatus.Held, assertIs<SendResult.Accepted>(result).message.status.value)
        }

        then("Alice's message fails as Stopped") {
            assertEquals(
                DeliveryStatus.Failed(FailureReason.Stopped),
                assertIs<SendResult.Accepted>(result).message.status.value,
            )
        }

        then("Alice's message is sent") {
            assertEquals(DeliveryStatus.Sent, assertIs<SendResult.Accepted>(result).message.status.value)
        }

        then("Alice's message remains expired") {
            assertEquals(DeliveryStatus.Expired, assertIs<SendResult.Accepted>(result).message.status.value)
        }
    }

    private fun StepsBuilder.capacityAssertionSteps() {
        then("Alice's capacity wait completes") {
            assertTrue(capacityWait.isCompleted)
            capacityWait.await()
        }

        then("Alice's queued messages have expired") {
            assertTrue(queued.all { it.status.value == DeliveryStatus.Expired })
        }

        then("Alice can send {string} to Bob") { payload: String ->
            assertIs<SendResult.Accepted>(nodes[0].send(bobId, payload.encodeToByteArray()))
        }
    }
}

private fun senderId(node: MeshLink): NodeId =
    node.peers.value
        .single()
        .nodeId

private const val RECONNECT_WAIT_MILLIS = 60_000L
private const val EXPIRY_WAIT_MILLIS = 30 * 60_000L + 30_000L
