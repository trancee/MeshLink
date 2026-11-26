package ch.trancee.meshlink.internal

import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshLinkConfig
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.ReceivedMessage
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.meshLinkConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.random.Random

/** A message a node received, at virtual time [atMillis]. */
internal class Arrival(
    val atMillis: Long,
    val message: ReceivedMessage,
)

/**
 * `unsecured` chat nodes of [MESH] at [positions] on one air, where [seed] fixes every random draw:
 * latencies, jitter, peer choice, keys, message IDs and link drops. One seed replays one mesh byte
 * for byte. A mesh of 100+ nodes runs without [recording] link writes.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalMeshLinkApi::class)
internal class VirtualMesh(
    private val scope: TestScope,
    seed: Long,
    positions: List<Pair<Double, Double>>,
    recording: Boolean = true,
    val config: MeshLinkConfig = meshLinkConfig(Preset.OfflineChat, SecurityProfile.Unsecured) { meshId = MESH }.build(),
) {
    val air: VirtualAir =
        VirtualAir(scope.backgroundScope, TestClock(scope.testScheduler), SeededRandom(seed), recording = recording)
    private val crypto = SeededCrypto(seed)
    private val hazard = Random(seed)

    val platforms: List<VirtualPlatform> =
        positions.map { (x, y) ->
            air.platform(StandardTestDispatcher(scope.testScheduler)).also { air.move(it.radio, x, y) }
        }

    val nodes: List<MeshLink> = platforms.map { MeshLink.create(config, MeshPlatform.of(it, crypto)) }

    /** What each node received since [start]. */
    val inboxes: List<MutableList<Arrival>> = nodes.map { mutableListOf() }

    /** Starts every node and collects its messages. */
    suspend fun start() {
        nodes.forEachIndexed { index, node ->
            node.start()
            scope.backgroundScope.launch {
                node.messages.collect { inboxes[index] += Arrival(scope.testScheduler.currentTime, it) }
            }
        }
    }

    /** The NodeId of node [index] as some running node knows it, or null while no node does. */
    fun idOf(index: Int): NodeId? {
        val bytes = nodeId(platforms[index])
        return nodes.firstNotNullOfOrNull { node ->
            node.peers.value
                .firstOrNull { it.nodeId.toByteArray().contentEquals(bytes) }
                ?.nodeId
        }
    }

    /** Each node's connected component: nodes joined by a chain of radios within range of each other. */
    fun components(): IntArray {
        val component = IntArray(platforms.size) { it }

        fun root(index: Int): Int = if (component[index] == index) index else root(component[index])
        for (a in platforms.indices) {
            for (b in a + 1 until platforms.size) {
                if (air.inRange(platforms[a].radio, platforms[b].radio)) component[root(a)] = root(b)
            }
        }
        return IntArray(platforms.size) { root(it) }
    }

    /** Drops each open link at a random time, [meanMillis] apart on average; the nodes reconnect on their own. */
    fun dropLinksAtRandom(meanMillis: Long) {
        scope.backgroundScope.launch {
            while (true) {
                delay(HAZARD_STEP_MILLIS)
                platforms
                    .flatMap { platform -> platform.radio.ends.filter { it is VirtualCentralEnd && it.open } }
                    .filter { hazard.nextLong(meanMillis) < HAZARD_STEP_MILLIS }
                    .forEach { it.drop() }
            }
        }
    }

    /** Every write on every link, as `node time bytes`, in the order the nodes wrote them. */
    fun trace(): List<String> =
        platforms.flatMapIndexed { index, platform ->
            platform.radio.ends.flatMap { end -> end.writes.map { (at, bytes) -> "$index $at ${bytes.toHexString()}" } }
        }

    fun close() {
        nodes.forEach { it.close() }
    }

    private companion object {
        const val HAZARD_STEP_MILLIS = 1_000L
    }
}
