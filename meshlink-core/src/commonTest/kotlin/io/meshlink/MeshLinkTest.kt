package io.meshlink

import io.meshlink.config.meshLinkConfig
import io.meshlink.model.Message
import io.meshlink.model.PeerEvent
import io.meshlink.transport.VirtualMeshTransport
import io.meshlink.wire.WireCodec
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
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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

    // --- Cycle 1: Chunk ACK flow ---

    @Test
    fun receiverSendsChunkAckAfterReassembly() = runTest {
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

        // Bob listens so reassembly completes
        val receiveJob = launch { bob.messages.first() }

        val msgId = alice.send(peerIdBob, "hello".encodeToByteArray()).getOrThrow()
        advanceUntilIdle()
        receiveJob.join()

        // Bob's transport should have sent a chunk_ack back to Alice
        val acksSent = transportBob.sentData.filter { (_, data) ->
            data.isNotEmpty() && data[0] == WireCodec.TYPE_CHUNK_ACK
        }
        assertEquals(1, acksSent.size, "Expected exactly 1 chunk_ack from Bob")

        // The ACK should reference the correct messageId
        val ack = WireCodec.decodeChunkAck(acksSent[0].second)
        assertContentEquals(msgId.toByteArray(), ack.messageId)

        alice.stop(); bob.stop()
    }

    // --- Cycle 2: Sender delivery confirmation ---

    @Test
    fun senderReceivesDeliveryConfirmationViaFlow() = runTest {
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

        // Bob consumes messages so reassembly triggers ACK
        val bobJob = launch { bob.messages.first() }

        // Subscribe for delivery confirmation before sending
        var confirmedId: Uuid? = null
        val confirmJob = launch {
            confirmedId = alice.deliveryConfirmations.first()
        }
        advanceUntilIdle()

        val msgId = alice.send(peerIdBob, "hello".encodeToByteArray()).getOrThrow()
        advanceUntilIdle()
        bobJob.join()
        advanceUntilIdle()
        confirmJob.join()

        assertEquals(msgId, confirmedId)

        alice.stop(); bob.stop()
    }

    // --- Cycle 3: PeerEvent.Lost ---

    @Test
    fun peerLostEventEmittedWhenPeerDisappears() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        alice.start()
        advanceUntilIdle()

        // Discover Bob
        transportAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()
        val discovered = alice.peers.first()
        assertIs<PeerEvent.Discovered>(discovered)

        // Bob disappears
        val lostJob = launch {
            // Skip past the replayed Discovered event, wait for Lost
            val events = alice.peers.take(2).toList()
            assertIs<PeerEvent.Lost>(events[1])
            assertContentEquals(peerIdBob, (events[1] as PeerEvent.Lost).peerId)
        }

        transportAlice.simulatePeerLost(peerIdBob)
        advanceUntilIdle()
        lostJob.join()

        alice.stop()
    }

    // --- Cycle 4: pause() stops discovery ---

    @Test
    fun pauseStopsEmittingPeerDiscoveries() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        alice.start()
        advanceUntilIdle()

        // Actively collect all peer events
        val events = mutableListOf<PeerEvent>()
        val collector = launch { alice.peers.collect { events.add(it) } }
        advanceUntilIdle()

        // Discover Bob — should emit
        transportAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()
        assertEquals(1, events.size, "Bob should be discovered")

        // Pause
        alice.pause()
        advanceUntilIdle()

        // Simulate Charlie while paused — should NOT emit
        val peerIdCharlie = ByteArray(16) { (0xC0 + it).toByte() }
        transportAlice.simulateDiscovery(peerIdCharlie)
        advanceUntilIdle()
        assertEquals(1, events.size, "Charlie should be suppressed while paused")

        collector.cancel()
        alice.stop()
    }

    // --- Cycle 5: send while paused, deliver on resume ---

    @Test
    fun sendWhilePausedQueuesAndDeliversAfterResume() = runTest {
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

        // Pause Alice
        alice.pause()
        advanceUntilIdle()

        // Send while paused — should NOT throw, should queue
        val result = alice.send(peerIdBob, "queued message".encodeToByteArray())
        assert(result.isSuccess) { "send while paused should succeed (queue silently)" }
        advanceUntilIdle()

        // Bob should NOT have received anything yet
        val bobEvents = mutableListOf<Message>()
        val bobCollector = launch { bob.messages.collect { bobEvents.add(it) } }
        advanceUntilIdle()
        assertEquals(0, bobEvents.size, "No messages should arrive while sender is paused")

        // Resume Alice — queued message should be delivered
        alice.resume()
        advanceUntilIdle()

        assertEquals(1, bobEvents.size, "Queued message should arrive after resume")
        assertContentEquals("queued message".encodeToByteArray(), bobEvents[0].payload)

        bobCollector.cancel()
        alice.stop(); bob.stop()
    }

    // --- Cycle 6: resume re-discovers peers ---

    @Test
    fun resumeTriggersNewPeerDiscoveries() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        alice.start()
        advanceUntilIdle()

        val events = mutableListOf<PeerEvent>()
        val collector = launch { alice.peers.collect { events.add(it) } }
        advanceUntilIdle()

        // Discover Bob
        transportAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()
        assertEquals(1, events.size)

        // Pause then resume
        alice.pause()
        advanceUntilIdle()
        alice.resume()
        advanceUntilIdle()

        // After resume, new advertisements should work again
        val peerIdCharlie = ByteArray(16) { (0xC0 + it).toByte() }
        transportAlice.simulateDiscovery(peerIdCharlie)
        advanceUntilIdle()

        assertEquals(2, events.size, "Charlie should be discovered after resume")
        assertIs<PeerEvent.Discovered>(events[1])
        assertContentEquals(peerIdCharlie, (events[1] as PeerEvent.Discovered).peerId)

        collector.cancel()
        alice.stop()
    }

    // --- Cycle 7 (Phase 3): Progress callback ---

    @Test
    fun progressCallbackFiresWithAccurateFraction() = runTest {
        // Use a small MTU so "hello world!!" (13 bytes) splits into multiple chunks
        // CHUNK_HEADER_SIZE = 21, MTU = 25 → chunkSize = 4 → 13/4 = 4 chunks
        val config = meshLinkConfig { mtu = 25 }
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)

        val alice = MeshLink(transportAlice, config, coroutineContext)
        val bob = MeshLink(transportBob, config, coroutineContext)
        alice.start(); bob.start()
        advanceUntilIdle()

        transportAlice.simulateDiscovery(peerIdBob)
        transportBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        // Collect progress events
        val progressEvents = mutableListOf<io.meshlink.model.TransferProgress>()
        val progressJob = launch { alice.transferProgress.collect { progressEvents.add(it) } }
        advanceUntilIdle()

        // Bob consumes messages
        val bobJob = launch { bob.messages.first() }

        val payload = "hello world!!".encodeToByteArray() // 13 bytes → 4 chunks at chunkSize=4
        val msgId = alice.send(peerIdBob, payload).getOrThrow()
        advanceUntilIdle()
        bobJob.join()
        advanceUntilIdle()

        // Should have received progress events, final one should have fraction = 1.0
        assertTrue(progressEvents.isNotEmpty(), "Expected at least one progress event")
        val last = progressEvents.last()
        assertEquals(msgId, last.messageId)
        assertEquals(1f, last.fraction, "Final progress should be 1.0")
        assertEquals(4, last.totalChunks)

        progressJob.cancel()
        alice.stop(); bob.stop()
    }

    // --- Cycle 8 (Phase 3): SACK retransmission ---

    @Test
    fun droppedChunkRetransmittedViaSack() = runTest {
        // MTU 25 → chunkSize=4 → "abcdefghijklm" (13 bytes) → 4 chunks
        val config = meshLinkConfig { mtu = 25 }
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)

        val alice = MeshLink(transportAlice, config, coroutineContext)
        val bob = MeshLink(transportBob, config, coroutineContext)
        alice.start(); bob.start()
        advanceUntilIdle()

        transportAlice.simulateDiscovery(peerIdBob)
        transportBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        // Drop chunk with seqNum=1 on Alice's transport (first time only)
        var droppedOnce = false
        transportAlice.dropFilter = { data ->
            if (!droppedOnce && data.isNotEmpty() && data[0] == WireCodec.TYPE_CHUNK) {
                val decoded = WireCodec.decodeChunk(data)
                if (decoded.sequenceNumber.toInt() == 1) {
                    droppedOnce = true
                    true // drop this packet
                } else false
            } else false
        }

        val payload = "abcdefghijklm".encodeToByteArray() // 13 bytes → 4 chunks

        // Bob listens for message
        val bobJob = launch { bob.messages.first() }

        // Alice listens for delivery confirmation
        var confirmedId: Uuid? = null
        val confirmJob = launch { confirmedId = alice.deliveryConfirmations.first() }
        advanceUntilIdle()

        val msgId = alice.send(peerIdBob, payload).getOrThrow()
        advanceUntilIdle()
        bobJob.join()
        advanceUntilIdle()
        confirmJob.join()

        // Verify chunk 1 was dropped once and then retransmitted
        assertEquals(1, transportAlice.droppedCount, "Exactly 1 chunk should have been dropped")
        assertEquals(msgId, confirmedId, "Delivery confirmation should match sent message")

        alice.stop(); bob.stop()
    }
}
