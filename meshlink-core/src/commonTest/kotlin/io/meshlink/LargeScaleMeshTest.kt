package io.meshlink

import io.meshlink.config.meshLinkConfig
import io.meshlink.transport.VirtualMeshTransport
import io.meshlink.util.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LargeScaleMeshTest {

    @Test
    fun fiftyPeerLineTopologyConverges() = runTest {
        val peerCount = 50
        val config = meshLinkConfig { gossipIntervalMs = 50L }

        // Create 50 transports with unique 16-byte peer IDs
        val transports = (0 until peerCount).map { i ->
            VirtualMeshTransport(ByteArray(16) { if (it == 0) i.toByte() else 0 })
        }

        // Link as a line: 0↔1↔2↔...↔49
        for (i in 0 until peerCount - 1) {
            transports[i].linkTo(transports[i + 1])
        }

        // Create and start 50 MeshLink nodes
        val nodes = transports.map { t -> MeshLink(t, config, coroutineContext) }
        nodes.forEach { it.start() }
        testScheduler.advanceTimeBy(1L)

        // Simulate discovery between neighbors
        for (i in 0 until peerCount - 1) {
            transports[i].simulateDiscovery(transports[i + 1].localPeerId)
            transports[i + 1].simulateDiscovery(transports[i].localPeerId)
        }
        testScheduler.advanceTimeBy(1L)

        // Add direct neighbor routes
        for (i in 0 until peerCount - 1) {
            val hexI = transports[i].localPeerId.toHex()
            val hexJ = transports[i + 1].localPeerId.toHex()
            nodes[i].addRoute(hexJ, hexJ, 1.0, 1u)
            nodes[i + 1].addRoute(hexI, hexI, 1.0, 1u)
        }

        // Run enough gossip intervals for full propagation across 50 hops
        // Each interval propagates routes 1 more hop, so need ~50 intervals
        testScheduler.advanceTimeBy(50 * 50L + 100)

        // Verify convergence: node 0 should know routes to many peers
        val healthFirst = nodes[0].meshHealth()
        val healthMiddle = nodes[25].meshHealth()

        // Stop all nodes
        nodes.forEach { it.stop() }
        testScheduler.advanceTimeBy(1L)

        // Node 0 should have learned routes to distant peers via gossip
        assertTrue(healthFirst.avgRouteCost > 0.0,
            "Node 0 should have learned routes, avgRouteCost=${healthFirst.avgRouteCost}")
        assertTrue(healthMiddle.connectedPeers >= 2,
            "Middle node should have at least 2 connected peers, got ${healthMiddle.connectedPeers}")
    }

    @Test
    fun twentyPeerFullMeshConverges() = runTest {
        val peerCount = 20
        val config = meshLinkConfig { gossipIntervalMs = 50L }

        // Create 20 transports
        val transports = (0 until peerCount).map { i ->
            VirtualMeshTransport(ByteArray(16) { if (it == 0) (0x80 + i).toByte() else 0 })
        }

        // Full mesh: every peer linked to every other
        for (i in 0 until peerCount) {
            for (j in i + 1 until peerCount) {
                transports[i].linkTo(transports[j])
            }
        }

        val nodes = transports.map { t -> MeshLink(t, config, coroutineContext) }
        nodes.forEach { it.start() }
        testScheduler.advanceTimeBy(1L)

        // Discover all neighbors
        for (i in 0 until peerCount) {
            for (j in 0 until peerCount) {
                if (i != j) {
                    transports[i].simulateDiscovery(transports[j].localPeerId)
                }
            }
        }
        testScheduler.advanceTimeBy(1L)

        // Add direct neighbor routes
        for (i in 0 until peerCount) {
            for (j in 0 until peerCount) {
                if (i != j) {
                    val hexJ = transports[j].localPeerId.toHex()
                    nodes[i].addRoute(hexJ, hexJ, 1.0, 1u)
                }
            }
        }

        // 2 gossip intervals should be enough for full mesh convergence
        testScheduler.advanceTimeBy(200L)

        val healths = nodes.map { it.meshHealth() }

        nodes.forEach { it.stop() }
        testScheduler.advanceTimeBy(1L)

        // Every node should know all other peers as connected
        for (i in 0 until peerCount) {
            assertTrue(healths[i].connectedPeers >= peerCount - 1,
                "Node $i should see ${peerCount - 1} peers, got ${healths[i].connectedPeers}")
        }
    }
}
