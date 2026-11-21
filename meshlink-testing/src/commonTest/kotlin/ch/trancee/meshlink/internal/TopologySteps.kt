package ch.trancee.meshlink.internal

import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.Reachability
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
internal fun StepsBuilder.topologySteps() {
    lateinit var scope: TestScope
    lateinit var air: VirtualAir
    lateinit var aliceId: NodeId
    lateinit var daveId: NodeId
    var scopeInitialized = false
    val nodes = mutableListOf<MeshLink>()
    val platforms = mutableListOf<VirtualPlatform>()
    val inboxes = List(3) { mutableListOf<ReceivedMessage>() }

    after {
        nodes.forEach { it.close() }
        if (scopeInitialized) scope.backgroundScope.cancel()
    }

    given("Alice, Bob, Carol and Dave are running in a line with links only between neighbors") {
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor] as TestDispatcher
        scope = TestScope(dispatcher)
        scopeInitialized = true
        air = scope.air()
        platforms +=
            List(4) { index ->
                air.platform(StandardTestDispatcher(scope.testScheduler)).also {
                    air.move(it.radio, index * 25.0, 0.0)
                }
            }
        nodes += platforms.map { node(it) }
        nodes.forEach { it.start() }
        scope.testScheduler.advanceTimeBy(TOPOLOGY_SETTLE_MILLIS)
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

    given("Bob, Carol and Dave are collecting messages") {
        nodes.drop(1).forEachIndexed { index, node ->
            scope.backgroundScope.launch { node.messages.collect { inboxes[index] += it } }
        }
        scope.testScheduler.runCurrent()
    }

    fun routingSteps() {
        given("Alice knows Dave's identity") {
            daveId =
                nodes[2]
                    .peers.value
                    .single { it.nodeId.toByteArray().contentEquals(nodeId(platforms[3])) }
                    .nodeId
        }

        whenever("Alice sends {string} to Dave through the mesh") { payload: String ->
            assertIs<SendResult.Accepted>(nodes[0].send(daveId, payload.encodeToByteArray()))
            scope.testScheduler.advanceTimeBy(TOPOLOGY_SETTLE_MILLIS)
        }

        given("Alice has sent {string} to Dave") { payload: String ->
            assertIs<SendResult.Accepted>(nodes[0].send(daveId, payload.encodeToByteArray()))
            scope.testScheduler.advanceTimeBy(TOPOLOGY_SETTLE_MILLIS)
            assertEquals(listOf(payload), inboxes[2].map { it.payload.decodeToString() })
        }

        whenever("Bob leaves and Carol and Dave move closer") {
            air.move(platforms[1].radio, 1_000.0, 0.0)
            air.move(platforms[2].radio, 25.0, 0.0)
            air.move(platforms[3].radio, 50.0, 0.0)
            scope.testScheduler.advanceTimeBy(TOPOLOGY_SETTLE_MILLIS)
        }
    }

    routingSteps()

    fun catchUpSteps() {
        given("Dave is out of range") {
            air.move(platforms[3].radio, 1_000.0, 0.0)
            scope.testScheduler.advanceTimeBy(10_000L)
            assertTrue(nodes[3].peers.value.isEmpty())
        }

        given("Alice broadcasts {string} while Dave is away") { payload: String ->
            assertIs<SendResult.Accepted>(nodes[0].broadcast(payload.encodeToByteArray()))
            scope.testScheduler.advanceTimeBy(1_000L)
            assertEquals(listOf(payload), inboxes[1].map { it.payload.decodeToString() })
            assertEquals(emptyList(), inboxes[2])
        }

        whenever("Dave returns within a minute") {
            air.move(platforms[3].radio, 75.0, 0.0)
            scope.testScheduler.advanceTimeBy(40_000L)
        }
    }

    catchUpSteps()

    whenever("Alice broadcasts {string} with a hop limit of {int}") { payload: String, hopLimit: Int ->
        assertIs<SendResult.Accepted>(nodes[0].broadcast(payload.encodeToByteArray(), hopLimit))
        scope.testScheduler.advanceTimeBy(TOPOLOGY_SETTLE_MILLIS)
    }

    fun assertionSteps() {
        then("Dave receives the broadcast {string} from Alice once") { payload: String ->
            assertEquals(1, inboxes[2].size)
            val message = inboxes[2].single()
            assertContentEquals(payload.encodeToByteArray(), message.payload)
            assertEquals(aliceId, message.sender)
            assertTrue(message.broadcast)
        }

        then("Dave receives {string} and {string} from Alice once each") { before: String, after: String ->
            assertEquals(listOf(before, after), inboxes[2].map { it.payload.decodeToString() })
            inboxes[2].forEach { assertEquals(aliceId, it.sender) }
        }

        then("Alice, Carol and Dave have links only to their new neighbors") {
            listOf(0 to listOf(2), 2 to listOf(0, 3), 3 to listOf(2)).forEach { (index, neighbors) ->
                assertEquals(
                    neighbors.map { nodeId(platforms[it]).toList() }.toSet(),
                    nodes[index]
                        .peers.value
                        .filter { it.reachability == Reachability.Direct }
                        .map { it.nodeId.toByteArray().toList() }
                        .toSet(),
                )
            }
        }

        then("Dave receives the direct message {string} from Alice once") { payload: String ->
            assertEquals(1, inboxes[2].size)
            val message = inboxes[2].single()
            assertContentEquals(payload.encodeToByteArray(), message.payload)
            assertEquals(aliceId, message.sender)
            assertEquals(false, message.broadcast)
        }

        then("Bob and Carol receive no direct messages") {
            inboxes.take(2).forEach { assertEquals(emptyList(), it) }
        }

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

private const val TOPOLOGY_SETTLE_MILLIS = 60_000L
