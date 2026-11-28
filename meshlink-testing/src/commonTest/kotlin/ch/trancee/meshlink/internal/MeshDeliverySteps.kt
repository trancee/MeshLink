package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.OutgoingMessage
import ch.trancee.meshlink.SendResult
import io.github.menjoo.cucumberkmp.core.runner.StepsBuilder
import io.github.menjoo.cucumberkmp.core.runner.given
import io.github.menjoo.cucumberkmp.core.runner.then
import io.github.menjoo.cucumberkmp.core.runner.whenever
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.coroutines.ContinuationInterceptor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A [message] a test sent from node [from] to node [to] at virtual time [atMillis]. */
private class Sent(
    val from: Int,
    val to: Int,
    val payload: ByteArray,
    val atMillis: Long,
    val message: OutgoingMessage,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal fun StepsBuilder.meshDeliverySteps() {
    with(MeshDeliveryScenario()) { register() }
}

/** One mesh delivery scenario: its mesh, the layout's positions and the messages sent. */
@OptIn(ExperimentalCoroutinesApi::class)
private class MeshDeliveryScenario {
    private lateinit var scope: TestScope
    private lateinit var mesh: SeededMesh
    private lateinit var random: Random
    private lateinit var positions: List<Pair<Double, Double>>
    private var meshInitialized = false
    private val sent = mutableListOf<Sent>()
    private val ids = mutableMapOf<Int, NodeId>()

    fun StepsBuilder.register() {
        after {
            if (meshInitialized) {
                mesh.close()
                scope.backgroundScope.cancel()
            }
        }
        setupSteps()
        sendSteps()
        movementSteps()
        assertionSteps()
    }

    private fun StepsBuilder.setupSteps() {
        given("{int} nodes are running in a {word} layout with seed {int}") { count: Int, layout: String, seed: Int ->
            val dispatcher = currentCoroutineContext()[ContinuationInterceptor] as TestDispatcher
            scope = TestScope(dispatcher)
            random = Random(seed)
            positions = layout(layout, count, random)
            mesh = SeededMesh(scope, seed.toLong(), positions)
            meshInitialized = true
            scope.launch { mesh.start() }
            scope.testScheduler.advanceTimeBy(MESH_SETTLE_MILLIS)
        }

        given("each link drops on average every {int} seconds and its nodes reconnect") { seconds: Int ->
            mesh.dropLinksAtRandom(seconds * MILLIS)
        }
    }

    private fun StepsBuilder.sendSteps() {
        whenever("each node sends a 256-byte message to {int} other nodes it can reach") { contacts: Int ->
            send(contacts, spreadMillis = 0)
        }

        whenever(
            "each node sends a 256-byte message to {int} other nodes it can reach over {int} minutes",
        ) { contacts: Int, minutes: Int ->
            send(contacts, spreadMillis = minutes * MINUTE_MILLIS)
        }

        whenever("node {int} sends a 256-byte message to node {int}") { from: Int, to: Int ->
            send(from, to)
            scope.testScheduler.advanceTimeBy(MESH_SETTLE_MILLIS)
        }
    }

    private fun StepsBuilder.movementSteps() {
        whenever("node {int} leaves") { index: Int ->
            id(index)
            mesh.air.move(mesh.platforms[index].radio, AWAY * (index + 1), 0.0)
        }

        whenever("node {int} returns to its place") { index: Int ->
            val (x, y) = positions[index]
            mesh.air.move(mesh.platforms[index].radio, x, y)
            scope.testScheduler.advanceTimeBy(MESH_SETTLE_MILLIS)
        }
    }

    private fun StepsBuilder.assertionSteps() {
        then("every message arrives once and intact within its lifetime") {
            val lifetimeMillis = mesh.config.messageLifetimeSeconds * MILLIS
            val deadline = sent.maxOf { it.atMillis } + lifetimeMillis

            fun waiting() = sent.any { message -> arrivals(mesh, message).isEmpty() }
            while (scope.testScheduler.currentTime < deadline && waiting()) {
                scope.testScheduler.advanceTimeBy(MILLIS)
            }
            scope.testScheduler.advanceTimeBy(DUPLICATE_WATCH_MILLIS)
            sent.forEach { message ->
                val arrivals = arrivals(mesh, message)
                val route = "${message.from} -> ${message.to}"
                assertEquals(1, arrivals.size, "message $route arrived ${arrivals.size} times")
                val arrival = arrivals.single()
                assertTrue(arrival.atMillis <= message.atMillis + lifetimeMillis)
                assertContentEquals(nodeId(mesh.platforms[message.from]), arrival.message.sender.toByteArray())
                assertEquals(false, arrival.message.broadcast)
            }
        }

        then("every sender sees its messages delivered") {
            val undelivered = sent.filter { it.message.status.value != DeliveryStatus.Delivered }
            assertEquals(emptyList(), undelivered.map { "${it.from} -> ${it.to}: ${it.message.status.value}" })
        }
    }

    // The NodeId of node [index], kept from when a running node first knew it, so it outlasts the node leaving.
    private fun id(index: Int): NodeId =
        ids.getOrPut(index) {
            val id = mesh.idOf(index)
            checkNotNull(id) { "no running node knows node $index" }
        }

    private suspend fun send(
        contacts: Int,
        spreadMillis: Long,
    ) {
        val components = mesh.components()
        val plan =
            mesh.nodes.indices
                .flatMap { from ->
                    mesh.nodes.indices
                        .filter { it != from && components[it] == components[from] }
                        .shuffled(random)
                        .take(contacts)
                        .map { to -> Triple(from, to, random.nextLong(spreadMillis + 1)) }
                }.sortedBy { it.third }
        val start = scope.testScheduler.currentTime
        plan.forEach { (from, to, offset) ->
            scope.testScheduler.advanceTimeBy(start + offset - scope.testScheduler.currentTime)
            send(from, to)
        }
        assertTrue(sent.isNotEmpty())
    }

    private suspend fun send(
        from: Int,
        to: Int,
    ) {
        val payload = random.nextBytes(PAYLOAD_BYTES)
        val result = assertIs<SendResult.Accepted>(mesh.nodes[from].send(id(to), payload))
        sent += Sent(from, to, payload, scope.testScheduler.currentTime, result.message)
    }
}

/** The arrivals at its destination of a message with [message]'s exact payload. */
private fun arrivals(
    mesh: SeededMesh,
    message: Sent,
): List<Arrival> = mesh.inboxes[message.to].filter { it.message.payload.contentEquals(message.payload) }

/**
 * [count] positions in [layout]: a `line` 25 m apart, a `star` of a hub and leaves out of each other's range,
 * or `random`.
 */
private fun layout(
    layout: String,
    count: Int,
    random: Random,
): List<Pair<Double, Double>> =
    when (layout) {
        "line" -> {
            List(count) { it * LINE_SPACING to 0.0 }
        }

        "star" -> {
            listOf(0.0 to 0.0) +
                List(count - 1) {
                    val angle = 2 * PI * it / (count - 1)
                    STAR_RADIUS * cos(angle) to STAR_RADIUS * sin(angle)
                }
        }

        else -> {
            // A square in which each node has on average RANDOM_PEERS others in range.
            val side = sqrt(count * PI * VirtualAir.DEFAULT_RANGE * VirtualAir.DEFAULT_RANGE / RANDOM_PEERS)
            List(count) { random.nextDouble(side) to random.nextDouble(side) }
        }
    }

private const val MESH_SETTLE_MILLIS = 60_000L
private const val MILLIS = 1_000L
private const val MINUTE_MILLIS = 60_000L
private const val DUPLICATE_WATCH_MILLIS = 60_000L
private const val PAYLOAD_BYTES = 256
private const val LINE_SPACING = 25.0

// Far out of every other node's 30 m range; each node that leaves goes to its own spot.
private const val AWAY = 1_000.0

// Five leaves 28 m from the hub are 33 m apart, out of each other's 30 m range.
private const val STAR_RADIUS = 28.0
private const val RANDOM_PEERS = 4.0
