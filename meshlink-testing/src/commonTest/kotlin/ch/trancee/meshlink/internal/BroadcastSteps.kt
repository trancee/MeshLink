package ch.trancee.meshlink.internal

import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.ReceivedMessage
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal fun StepsBuilder.broadcastSteps() {
    lateinit var scope: TestScope
    lateinit var aliceId: NodeId
    var scopeInitialized = false
    val nodes = mutableListOf<MeshLink>()
    val inboxes = List(3) { mutableListOf<ReceivedMessage>() }

    after {
        nodes.forEach { it.close() }
        if (scopeInitialized) scope.backgroundScope.cancel()
    }

    given("Alice, Bob, Carol and Dave are running in a line with links only between neighbors") {
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor] as TestDispatcher
        scope = TestScope(dispatcher)
        scopeInitialized = true
        val air = scope.air()
        val platforms =
            List(4) { index ->
                air.platform(StandardTestDispatcher(scope.testScheduler)).also {
                    air.move(it.radio, index * 25.0, 0.0)
                }
            }
        nodes += platforms.map { node(it) }
        nodes.forEach { it.start() }
        scope.testScheduler.advanceTimeBy(BROADCAST_SETTLE_MILLIS)
        nodes.forEachIndexed { index, node ->
            val expected = listOf(index - 1, index + 1).filter { it in platforms.indices }
            assertEquals(
                expected.map { nodeId(platforms[it]).toList() }.toSet(),
                node.peers.value
                    .map { it.nodeId.toByteArray().toList() }
                    .toSet(),
            )
        }
        aliceId =
            nodes[1]
                .peers.value
                .single { it.nodeId.toByteArray().contentEquals(nodeId(platforms[0])) }
                .nodeId
    }

    given("Bob, Carol and Dave are collecting broadcasts") {
        nodes.drop(1).forEachIndexed { index, node ->
            scope.backgroundScope.launch { node.messages.collect { inboxes[index] += it } }
        }
        scope.testScheduler.runCurrent()
    }

    whenever("Alice broadcasts {string} with a hop limit of {int}") { payload: String, hopLimit: Int ->
        assertIs<SendResult.Accepted>(nodes[0].broadcast(payload.encodeToByteArray(), hopLimit))
        scope.testScheduler.advanceTimeBy(BROADCAST_SETTLE_MILLIS)
    }

    fun assertionSteps() {
        then("Bob and Carol each receive the broadcast {string} from Alice once") { payload: String ->
            inboxes.take(2).forEach { inbox ->
                assertEquals(1, inbox.size)
                val message = inbox.single()
                assertContentEquals(payload.encodeToByteArray(), message.payload)
                assertEquals(aliceId, message.sender)
                assertTrue(message.broadcast)
            }
        }

        then("Dave receives no broadcasts") {
            assertEquals(emptyList(), inboxes[2])
        }
    }

    assertionSteps()
}

private const val BROADCAST_SETTLE_MILLIS = 60_000L
