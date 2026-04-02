package io.meshlink

import io.meshlink.config.testMeshLinkConfig
import io.meshlink.model.Message
import io.meshlink.transport.VirtualMeshTransport
import io.meshlink.util.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StressTest {

    private fun peerId(index: Int) = ByteArray(8) { (index * 0x10 + it).toByte() }

    @Test
    fun `10 peers discover each other`() = runTest {
        val count = 10
        val ids = (0 until count).map { peerId(it) }
        val transports = ids.map { VirtualMeshTransport(it) }

        for (i in 0 until count) {
            for (j in i + 1 until count) {
                transports[i].linkTo(transports[j])
            }
        }

        val config = testMeshLinkConfig { requireEncryption = false }
        val nodes = transports.map { MeshLink(it, config, coroutineContext) }
        nodes.forEach { it.start() }
        advanceUntilIdle()

        for (i in 0 until count) {
            for (j in 0 until count) {
                if (i != j) transports[i].simulateDiscovery(ids[j])
            }
        }
        advanceUntilIdle()

        for (i in 0 until count) {
            val health = nodes[i].meshHealth()
            assertEquals(
                count - 1, health.connectedPeers,
                "Peer $i should see ${count - 1} peers but saw ${health.connectedPeers}"
            )
        }

        nodes.forEach { it.stop() }
    }

    @Test
    fun `message delivery in 5-peer chain`() = runTest {
        val ids = (0 until 5).map { peerId(it) }
        val transports = ids.map { VirtualMeshTransport(it) }

        // Linear topology: 0↔1↔2↔3↔4
        for (i in 0 until 4) transports[i].linkTo(transports[i + 1])

        val config = testMeshLinkConfig { requireEncryption = false }
        val nodes = transports.map { MeshLink(it, config, coroutineContext) }
        nodes.forEach { it.start() }
        advanceUntilIdle()

        // Discover direct neighbors
        for (i in 0 until 4) {
            transports[i].simulateDiscovery(ids[i + 1])
            transports[i + 1].simulateDiscovery(ids[i])
        }
        advanceUntilIdle()

        // Set up forwarding routes: each peer routes toward peer 4
        val hexIds = ids.map { it.toHex() }
        nodes[0].addRoute(hexIds[4], hexIds[1], 1.0, 1u)
        nodes[1].addRoute(hexIds[4], hexIds[2], 1.0, 1u)
        nodes[2].addRoute(hexIds[4], hexIds[3], 1.0, 1u)
        nodes[3].addRoute(hexIds[4], hexIds[4], 1.0, 1u)

        val receiveJob = launch {
            val msg = nodes[4].messages.first()
            assertContentEquals(ids[0], msg.senderId)
            assertContentEquals("chain-msg".encodeToByteArray(), msg.payload)
        }
        advanceUntilIdle()

        val result = nodes[0].send(ids[4], "chain-msg".encodeToByteArray())
        assertTrue(result.isSuccess, "Send should succeed: $result")
        advanceUntilIdle()
        receiveJob.join()

        nodes.forEach { it.stop() }
    }

    @Test
    fun `concurrent sends from multiple sources`() = runTest {
        val ids = (0 until 3).map { peerId(it) }
        val transports = ids.map { VirtualMeshTransport(it) }

        // Full mesh
        for (i in 0 until 3) {
            for (j in i + 1 until 3) {
                transports[i].linkTo(transports[j])
            }
        }

        val config = testMeshLinkConfig { requireEncryption = false }
        val nodes = transports.map { MeshLink(it, config, coroutineContext) }
        nodes.forEach { it.start() }
        advanceUntilIdle()

        // All discover each other
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                if (i != j) transports[i].simulateDiscovery(ids[j])
            }
        }
        advanceUntilIdle()

        val received = Array(3) { mutableListOf<Message>() }
        val collectors = (0 until 3).map { i ->
            launch { nodes[i].messages.collect { received[i].add(it) } }
        }
        advanceUntilIdle()

        // Each peer sends to the next: 0→1, 1→2, 2→0
        val r0 = nodes[0].send(ids[1], "from-0".encodeToByteArray())
        val r1 = nodes[1].send(ids[2], "from-1".encodeToByteArray())
        val r2 = nodes[2].send(ids[0], "from-2".encodeToByteArray())
        assertTrue(r0.isSuccess, "Send 0→1 failed: $r0")
        assertTrue(r1.isSuccess, "Send 1→2 failed: $r1")
        assertTrue(r2.isSuccess, "Send 2→0 failed: $r2")
        advanceUntilIdle()

        assertEquals(1, received[0].size, "Peer 0 should receive 1 message from peer 2")
        assertEquals(1, received[1].size, "Peer 1 should receive 1 message from peer 0")
        assertEquals(1, received[2].size, "Peer 2 should receive 1 message from peer 1")

        assertContentEquals("from-2".encodeToByteArray(), received[0][0].payload)
        assertContentEquals("from-0".encodeToByteArray(), received[1][0].payload)
        assertContentEquals("from-1".encodeToByteArray(), received[2][0].payload)

        collectors.forEach { it.cancel() }
        nodes.forEach { it.stop() }
    }

    @Test
    fun `rapid start-stop cycles do not leak`() = runTest {
        val id = peerId(0)
        val transport = VirtualMeshTransport(id)
        val config = testMeshLinkConfig { requireEncryption = false }
        val peer = MeshLink(transport, config, coroutineContext)

        repeat(10) { cycle ->
            val startResult = peer.start()
            assertTrue(startResult.isSuccess, "Start cycle $cycle failed: $startResult")
            advanceUntilIdle()
            peer.stop()
        }

        // Verify the peer still works after rapid cycling
        val finalStart = peer.start()
        assertTrue(finalStart.isSuccess, "Final start after 10 cycles failed")
        advanceUntilIdle()
        peer.stop()
    }

    @Test
    fun `large message chunking under load`() = runTest {
        val idA = peerId(0)
        val idB = peerId(1)
        val tA = VirtualMeshTransport(idA)
        val tB = VirtualMeshTransport(idB)
        tA.linkTo(tB)

        val config = testMeshLinkConfig {
            requireEncryption = false
            maxMessageSize = 100_000
            mtu = 185
        }
        val a = MeshLink(tA, config, coroutineContext)
        val b = MeshLink(tB, config, coroutineContext)
        a.start(); b.start()
        advanceUntilIdle()

        tA.simulateDiscovery(idB)
        tB.simulateDiscovery(idA)
        advanceUntilIdle()

        // 10 KB payload requires ~61 chunks at 164 bytes each (185 MTU - 21 header)
        val largePayload = ByteArray(10_000) { (it % 256).toByte() }

        val receiveJob = launch {
            val msg = b.messages.first()
            assertContentEquals(largePayload, msg.payload)
            assertContentEquals(idA, msg.senderId)
        }
        advanceUntilIdle()

        val result = a.send(idB, largePayload)
        assertTrue(result.isSuccess, "Large message send should succeed: $result")
        advanceUntilIdle()
        receiveJob.join()

        assertTrue(tA.sentData.size > 1, "Large message should be chunked into multiple packets")

        a.stop(); b.stop()
    }

    @Test
    fun `broadcast floods to all peers`() = runTest {
        val count = 5
        val ids = (0 until count).map { peerId(it) }
        val transports = ids.map { VirtualMeshTransport(it) }

        // Full mesh
        for (i in 0 until count) {
            for (j in i + 1 until count) {
                transports[i].linkTo(transports[j])
            }
        }

        val config = testMeshLinkConfig { requireEncryption = false }
        val nodes = transports.map { MeshLink(it, config, coroutineContext) }
        nodes.forEach { it.start() }
        advanceUntilIdle()

        // All discover each other
        for (i in 0 until count) {
            for (j in 0 until count) {
                if (i != j) transports[i].simulateDiscovery(ids[j])
            }
        }
        advanceUntilIdle()

        // Collect messages on nodes 1–4
        val received = (1 until count).map { mutableListOf<Message>() }
        val collectors = (1 until count).map { i ->
            launch { nodes[i].messages.collect { received[i - 1].add(it) } }
        }
        advanceUntilIdle()

        // Peer 0 broadcasts
        val result = nodes[0].broadcast("flood".encodeToByteArray(), maxHops = 3u)
        assertTrue(result.isSuccess, "Broadcast should succeed: $result")
        advanceUntilIdle()

        for (i in 0 until count - 1) {
            assertEquals(
                1, received[i].size,
                "Peer ${i + 1} should receive broadcast but got ${received[i].size} messages"
            )
            assertContentEquals("flood".encodeToByteArray(), received[i][0].payload)
            assertContentEquals(ids[0], received[i][0].senderId)
        }

        collectors.forEach { it.cancel() }
        nodes.forEach { it.stop() }
    }
}
