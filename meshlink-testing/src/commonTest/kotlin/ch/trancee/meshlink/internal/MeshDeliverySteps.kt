package ch.trancee.meshlink.internal

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

/** A message a test sent from node [from] to node [to] at virtual time [atMillis]. */
private class Sent(
    val from: Int,
    val to: Int,
    val payload: ByteArray,
    val atMillis: Long,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal fun StepsBuilder.meshDeliverySteps() {
    lateinit var scope: TestScope
    lateinit var mesh: VirtualMesh
    lateinit var random: Random
    var meshInitialized = false
    val sent = mutableListOf<Sent>()

    after {
        if (meshInitialized) {
            mesh.close()
            scope.backgroundScope.cancel()
        }
    }

    given("{int} nodes are running in a {word} layout with seed {int}") { count: Int, layout: String, seed: Int ->
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor] as TestDispatcher
        scope = TestScope(dispatcher)
        random = Random(seed)
        mesh = VirtualMesh(scope, seed.toLong(), layout(layout, count, random))
        meshInitialized = true
        scope.launch { mesh.start() }
        scope.testScheduler.advanceTimeBy(MESH_SETTLE_MILLIS)
    }

    given("each link drops on average every {int} seconds and its nodes reconnect") { seconds: Int ->
        mesh.dropLinksAtRandom(seconds * MILLIS)
    }

    suspend fun send(
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
            val payload = random.nextBytes(PAYLOAD_BYTES)
            val id = checkNotNull(mesh.idOf(to)) { "no running node knows node $to" }
            assertIs<SendResult.Accepted>(mesh.nodes[from].send(id, payload))
            sent += Sent(from, to, payload, scope.testScheduler.currentTime)
        }
        assertTrue(sent.isNotEmpty())
    }

    whenever("each node sends a 256-byte message to {int} other nodes it can reach") { contacts: Int ->
        send(contacts, spreadMillis = 0)
    }

    whenever(
        "each node sends a 256-byte message to {int} other nodes it can reach over {int} minutes",
    ) { contacts: Int, minutes: Int ->
        send(contacts, spreadMillis = minutes * MINUTE_MILLIS)
    }

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
            assertEquals(1, arrivals.size, "message ${message.from} -> ${message.to} arrived ${arrivals.size} times")
            val arrival = arrivals.single()
            assertTrue(arrival.atMillis <= message.atMillis + lifetimeMillis)
            assertContentEquals(nodeId(mesh.platforms[message.from]), arrival.message.sender.toByteArray())
            assertEquals(false, arrival.message.broadcast)
        }
    }
}

/** The arrivals at its destination of a message with [message]'s exact payload. */
private fun arrivals(
    mesh: VirtualMesh,
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

// Five leaves 28 m from the hub are 33 m apart, out of each other's 30 m range.
private const val STAR_RADIUS = 28.0
private const val RANDOM_PEERS = 4.0
