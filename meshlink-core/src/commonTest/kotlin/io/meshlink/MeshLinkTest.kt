package io.meshlink

import io.meshlink.config.meshLinkConfig
import io.meshlink.model.Message
import io.meshlink.model.PeerEvent
import io.meshlink.transport.VirtualMeshTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalUuidApi::class)
class MeshLinkTest {

    private val peerIdAlice = ByteArray(16) { (0xA0 + it).toByte() }
    private val peerIdBob = ByteArray(16) { (0xB0 + it).toByte() }

    @Test
    fun twoPeersDiscoverEachOther() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)

        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        alice.start()

        // Let MeshLink's internal collector coroutines start
        advanceUntilIdle()

        val discoveryJob = launch {
            val event = alice.peers.first()
            assertIs<PeerEvent.Discovered>(event)
        }

        // Simulate Bob advertising to Alice
        transportAlice.simulateDiscovery(peerIdBob)

        advanceUntilIdle()
        discoveryJob.join()
        alice.stop()
    }

    @Test
    fun sendMessageAndReceiveIt() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)

        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        val bob = MeshLink(transportBob, meshLinkConfig(), coroutineContext)
        alice.start()
        bob.start()
        advanceUntilIdle()

        // Discover each other
        transportAlice.simulateDiscovery(peerIdBob)
        transportBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        // Bob listens for messages
        val receiveJob = launch {
            val msg = bob.messages.first()
            assertContentEquals(peerIdAlice, msg.senderId)
            assertContentEquals("hello".encodeToByteArray(), msg.payload)
        }

        // Alice sends to Bob
        val result = alice.send(peerIdBob, "hello".encodeToByteArray())
        assert(result.isSuccess) { "send failed: $result" }

        advanceUntilIdle()
        receiveJob.join()

        alice.stop()
        bob.stop()
    }

    @Test
    fun largePayloadIsChunkedAndReassembled() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)

        // MTU=185, header=21, so chunk payload = 164 bytes
        val alice = MeshLink(transportAlice, meshLinkConfig { mtu = 185 }, coroutineContext)
        val bob = MeshLink(transportBob, meshLinkConfig { mtu = 185 }, coroutineContext)
        alice.start()
        bob.start()
        advanceUntilIdle()

        transportAlice.simulateDiscovery(peerIdBob)
        transportBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        // 500 bytes > 164 chunk size → requires 4 chunks (164+164+164+8)
        val largePayload = ByteArray(500) { (it % 256).toByte() }

        val receiveJob = launch {
            val msg = bob.messages.first()
            assertContentEquals(largePayload, msg.payload)
        }

        val result = alice.send(peerIdBob, largePayload)
        assert(result.isSuccess) { "send failed: $result" }

        advanceUntilIdle()
        receiveJob.join()

        alice.stop()
        bob.stop()
    }

    @Test
    fun sendBeforeStartThrowsIllegalState() = runTest {
        val transport = VirtualMeshTransport(peerIdAlice)
        val mesh = MeshLink(transport, meshLinkConfig(), coroutineContext)

        val exception = assertFailsWith<IllegalStateException> {
            mesh.send(peerIdBob, "hello".encodeToByteArray())
        }
        assert(exception.message!!.contains("not started"))
    }

    @Test
    fun stopThenStartAgainWorks() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)

        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        val bob = MeshLink(transportBob, meshLinkConfig(), coroutineContext)

        // First cycle
        alice.start(); bob.start()
        advanceUntilIdle()
        alice.stop(); bob.stop()

        // Restart
        alice.start(); bob.start()
        advanceUntilIdle()

        transportAlice.simulateDiscovery(peerIdBob)
        transportBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        val receiveJob = launch {
            val msg = bob.messages.first()
            assertContentEquals("after restart".encodeToByteArray(), msg.payload)
        }

        val result = alice.send(peerIdBob, "after restart".encodeToByteArray())
        assert(result.isSuccess)

        advanceUntilIdle()
        receiveJob.join()

        alice.stop()
        bob.stop()
    }

    @Test
    fun configDslAppliesOverridesWithDefaults() {
        val config = meshLinkConfig {
            maxMessageSize = 50_000
        }
        assertEquals(50_000, config.maxMessageSize)
        assertEquals(1_048_576, config.bufferCapacity) // default preserved
        assertEquals(185, config.mtu) // default preserved
    }

    @Test
    fun configDslDefaultsAppliedWhenNoOverrides() {
        val config = meshLinkConfig()
        assertEquals(100_000, config.maxMessageSize)
        assertEquals(1_048_576, config.bufferCapacity)
        assertEquals(185, config.mtu)
    }

    @Test
    fun sendReturnsBufferFullWhenCapacityExceeded() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)

        // Tiny buffer: 500 bytes
        val alice = MeshLink(transportAlice, meshLinkConfig { bufferCapacity = 500 }, coroutineContext)
        alice.start()
        advanceUntilIdle()

        transportAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        // Send a payload that exceeds the buffer
        val result = alice.send(peerIdBob, ByteArray(600))
        assert(result.isFailure) { "Expected failure, got $result" }

        alice.stop()
    }

    @Test
    fun concurrentSendsDoNotCorrupt() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)

        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        val bob = MeshLink(transportBob, meshLinkConfig(), coroutineContext)
        alice.start(); bob.start()
        advanceUntilIdle()

        transportAlice.simulateDiscovery(peerIdBob)
        transportBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        val messageCount = 20

        val receiveJob = launch {
            val received = bob.messages.take(messageCount).toList()
            assertEquals(messageCount, received.size)
            received.forEach { msg ->
                assertContentEquals(peerIdAlice, msg.senderId)
            }
        }

        // Fire 20 concurrent sends
        val sendJobs = (0 until messageCount).map { i ->
            launch {
                val result = alice.send(peerIdBob, "msg-$i".encodeToByteArray())
                assert(result.isSuccess) { "send $i failed: $result" }
            }
        }
        sendJobs.forEach { it.join() }

        advanceUntilIdle()
        receiveJob.join()

        alice.stop()
        bob.stop()
    }
}
