@file:OptIn(ExperimentalCoroutinesApi::class)

package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.OutgoingMessage
import ch.trancee.meshlink.Reachability
import ch.trancee.meshlink.ReceivedMessage
import ch.trancee.meshlink.SendOptions
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

internal fun StepsBuilder.topologySteps() {
    val topology = Topology()
    with(topology) {
        after {
            nodes.forEach { it.close() }
            if (scopeInitialized) scope.backgroundScope.cancel()
        }

        given("Alice, Bob, Carol and Dave are running in a line with links only between neighbors") {
            createTopology(
                listOf(0.0 to 0.0, 25.0 to 0.0, 50.0 to 0.0, 75.0 to 0.0),
                listOf(listOf(1), listOf(0, 2), listOf(1, 3), listOf(2)),
            )
        }

        given("Alice, Bob, Carol and Dave are running with two routes from Alice to Dave") {
            createTopology(
                listOf(0.0 to 0.0, 25.0 to 0.0, 0.0 to 25.0, 25.0 to 25.0),
                listOf(listOf(1, 2), listOf(0, 3), listOf(0, 3), listOf(1, 2)),
            )
        }

        // Bob and Carol each hear the other's copy of a flooded frame, so either could learn a route through the other.
        given("Alice, Bob, Carol and Dave are running with Bob and Carol linked to each other and to both ends") {
            createTopology(
                listOf(0.0 to 0.0, 20.0 to -12.0, 20.0 to 12.0, 40.0 to 0.0),
                listOf(listOf(1, 2), listOf(0, 2, 3), listOf(0, 1, 3), listOf(1, 2)),
            )
        }

        given("Alice is collecting messages") {
            scope.backgroundScope.launch { nodes[0].messages.collect { aliceInbox += it } }
            scope.testScheduler.runCurrent()
        }

        given("Bob, Carol and Dave are collecting messages") {
            inboxes.indices.forEach { collect(it) }
            scope.testScheduler.runCurrent()
        }

        whenever("Alice broadcasts {string} with a hop limit of {int}") { payload: String, hopLimit: Int ->
            assertIs<SendResult.Accepted>(nodes[0].broadcast(payload.encodeToByteArray(), hopLimit))
            scope.testScheduler.advanceTimeBy(TOPOLOGY_SETTLE_MILLIS)
        }
    }
    routingSteps(topology)
    receiptSteps(topology)
    catchUpSteps(topology)
    assertionSteps(topology)
}

private fun StepsBuilder.routingSteps(topology: Topology) =
    with(topology) {
        given("Alice knows Dave's identity") {
            daveId =
                nodes[2]
                    .peers.value
                    .single { it.nodeId.toByteArray().contentEquals(nodeId(platforms[3])) }
                    .nodeId
        }

        whenever("Alice sends {string} to Dave through the mesh") { payload: String ->
            send(payload)
        }

        given("Alice has sent {string} to Dave") { payload: String ->
            send(payload)
            assertEquals(listOf(payload), inboxes[2].map { it.payload.decodeToString() })
        }

        given("Dave has sent {string} to Alice through the mesh") { payload: String ->
            assertIs<SendResult.Accepted>(nodes[3].send(aliceId, payload.encodeToByteArray()))
            scope.testScheduler.advanceTimeBy(REPLY_DELAY_MILLIS)
            assertEquals(listOf(payload), aliceInbox.map { it.payload.decodeToString() })
        }

        whenever("Alice replies {string} to the sender of {string}") { reply: String, payload: String ->
            val sender = aliceInbox.single { it.payload.decodeToString() == payload }.sender
            assertIs<SendResult.Accepted>(nodes[0].send(sender, reply.encodeToByteArray()))
            scope.testScheduler.advanceTimeBy(TOPOLOGY_SETTLE_MILLIS)
        }

        whenever("Bob leaves and Carol and Dave move closer") {
            air.move(platforms[1].radio, 1_000.0, 0.0)
            air.move(platforms[2].radio, 25.0, 0.0)
            air.move(platforms[3].radio, 50.0, 0.0)
            scope.testScheduler.advanceTimeBy(TOPOLOGY_SETTLE_MILLIS)
        }

        whenever("Bob leaves the two-route mesh") {
            air.move(platforms[1].radio, 1_000.0, 0.0)
            scope.testScheduler.advanceTimeBy(TOPOLOGY_SETTLE_MILLIS)
        }
    }

private fun StepsBuilder.receiptSteps(topology: Topology) =
    with(topology) {
        given("Alice has sent {string} to Dave while Dave's app takes no messages") { payload: String ->
            send(payload)
            assertEquals(DeliveryStatus.Sent, sends.getValue(payload).status.value)
        }

        whenever("Dave's app starts taking messages") {
            collect(2)
            scope.testScheduler.advanceTimeBy(TOPOLOGY_SETTLE_MILLIS)
        }

        whenever("Alice sends {string} directly to Dave") { payload: String ->
            send(payload, SendOptions(directOnly = true))
        }

        given("Alice has sent {string} directly to Dave") { payload: String ->
            send(payload, SendOptions(directOnly = true))
            assertEquals(DeliveryStatus.Held, sends.getValue(payload).status.value)
        }

        whenever("Dave moves next to Alice") {
            air.move(platforms[3].radio, -10.0, 0.0)
            scope.testScheduler.advanceTimeBy(TOPOLOGY_SETTLE_MILLIS)
        }

        whenever("the lifetime of Alice's messages passes") {
            scope.testScheduler.advanceTimeBy(LIFETIME_PASSED_MILLIS)
        }

        then("Alice sees {string} {word}") { payload: String, status: String ->
            val expected = STATUSES.getValue(status)
            assertEquals(expected, sends.getValue(payload).status.value)
        }

        then("Bob, Carol and Dave receive no direct messages") {
            inboxes.forEach { assertEquals(emptyList(), it) }
        }
    }

private fun StepsBuilder.catchUpSteps(topology: Topology) =
    with(topology) {
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

private fun StepsBuilder.assertionSteps(topology: Topology) =
    with(topology) {
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

/** The nodes of one topology scenario, on [air] in [scope], and what they received and sent. */
private class Topology {
    lateinit var scope: TestScope
    lateinit var air: VirtualAir
    lateinit var aliceId: NodeId
    lateinit var daveId: NodeId
    var scopeInitialized = false
    val nodes = mutableListOf<MeshLink>()
    val platforms = mutableListOf<VirtualPlatform>()
    val inboxes = List(3) { mutableListOf<ReceivedMessage>() }
    val aliceInbox = mutableListOf<ReceivedMessage>()
    val sends = mutableMapOf<String, OutgoingMessage>()

    suspend fun createTopology(
        positions: List<Pair<Double, Double>>,
        neighbors: List<List<Int>>,
    ) {
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor] as TestDispatcher
        scope = TestScope(dispatcher)
        scopeInitialized = true
        air = scope.air()
        platforms +=
            positions.map { (x, y) ->
                air.platform(StandardTestDispatcher(scope.testScheduler)).also {
                    air.move(it.radio, x, y)
                }
            }
        nodes += platforms.map { node(it) }
        nodes.forEach { it.start() }
        scope.testScheduler.advanceTimeBy(TOPOLOGY_SETTLE_MILLIS)
        nodes.forEachIndexed { index, node ->
            assertEquals(
                neighbors[index].map { nodeId(platforms[it]).toList() }.toSet(),
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

    /** Collects the messages of Bob (0), Carol (1) or Dave (2). */
    fun collect(index: Int) {
        scope.backgroundScope.launch { nodes[index + 1].messages.collect { inboxes[index] += it } }
    }

    /** Alice sends [payload] to Dave as [options] say. */
    fun send(
        payload: String,
        options: SendOptions = SendOptions(),
    ) {
        val result = nodes[0].send(daveId, payload.encodeToByteArray(), options)
        sends[payload] = assertIs<SendResult.Accepted>(result).message
        scope.testScheduler.advanceTimeBy(TOPOLOGY_SETTLE_MILLIS)
    }
}

private const val TOPOLOGY_SETTLE_MILLIS = 60_000L

private val STATUSES =
    mapOf("delivered" to DeliveryStatus.Delivered, "held" to DeliveryStatus.Held, "expired" to DeliveryStatus.Expired)

// The 30-minute lifetime of `OfflineChat` messages and the 30-second clock-skew allowance, from a settled send.
private const val LIFETIME_PASSED_MILLIS = 30 * 60_000L + 31_000L

// Within reverse-route-timeout (6 s), so the reply finds the reverse routes the first message taught.
private const val REPLY_DELAY_MILLIS = 1_000L
