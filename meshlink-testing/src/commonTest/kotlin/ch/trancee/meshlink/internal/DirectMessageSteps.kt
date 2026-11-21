package ch.trancee.meshlink.internal

import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.ReceivedMessage
import ch.trancee.meshlink.RejectionReason
import ch.trancee.meshlink.SendResult
import io.github.menjoo.cucumberkmp.core.runner.StepsBuilder
import io.github.menjoo.cucumberkmp.core.runner.given
import io.github.menjoo.cucumberkmp.core.runner.then
import io.github.menjoo.cucumberkmp.core.runner.whenever
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
internal fun StepsBuilder.directMessageSteps() {
    lateinit var scope: TestScope
    var scopeInitialized = false
    val nodes = mutableListOf<MeshLink>()
    val received = mutableListOf<ReceivedMessage>()
    lateinit var result: SendResult
    lateinit var bobId: NodeId

    after {
        nodes.forEach { it.close() }
        if (scopeInitialized) scope.backgroundScope.cancel()
    }

    given("Alice and Bob are running in the same mesh") {
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor] as TestDispatcher
        scope = TestScope(dispatcher)
        scopeInitialized = true
        val air = scope.air()
        val alice = air.platform(StandardTestDispatcher(scope.testScheduler))
        val bob = air.platform(StandardTestDispatcher(scope.testScheduler))
        nodes += node(alice)
        nodes += node(bob)
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

    given("Alice has stopped") {
        nodes[0].stop()
    }

    whenever("Alice sends {string} to Bob") { payload: String ->
        result = nodes[0].send(bobId, payload.encodeToByteArray())
        scope.testScheduler.advanceTimeBy(SETTLE_MILLIS)
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

    then("Bob receives no messages") {
        assertEquals(emptyList(), received)
    }
}

private fun senderId(node: MeshLink): NodeId =
    node.peers.value
        .single()
        .nodeId
