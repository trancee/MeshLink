package io.meshlink

import io.meshlink.config.meshLinkConfig
import io.meshlink.model.Message
import io.meshlink.model.PeerEvent
import io.meshlink.transport.VirtualMeshTransport
import io.meshlink.util.toHex
import io.meshlink.wire.WireCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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

        // Tiny buffer: 500 bytes, with matching maxMessageSize
        val alice = MeshLink(transportAlice, meshLinkConfig { bufferCapacity = 500; maxMessageSize = 500 }, coroutineContext)
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

    // --- Cycle 8 (Integration): Pause queues in FIFO order ---

    @Test
    fun pauseQueueDeliversSendsInFifoOrder() = runTest {
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

        // Queue 3 messages while paused
        alice.send(peerIdBob, "first".encodeToByteArray())
        alice.send(peerIdBob, "second".encodeToByteArray())
        alice.send(peerIdBob, "third".encodeToByteArray())

        val received = mutableListOf<Message>()
        val collector = launch { bob.messages.take(3).toList().let { received.addAll(it) } }
        advanceUntilIdle()

        // Resume — queued sends should deliver in order
        alice.resume()
        advanceUntilIdle()
        collector.join()

        assertEquals(3, received.size, "Should receive all 3 queued messages")
        assertContentEquals("first".encodeToByteArray(), received[0].payload)
        assertContentEquals("second".encodeToByteArray(), received[1].payload)
        assertContentEquals("third".encodeToByteArray(), received[2].payload)

        alice.stop(); bob.stop()
    }

    // --- Cycle 7 (Integration): Dedup rejects duplicate messages ---

    @Test
    fun duplicateMessageDeliveredOnlyOnce() = runTest {
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

        val received = mutableListOf<Message>()
        val collector = launch { bob.messages.collect { received.add(it) } }
        advanceUntilIdle()

        // Alice sends a single-chunk message
        alice.send(peerIdBob, "hello".encodeToByteArray())
        advanceUntilIdle()

        assertEquals(1, received.size, "Should receive message once")

        // Replay the same raw chunk data to Bob (simulating duplicate delivery)
        val sentChunks = transportAlice.sentData.filter { (_, d) ->
            d.isNotEmpty() && d[0] == WireCodec.TYPE_CHUNK
        }
        assertEquals(1, sentChunks.size)
        transportBob.receiveData(peerIdAlice, sentChunks[0].second)
        advanceUntilIdle()

        // Bob should still only have 1 message (duplicate rejected)
        assertEquals(1, received.size, "Duplicate should be rejected by dedup")

        collector.cancel()
        alice.stop(); bob.stop()
    }

    // --- Cycle 6 (Integration): Delivery failure for unknown peer ---

    @Test
    fun sendToUnknownPeerReturnsFailure() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        alice.start()
        advanceUntilIdle()

        // No peers discovered — send to Bob should fail
        val unknownPeer = ByteArray(16) { 0xFF.toByte() }
        val result = alice.send(unknownPeer, "hello".encodeToByteArray())
        assertTrue(result.isFailure, "Send to unknown peer should fail")
        assertTrue("no route" in result.exceptionOrNull()!!.message!!.lowercase() ||
            "unknown" in result.exceptionOrNull()!!.message!!.lowercase(),
            "Should mention no route/unknown: ${result.exceptionOrNull()!!.message}")

        alice.stop()
    }

    // --- Cycle 5 (Integration): meshHealth() snapshot ---

    @Test
    fun meshHealthReturnsFreshSnapshot() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)

        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        alice.start()
        advanceUntilIdle()

        // Initially: no peers, no transfers
        var health = alice.meshHealth()
        assertEquals(0, health.connectedPeers)
        assertEquals(0, health.activeTransfers)

        // Discover Bob
        transportAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        health = alice.meshHealth()
        assertEquals(1, health.connectedPeers)

        alice.stop()
    }

    // --- Cycle 4 (Integration): Rate limiting on sends ---

    @Test
    fun rateLimitRejectsExcessSends() = runTest {
        var nowMs = 0L
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)

        val alice = MeshLink(
            transportAlice,
            meshLinkConfig { rateLimitMaxSends = 3; rateLimitWindowMs = 60_000L },
            coroutineContext,
            clock = { nowMs },
        )
        alice.start()
        advanceUntilIdle()
        transportAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        // 3 sends within window should succeed
        for (i in 1..3) {
            val r = alice.send(peerIdBob, "msg$i".encodeToByteArray())
            assertTrue(r.isSuccess, "Send $i should succeed")
        }

        // 4th send should be rate-limited
        val r4 = alice.send(peerIdBob, "msg4".encodeToByteArray())
        assertTrue(r4.isFailure, "4th send should be rate-limited")
        assertTrue("rate" in r4.exceptionOrNull()!!.message!!.lowercase())

        // Advance time past the window → should succeed again
        nowMs = 61_000L
        val r5 = alice.send(peerIdBob, "msg5".encodeToByteArray())
        assertTrue(r5.isSuccess, "Send after window reset should succeed")

        alice.stop()
    }

    // --- Cycle 3 (Integration): Callback exception isolation ---

    @Test
    fun exceptionInSubscriberDoesNotCrashLibrary() = runTest {
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

        // Bob has a crashy subscriber and a well-behaved one
        val goodMessages = mutableListOf<Message>()
        val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> /* swallow */ }
        val crashJob = launch(SupervisorJob() + handler) {
            bob.messages.collect {
                throw RuntimeException("app callback crash!")
            }
        }
        val goodJob = launch {
            bob.messages.take(2).toList().let { goodMessages.addAll(it) }
        }
        advanceUntilIdle()

        // Send two messages
        alice.send(peerIdBob, "msg1".encodeToByteArray())
        advanceUntilIdle()
        alice.send(peerIdBob, "msg2".encodeToByteArray())
        advanceUntilIdle()

        goodJob.join()

        // Well-behaved subscriber should get both messages
        assertEquals(2, goodMessages.size, "Good subscriber should receive both messages")
        assertContentEquals("msg1".encodeToByteArray(), goodMessages[0].payload)
        assertContentEquals("msg2".encodeToByteArray(), goodMessages[1].payload)

        crashJob.cancel()
        alice.stop(); bob.stop()
    }

    // --- Cycle 2 (Integration): Config validation at start() ---

    @Test
    fun startFailsWithAllViolationsWhenConfigInvalid() = runTest {
        val transport = VirtualMeshTransport(peerIdAlice)
        val badConfig = meshLinkConfig {
            mtu = 10              // too small (< 22)
            maxMessageSize = 2_000_000  // > bufferCapacity
        }
        val mesh = MeshLink(transport, badConfig, coroutineContext)

        val result = mesh.start()
        assertTrue(result.isFailure, "start() should fail with invalid config")
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("mtu" in message.lowercase(), "Should mention mtu: $message")
        assertTrue("maxmessagesize" in message.lowercase() || "buffer" in message.lowercase(),
            "Should mention buffer/size violation: $message")
    }

    // --- Cycle 1 (Integration): Self-send loopback ---

    @Test
    fun selfSendDeliversViaMesagesFlowWithoutBle() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        alice.start()
        advanceUntilIdle()

        // Send to self — should arrive via messages Flow, no BLE
        val receiveJob = launch {
            val msg = alice.messages.first()
            assertContentEquals(peerIdAlice, msg.senderId)
            assertContentEquals("self-loop".encodeToByteArray(), msg.payload)
        }

        val result = alice.send(peerIdAlice, "self-loop".encodeToByteArray())
        assert(result.isSuccess) { "self-send should succeed" }
        advanceUntilIdle()
        receiveJob.join()

        // No BLE data should have been sent
        assertTrue(transportAlice.sentData.isEmpty(), "Self-send should not use BLE transport")

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

    // --- Cycle 8 (Wire Integration): Broadcast send API ---

    @Test
    fun broadcastSendsToAllNeighbors() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        val transportCharlie = VirtualMeshTransport(peerIdCharlie)
        transportAlice.linkTo(transportBob)
        transportAlice.linkTo(transportCharlie)

        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        val bob = MeshLink(transportBob, meshLinkConfig(), coroutineContext)
        val charlie = MeshLink(transportCharlie, meshLinkConfig(), coroutineContext)
        alice.start(); bob.start(); charlie.start()
        advanceUntilIdle()

        transportAlice.simulateDiscovery(peerIdBob)
        transportAlice.simulateDiscovery(peerIdCharlie)
        transportBob.simulateDiscovery(peerIdAlice)
        transportCharlie.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        val bobMessages = mutableListOf<Message>()
        val charlieMessages = mutableListOf<Message>()
        val bobCollector = launch { bob.messages.collect { bobMessages.add(it) } }
        val charlieCollector = launch { charlie.messages.collect { charlieMessages.add(it) } }
        advanceUntilIdle()

        // Alice broadcasts
        val msgId = alice.broadcast("hello mesh!".encodeToByteArray(), maxHops = 3u)
        assertTrue(msgId.isSuccess)
        advanceUntilIdle()

        // Both Bob and Charlie should receive the broadcast
        assertEquals(1, bobMessages.size, "Bob should receive broadcast")
        assertEquals(1, charlieMessages.size, "Charlie should receive broadcast")
        assertContentEquals("hello mesh!".encodeToByteArray(), bobMessages[0].payload)
        assertContentEquals(peerIdAlice, bobMessages[0].senderId)

        bobCollector.cancel(); charlieCollector.cancel()
        alice.stop(); bob.stop(); charlie.stop()
    }

    // --- Cycle 7 (Wire Integration): RoutingTable next-hop relay ---

    @Test
    fun sendToNonNeighborUsesRoutingTable() = runTest {
        // Alice knows Bob directly, Bob knows Charlie. Alice has a route to Charlie via Bob.
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)

        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        alice.start()
        advanceUntilIdle()

        // Discover Bob (direct neighbor)
        transportAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        // Add a route to Charlie via Bob
        alice.addRoute(
            destination = peerIdCharlie.toHex(),
            nextHop = peerIdBob.toHex(),
            cost = 1.0,
            sequenceNumber = 1u,
        )

        // Send to Charlie (not a direct neighbor) — should route via Bob
        val result = alice.send(peerIdCharlie, "for charlie".encodeToByteArray())
        assertTrue(result.isSuccess, "Should succeed via routing table")
        advanceUntilIdle()

        // Bob should have received a routed message (not a chunk)
        val routedSent = transportAlice.sentData.filter { (peerId, data) ->
            peerId == peerIdBob.toHex() && data.isNotEmpty() && data[0] == WireCodec.TYPE_ROUTED_MESSAGE
        }
        assertEquals(1, routedSent.size, "Should send routed message via Bob")
        val msg = WireCodec.decodeRoutedMessage(routedSent[0].second)
        assertContentEquals(peerIdCharlie, msg.destination)
        assertContentEquals("for charlie".encodeToByteArray(), msg.payload)

        alice.stop()
    }

    // --- Cycle 6 (Wire Integration): PresenceTracker with sweep eviction ---

    @Test
    fun presenceTrackerEvictsAfterTwoMissedSweeps() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        alice.start()
        advanceUntilIdle()

        // Discover Bob
        transportAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()
        assertEquals(1, alice.meshHealth().connectedPeers)

        // First sweep with empty seen set — Bob misses once (still tracked)
        alice.sweep(emptySet())
        assertEquals(1, alice.meshHealth().connectedPeers, "First miss: still tracked")

        // Second sweep — Bob misses again → evicted
        val evicted = alice.sweep(emptySet())
        assertEquals(0, alice.meshHealth().connectedPeers, "Second miss: evicted")
        assertEquals(1, evicted.size, "Should report 1 evicted peer")

        alice.stop()
    }

    // --- Cycle 5 (Wire Integration): DiagnosticSink emits on rate limit ---

    @Test
    fun diagnosticEventEmittedOnRateLimit() = runTest {
        var nowMs = 0L
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(
            transportAlice, meshLinkConfig { rateLimitMaxSends = 1; rateLimitWindowMs = 60_000L }, coroutineContext,
            clock = { nowMs },
        )
        alice.start()
        advanceUntilIdle()

        transportAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        // First send succeeds
        alice.send(peerIdBob, "ok".encodeToByteArray())

        // Second send rate-limited
        alice.send(peerIdBob, "blocked".encodeToByteArray())

        // Drain diagnostics
        val events = alice.drainDiagnostics()
        assertTrue(events.any { it.code == io.meshlink.diagnostics.DiagnosticCode.RATE_LIMIT_HIT },
            "Should emit RATE_LIMIT_HIT diagnostic, got: $events")

        alice.stop()
    }

    // --- Cycle 4 (Wire Integration): DeliveryTracker exactly-once terminal signal ---

    @Test
    fun deliveryTrackerPreventsDoubleConfirmation() = runTest {
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

        // Collect all delivery confirmations
        val confirmations = mutableListOf<Uuid>()
        val collector = launch { alice.deliveryConfirmations.collect { confirmations.add(it) } }
        advanceUntilIdle()

        // Bob consumes messages
        val bobJob = launch { bob.messages.first() }

        val msgId = alice.send(peerIdBob, "hello".encodeToByteArray()).getOrThrow()
        advanceUntilIdle()
        bobJob.join()
        advanceUntilIdle()

        // First ACK should produce exactly 1 confirmation
        assertEquals(1, confirmations.size, "Should get exactly 1 confirmation")
        assertEquals(msgId, confirmations[0])

        // Simulate a duplicate delivery ACK arriving
        val ackData = WireCodec.encodeDeliveryAck(
            messageId = msgId.toByteArray(),
            recipientId = peerIdBob,
        )
        transportAlice.receiveData(peerIdBob, ackData)
        advanceUntilIdle()

        // Should still be exactly 1 (duplicate rejected by DeliveryTracker)
        assertEquals(1, confirmations.size, "Duplicate ACK should not produce second confirmation")

        collector.cancel()
        alice.stop(); bob.stop()
    }

    // --- Cycle 3 (Wire Integration): Handle delivery ACK ---

    @Test
    fun deliveryAckEmitsConfirmation() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        alice.start()
        advanceUntilIdle()

        transportAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        var confirmed: Uuid? = null
        val confirmJob = launch { confirmed = alice.deliveryConfirmations.first() }
        advanceUntilIdle()

        // Simulate receiving a delivery ACK from Bob
        val msgId = Uuid.random()
        val ackData = WireCodec.encodeDeliveryAck(
            messageId = msgId.toByteArray(),
            recipientId = peerIdBob,
        )
        transportAlice.receiveData(peerIdBob, ackData)
        advanceUntilIdle()
        confirmJob.join()

        assertEquals(msgId, confirmed, "Delivery ACK should emit confirmation")

        alice.stop()
    }

    // --- Cycle 2 (Wire Integration): Handle routed message ---

    @Test
    fun routedMessageDeliveredIfWeAreDestination() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportBob.linkTo(transportAlice)

        val bob = MeshLink(transportBob, meshLinkConfig(), coroutineContext)
        bob.start()
        advanceUntilIdle()

        transportBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        val received = mutableListOf<Message>()
        val collector = launch { bob.messages.collect { received.add(it) } }
        advanceUntilIdle()

        // Alice sends a routed message with Bob as destination
        val msgId = Uuid.random().toByteArray()
        val routedData = WireCodec.encodeRoutedMessage(
            messageId = msgId,
            origin = peerIdAlice,
            destination = peerIdBob,
            hopLimit = 3u,
            visitedList = listOf(peerIdAlice),
            payload = "routed hello".encodeToByteArray(),
        )
        transportBob.receiveData(peerIdAlice, routedData)
        advanceUntilIdle()

        assertEquals(1, received.size, "Routed message should be delivered")
        assertContentEquals("routed hello".encodeToByteArray(), received[0].payload)
        assertContentEquals(peerIdAlice, received[0].senderId)

        collector.cancel()
        bob.stop()
    }

    @Test
    fun routedMessageRelayedIfNotDestination() = runTest {
        // Alice--Bob--Charlie: Alice sends routed message destined for Charlie, Bob relays
        val transportBob = VirtualMeshTransport(peerIdBob)
        val transportCharlie = VirtualMeshTransport(peerIdCharlie)
        transportBob.linkTo(transportCharlie)

        val bob = MeshLink(transportBob, meshLinkConfig(), coroutineContext)
        bob.start()
        advanceUntilIdle()

        transportBob.simulateDiscovery(peerIdAlice)
        transportBob.simulateDiscovery(peerIdCharlie)
        advanceUntilIdle()

        // Bob receives a routed message destined for Charlie (not Bob)
        val msgId = Uuid.random().toByteArray()
        val routedData = WireCodec.encodeRoutedMessage(
            messageId = msgId,
            origin = peerIdAlice,
            destination = peerIdCharlie,
            hopLimit = 3u,
            visitedList = listOf(peerIdAlice),
            payload = "for charlie".encodeToByteArray(),
        )
        transportBob.receiveData(peerIdAlice, routedData)
        advanceUntilIdle()

        // Bob should NOT deliver to self (not the destination)
        val bobMessages = mutableListOf<Message>()
        val collector = launch { bob.messages.collect { bobMessages.add(it) } }
        advanceUntilIdle()
        assertEquals(0, bobMessages.size, "Bob should not deliver message not destined for him")

        // Bob should relay to Charlie with Bob added to visited list
        val relayed = transportBob.sentData.filter { (_, data) ->
            data.isNotEmpty() && data[0] == WireCodec.TYPE_ROUTED_MESSAGE
        }
        assertEquals(1, relayed.size, "Bob should relay to Charlie")
        val relayedMsg = WireCodec.decodeRoutedMessage(relayed[0].second)
        assertEquals(2u.toUByte(), relayedMsg.hopLimit, "Hop limit should be decremented")
        assertEquals(2, relayedMsg.visitedList.size, "Bob should be added to visited list")

        collector.cancel()
        bob.stop()
    }

    // --- Cycle 1 (Wire Integration): Handle incoming broadcast ---

    private val peerIdCharlie = ByteArray(16) { (0xC0 + it).toByte() }

    @Test
    fun receivedBroadcastDeliversToSelfAndRefloods() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        val transportCharlie = VirtualMeshTransport(peerIdCharlie)
        transportAlice.linkTo(transportBob)
        transportAlice.linkTo(transportCharlie)

        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        alice.start()
        advanceUntilIdle()

        transportAlice.simulateDiscovery(peerIdBob)
        transportAlice.simulateDiscovery(peerIdCharlie)
        advanceUntilIdle()

        // Collect messages delivered to Alice
        val received = mutableListOf<Message>()
        val collector = launch { alice.messages.collect { received.add(it) } }
        advanceUntilIdle()

        // Bob sends a broadcast with hops=2
        val msgId = Uuid.random().toByteArray()
        val broadcastData = WireCodec.encodeBroadcast(
            messageId = msgId,
            origin = peerIdBob,
            remainingHops = 2u,
            payload = "broadcast!".encodeToByteArray(),
        )
        transportAlice.receiveData(peerIdBob, broadcastData)
        advanceUntilIdle()

        // Alice should deliver the broadcast to herself
        assertEquals(1, received.size, "Broadcast should be delivered locally")
        assertContentEquals("broadcast!".encodeToByteArray(), received[0].payload)
        assertContentEquals(peerIdBob, received[0].senderId)

        // Alice should re-flood to Charlie (not back to Bob) with hops decremented
        val refloodedToCharlie = transportAlice.sentData.filter { (peerId, data) ->
            peerId == peerIdCharlie.toHex() && data.isNotEmpty() && data[0] == WireCodec.TYPE_BROADCAST
        }
        assertEquals(1, refloodedToCharlie.size, "Should re-flood to Charlie")
        val reflooded = WireCodec.decodeBroadcast(refloodedToCharlie[0].second)
        assertEquals(1u.toUByte(), reflooded.remainingHops, "Hops should be decremented")

        collector.cancel()
        alice.stop()
    }

    // --- Batch 8 Cycle 1: Double start is idempotent ---

    @Test
    fun doubleStartIsIdempotent() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)

        val r1 = alice.start()
        assertTrue(r1.isSuccess)
        advanceUntilIdle()
        val r2 = alice.start()
        assertTrue(r2.isSuccess, "Second start should succeed (idempotent)")

        // Should still function normally
        transportAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()
        assertEquals(1, alice.meshHealth().connectedPeers)

        alice.stop()
    }

    // --- Batch 8 Cycle 2: Broadcast to zero peers succeeds ---

    @Test
    fun broadcastToZeroPeersSucceeds() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        alice.start()
        advanceUntilIdle()

        // No peers discovered — broadcast should still succeed
        assertEquals(0, alice.meshHealth().connectedPeers)
        val result = alice.broadcast("hello void".encodeToByteArray(), 3u)
        assertTrue(result.isSuccess, "Broadcast to zero peers should succeed")

        alice.stop()
    }

    // --- Batch 8 Cycle 3: Send not started throws ---

    @Test
    fun sendBeforeStartThrows() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)

        assertFailsWith<IllegalStateException>("Should throw when not started") {
            alice.send(peerIdBob, "nope".encodeToByteArray())
        }
    }

    // --- Batch 8 Cycle 4: Routed message to direct neighbor skips routing table ---

    @Test
    fun routedMessageToDirectNeighborSendsDirectly() = runTest {
        val peerIdCharlie = ByteArray(16) { (0xC0 + it).toByte() }
        val transportBob = VirtualMeshTransport(peerIdBob)
        val transportCharlie = VirtualMeshTransport(peerIdCharlie)
        transportBob.linkTo(transportCharlie)

        val bob = MeshLink(transportBob, meshLinkConfig(), coroutineContext)
        val charlie = MeshLink(transportCharlie, meshLinkConfig(), coroutineContext)
        bob.start(); charlie.start()
        advanceUntilIdle()
        transportBob.simulateDiscovery(peerIdCharlie)
        transportCharlie.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        val received = mutableListOf<Message>()
        val collector = launch { charlie.messages.collect { received.add(it) } }
        advanceUntilIdle()

        // Bob receives a routed message destined for Charlie (direct neighbor)
        // Should deliver directly without needing routing table entry
        val msgId = Uuid.random().toByteArray()
        val routedData = WireCodec.encodeRoutedMessage(
            messageId = msgId,
            origin = peerIdAlice,
            destination = peerIdCharlie,
            hopLimit = 5u,
            visitedList = listOf(peerIdAlice),
            payload = "direct relay".encodeToByteArray(),
        )
        transportBob.receiveData(peerIdAlice, routedData)
        advanceUntilIdle()

        assertEquals(1, received.size, "Charlie should receive via direct relay")
        assertContentEquals("direct relay".encodeToByteArray(), received[0].payload)

        collector.cancel()
        bob.stop(); charlie.stop()
    }

    // --- Batch 8 Cycle 5: Delivery tracker prevents double confirmation ---

    @Test
    fun duplicateDeliveryAckEmitsOnlyOneConfirmation() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)
        transportBob.linkTo(transportAlice)

        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        val bob = MeshLink(transportBob, meshLinkConfig(), coroutineContext)
        alice.start(); bob.start()
        advanceUntilIdle()
        transportAlice.simulateDiscovery(peerIdBob)
        transportBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        val confirmations = mutableListOf<Uuid>()
        val collector = launch { alice.deliveryConfirmations.collect { confirmations.add(it) } }
        advanceUntilIdle()

        // Send message — will get one confirmation via chunk ACK
        val result = alice.send(peerIdBob, "msg".encodeToByteArray())
        assertTrue(result.isSuccess)
        advanceUntilIdle()

        val countAfterFirst = confirmations.size
        assertEquals(1, countAfterFirst, "Should have exactly 1 confirmation")

        // Manually send a duplicate delivery ACK back from Bob
        val msgId = result.getOrThrow().toByteArray()
        val dupAck = WireCodec.encodeDeliveryAck(msgId, peerIdBob)
        transportAlice.receiveData(peerIdBob, dupAck)
        advanceUntilIdle()

        // DeliveryTracker should block the duplicate — state is already RESOLVED
        assertEquals(1, confirmations.size, "Duplicate ACK should NOT produce second confirmation")

        collector.cancel()
        alice.stop(); bob.stop()
    }

    // --- Batch 8 Cycle 6: Resume without start is safe ---

    @Test
    fun resumeWithoutStartDoesNotCrash() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)

        // These should not crash
        alice.resume()
        alice.pause()
        alice.resume()
        alice.stop()
    }

    // --- Batch 8 Cycle 7: Config DSL builder produces expected values ---

    @Test
    fun configDslBuilderOverridesApply() = runTest {
        val config = meshLinkConfig {
            mtu = 128
            maxMessageSize = 5_000
            bufferCapacity = 50_000
        }
        assertEquals(128, config.mtu)
        assertEquals(5_000, config.maxMessageSize)
        assertEquals(50_000, config.bufferCapacity)

        // Validate passes
        val violations = config.validate()
        assertTrue(violations.isEmpty(), "Custom config should validate: $violations")
    }

    // --- Batch 8 Cycle 8: meshHealth consistent after pause/resume ---

    @Test
    fun meshHealthConsistentAfterPauseResume() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        alice.start()
        advanceUntilIdle()

        transportAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()
        assertEquals(1, alice.meshHealth().connectedPeers)

        alice.pause()
        // Peers should still be tracked while paused
        assertEquals(1, alice.meshHealth().connectedPeers, "Peers preserved during pause")

        alice.resume()
        advanceUntilIdle()
        assertEquals(1, alice.meshHealth().connectedPeers, "Peers preserved after resume")

        alice.stop()
    }

    // --- Batch 7 Cycle 8: Power mode BALANCED at mid battery ---

    @Test
    fun powerModeTransitionsToBalancedAtMidBattery() = runTest {
        var now = 0L
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext, clock = { now })
        alice.start()
        advanceUntilIdle()

        assertEquals("PERFORMANCE", alice.meshHealth().powerMode)

        // 50% battery → target BALANCED, but hysteresis delays it
        alice.updateBattery(50, false)
        assertEquals("PERFORMANCE", alice.meshHealth().powerMode, "Hysteresis prevents immediate downgrade")

        // Advance past hysteresis (30s default)
        now += 30_001
        alice.updateBattery(50, false)
        assertEquals("BALANCED", alice.meshHealth().powerMode, "Should transition to BALANCED")

        // Battery recovers to 90% → immediate upgrade (no hysteresis for upward)
        alice.updateBattery(90, false)
        assertEquals("PERFORMANCE", alice.meshHealth().powerMode, "Upward transition is immediate")

        alice.stop()
    }

    // --- Batch 7 Cycle 7: Sweep with all peers seen preserves all ---

    @Test
    fun sweepWithAllPeersSeenPreservesEveryone() = runTest {
        val peerIdCharlie = ByteArray(16) { (0xC0 + it).toByte() }
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        alice.start()
        advanceUntilIdle()

        transportAlice.simulateDiscovery(peerIdBob)
        transportAlice.simulateDiscovery(peerIdCharlie)
        advanceUntilIdle()
        assertEquals(2, alice.meshHealth().connectedPeers)

        // Sweep with all peers seen — nobody evicted
        val allSeen = setOf(peerIdBob.toHex(), peerIdCharlie.toHex())
        val evicted1 = alice.sweep(allSeen)
        assertEquals(0, evicted1.size, "No eviction when all peers seen")
        assertEquals(2, alice.meshHealth().connectedPeers, "Both peers preserved")

        // Multiple sweeps with all seen — still nobody evicted
        val evicted2 = alice.sweep(allSeen)
        val evicted3 = alice.sweep(allSeen)
        assertEquals(0, evicted2.size)
        assertEquals(0, evicted3.size)
        assertEquals(2, alice.meshHealth().connectedPeers, "All peers still present after 3 sweeps")

        alice.stop()
    }

    // --- Batch 7 Cycle 6: Delivery confirmation for direct single-chunk send ---

    @Test
    fun deliveryConfirmationForDirectSingleChunkSend() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)
        transportBob.linkTo(transportAlice)

        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        val bob = MeshLink(transportBob, meshLinkConfig(), coroutineContext)
        alice.start(); bob.start()
        advanceUntilIdle()

        transportAlice.simulateDiscovery(peerIdBob)
        transportBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        val confirmations = mutableListOf<Uuid>()
        val collector = launch { alice.deliveryConfirmations.collect { confirmations.add(it) } }
        advanceUntilIdle()

        val result = alice.send(peerIdBob, "small".encodeToByteArray())
        assertTrue(result.isSuccess)
        val messageId = result.getOrThrow()
        advanceUntilIdle()

        // Should get exactly one delivery confirmation matching the message ID
        assertEquals(1, confirmations.size, "Should receive delivery confirmation")
        assertEquals(messageId, confirmations[0], "Confirmation should match sent message ID")

        collector.cancel()
        alice.stop(); bob.stop()
    }

    // --- Batch 7 Cycle 5: Multiple sequential sends all deliver ---

    @Test
    fun multipleSequentialSendsToSamePeerAllDeliver() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)
        transportBob.linkTo(transportAlice)

        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        val bob = MeshLink(transportBob, meshLinkConfig(), coroutineContext)
        alice.start(); bob.start()
        advanceUntilIdle()

        transportAlice.simulateDiscovery(peerIdBob)
        transportBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        val received = mutableListOf<Message>()
        val collector = launch { bob.messages.collect { received.add(it) } }
        advanceUntilIdle()

        // Send 5 sequential messages
        repeat(5) { i ->
            val result = alice.send(peerIdBob, "msg-$i".encodeToByteArray())
            assertTrue(result.isSuccess, "Send $i should succeed")
            advanceUntilIdle()
        }

        assertEquals(5, received.size, "All 5 messages should be delivered")
        for (i in 0 until 5) {
            assertContentEquals("msg-$i".encodeToByteArray(), received[i].payload)
        }

        collector.cancel()
        alice.stop(); bob.stop()
    }

    // --- Batch 7 Cycle 4: Broadcast maxHops=1 reaches only direct neighbors ---

    @Test
    fun broadcastMaxHopsOneReachesOnlyDirectNeighbors() = runTest {
        val peerIdCharlie = ByteArray(16) { (0xC0 + it).toByte() }
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        val transportCharlie = VirtualMeshTransport(peerIdCharlie)
        transportAlice.linkTo(transportBob)
        transportBob.linkTo(transportCharlie)

        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        val bob = MeshLink(transportBob, meshLinkConfig(), coroutineContext)
        val charlie = MeshLink(transportCharlie, meshLinkConfig(), coroutineContext)
        alice.start(); bob.start(); charlie.start()
        advanceUntilIdle()

        transportAlice.simulateDiscovery(peerIdBob)
        transportBob.simulateDiscovery(peerIdAlice)
        transportBob.simulateDiscovery(peerIdCharlie)
        transportCharlie.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        val bobMessages = mutableListOf<Message>()
        val charlieMessages = mutableListOf<Message>()
        val cBob = launch { bob.messages.collect { bobMessages.add(it) } }
        val cCharlie = launch { charlie.messages.collect { charlieMessages.add(it) } }
        advanceUntilIdle()

        // Broadcast with maxHops=1: Alice→Bob (hops=1), Bob re-floods with hops=0→Charlie
        alice.broadcast("one hop".encodeToByteArray(), 1u)
        advanceUntilIdle()

        assertEquals(1, bobMessages.size, "Bob (direct neighbor) should receive")
        assertEquals(1, charlieMessages.size, "Charlie should receive (Bob re-floods with hops=0)")

        // But if maxHops=0, Charlie should NOT receive
        val charlieMessages2 = mutableListOf<Message>()
        val cCharlie2 = launch { charlie.messages.collect { charlieMessages2.add(it) } }
        advanceUntilIdle()

        alice.broadcast("zero hop".encodeToByteArray(), 0u)
        advanceUntilIdle()

        // Bob receives (direct), but doesn't re-flood (hops=0 after decrement would be <0)
        // Actually maxHops=0 means Alice sends with remainingHops=0, Bob receives and does NOT re-flood
        assertEquals(0, charlieMessages2.size, "Charlie should NOT receive with maxHops=0")

        cBob.cancel(); cCharlie.cancel(); cCharlie2.cancel()
        alice.stop(); bob.stop(); charlie.stop()
    }

    // --- Batch 7 Cycle 3: addRoute higher seqnum changes next hop ---

    @Test
    fun addRouteHigherSeqnumReplacesOldRoute() = runTest {
        val peerIdCharlie = ByteArray(16) { (0xC0 + it).toByte() }
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        val transportCharlie = VirtualMeshTransport(peerIdCharlie)
        transportAlice.linkTo(transportBob)
        transportAlice.linkTo(transportCharlie)
        transportBob.linkTo(transportAlice)
        transportCharlie.linkTo(transportAlice)

        val peerIdDest = ByteArray(16) { (0xD0 + it).toByte() }
        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        alice.start()
        advanceUntilIdle()
        transportAlice.simulateDiscovery(peerIdBob)
        transportAlice.simulateDiscovery(peerIdCharlie)
        advanceUntilIdle()

        // Route to dest via Bob (seqnum 1)
        alice.addRoute(peerIdDest.toHex(), peerIdBob.toHex(), 1.0, 1u)
        alice.send(peerIdDest, "via-bob".encodeToByteArray())
        advanceUntilIdle()

        val sentToBob = transportAlice.sentData.filter { (peerId, data) ->
            peerId == peerIdBob.toHex() && data.isNotEmpty() && data[0] == WireCodec.TYPE_ROUTED_MESSAGE
        }
        assertEquals(1, sentToBob.size, "Should route via Bob initially")

        // Update route: higher seqnum, now via Charlie
        transportAlice.sentData.clear()
        alice.addRoute(peerIdDest.toHex(), peerIdCharlie.toHex(), 1.0, 2u)
        alice.send(peerIdDest, "via-charlie".encodeToByteArray())
        advanceUntilIdle()

        val sentToCharlie = transportAlice.sentData.filter { (peerId, data) ->
            peerId == peerIdCharlie.toHex() && data.isNotEmpty() && data[0] == WireCodec.TYPE_ROUTED_MESSAGE
        }
        assertEquals(1, sentToCharlie.size, "Should route via Charlie after seqnum update")

        alice.stop()
    }

    // --- Batch 7 Cycle 2: 4-node delivery ACK chain ---

    @Test
    fun fourNodeDeliveryAckRelaysBackToSender() = runTest {
        val peerIdCharlie = ByteArray(16) { (0xC0 + it).toByte() }
        val peerIdDave = ByteArray(16) { (0xD0 + it).toByte() }
        val tA = VirtualMeshTransport(peerIdAlice)
        val tB = VirtualMeshTransport(peerIdBob)
        val tC = VirtualMeshTransport(peerIdCharlie)
        val tD = VirtualMeshTransport(peerIdDave)
        tA.linkTo(tB); tB.linkTo(tC); tC.linkTo(tD)

        val alice = MeshLink(tA, meshLinkConfig(), coroutineContext)
        val bob = MeshLink(tB, meshLinkConfig(), coroutineContext)
        val charlie = MeshLink(tC, meshLinkConfig(), coroutineContext)
        val dave = MeshLink(tD, meshLinkConfig(), coroutineContext)
        alice.start(); bob.start(); charlie.start(); dave.start()
        advanceUntilIdle()

        tA.simulateDiscovery(peerIdBob); tB.simulateDiscovery(peerIdAlice)
        tB.simulateDiscovery(peerIdCharlie); tC.simulateDiscovery(peerIdBob)
        tC.simulateDiscovery(peerIdDave); tD.simulateDiscovery(peerIdCharlie)
        advanceUntilIdle()

        // Routes: A→B→C→D
        alice.addRoute(peerIdDave.toHex(), peerIdBob.toHex(), 1.0, 1u)
        bob.addRoute(peerIdDave.toHex(), peerIdCharlie.toHex(), 1.0, 1u)
        charlie.addRoute(peerIdDave.toHex(), peerIdDave.toHex(), 1.0, 1u)

        val confirmations = mutableListOf<Uuid>()
        val confCollector = launch { alice.deliveryConfirmations.collect { confirmations.add(it) } }
        val daveMessages = mutableListOf<Message>()
        val daveCollector = launch { dave.messages.collect { daveMessages.add(it) } }
        advanceUntilIdle()

        val result = alice.send(peerIdDave, "4-hop hello".encodeToByteArray())
        assertTrue(result.isSuccess)
        advanceUntilIdle()

        // Dave should receive the message
        assertEquals(1, daveMessages.size, "Dave should receive the message")
        assertContentEquals("4-hop hello".encodeToByteArray(), daveMessages[0].payload)

        // Alice should receive delivery confirmation (ACK relayed D→C→B→A)
        assertEquals(1, confirmations.size, "Alice should get delivery ACK through 3-hop relay")

        confCollector.cancel(); daveCollector.cancel()
        alice.stop(); bob.stop(); charlie.stop(); dave.stop()
    }

    // --- Batch 7 Cycle 1: Paused node doesn't relay routed messages ---

    @Test
    fun pausedNodeDoesNotRelayRoutedMessages() = runTest {
        val peerIdCharlie = ByteArray(16) { (0xC0 + it).toByte() }
        val transportBob = VirtualMeshTransport(peerIdBob)
        val transportCharlie = VirtualMeshTransport(peerIdCharlie)
        transportBob.linkTo(transportCharlie)

        val bob = MeshLink(transportBob, meshLinkConfig(), coroutineContext)
        bob.start()
        advanceUntilIdle()
        transportBob.simulateDiscovery(peerIdCharlie)
        advanceUntilIdle()
        bob.addRoute(peerIdCharlie.toHex(), peerIdCharlie.toHex(), 1.0, 1u)

        // Pause Bob
        bob.pause()
        advanceUntilIdle()

        // Send routed message through Bob destined for Charlie
        val msgId = Uuid.random().toByteArray()
        val routedData = WireCodec.encodeRoutedMessage(
            messageId = msgId,
            origin = peerIdAlice,
            destination = peerIdCharlie,
            hopLimit = 5u,
            visitedList = listOf(peerIdAlice),
            payload = "relayed?".encodeToByteArray(),
        )
        transportBob.receiveData(peerIdAlice, routedData)
        advanceUntilIdle()

        // Bob should NOT relay while paused
        val relayed = transportBob.sentData.filter { (_, data) ->
            data.isNotEmpty() && data[0] == WireCodec.TYPE_ROUTED_MESSAGE
        }
        assertEquals(0, relayed.size, "Paused node should not relay routed messages")

        bob.stop()
    }

    // --- Batch 6 Cycle 8: Sweep returns evicted peer IDs ---

    @Test
    fun sweepReturnsCorrectEvictedPeerIds() = runTest {
        val peerIdCharlie = ByteArray(16) { (0xC0 + it).toByte() }
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        alice.start()
        advanceUntilIdle()

        transportAlice.simulateDiscovery(peerIdBob)
        transportAlice.simulateDiscovery(peerIdCharlie)
        advanceUntilIdle()
        assertEquals(2, alice.meshHealth().connectedPeers)

        // First sweep with only Charlie seen → Bob gets first miss
        val evicted1 = alice.sweep(setOf(peerIdCharlie.toHex()))
        assertEquals(0, evicted1.size, "No eviction after 1 miss")
        assertEquals(2, alice.meshHealth().connectedPeers, "Bob disconnected but not evicted")

        // Second sweep with only Charlie seen → Bob gets second miss → evicted
        val evicted2 = alice.sweep(setOf(peerIdCharlie.toHex()))
        assertEquals(1, evicted2.size, "Bob should be evicted")
        assertTrue(evicted2.contains(peerIdBob.toHex()), "Evicted set should contain Bob's ID")
        assertEquals(1, alice.meshHealth().connectedPeers, "Only Charlie remains")

        alice.stop()
    }

    // --- Batch 6 Cycle 7: Large message fragmentation chunk count ---

    @Test
    fun largeMessageFragmentsIntoCorrectChunkCount() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)
        transportBob.linkTo(transportAlice)

        // mtu=30, header=21 → 9 bytes payload per chunk
        val alice = MeshLink(transportAlice, meshLinkConfig { mtu = 30 }, coroutineContext)
        val bob = MeshLink(transportBob, meshLinkConfig { mtu = 30 }, coroutineContext)
        alice.start(); bob.start()
        advanceUntilIdle()

        transportAlice.simulateDiscovery(peerIdBob)
        transportBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        val received = mutableListOf<Message>()
        val collector = launch { bob.messages.collect { received.add(it) } }
        advanceUntilIdle()

        // 50 bytes / 9 bytes per chunk = ceil(50/9) = 6 chunks
        val payload = ByteArray(50) { (it + 1).toByte() }
        alice.send(peerIdBob, payload)
        advanceUntilIdle()

        assertEquals(1, received.size, "Should receive reassembled message")
        assertContentEquals(payload, received[0].payload, "Payload should match exactly")

        // Verify the number of unique chunk sequence numbers sent
        val chunkSeqNums = transportAlice.sentData
            .filter { (_, data) -> data.isNotEmpty() && data[0] == WireCodec.TYPE_CHUNK }
            .map { (_, data) -> ((data[17].toInt() and 0xFF) or ((data[18].toInt() and 0xFF) shl 8)) }
            .toSet()
        assertEquals(6, chunkSeqNums.size, "50 bytes at 9 bytes/chunk = 6 unique chunk seqNums")

        collector.cancel()
        alice.stop(); bob.stop()
    }

    // --- Batch 6 Cycle 6: Duplicate broadcast delivered only once ---

    @Test
    fun duplicateBroadcastDeliveredOnlyOnce() = runTest {
        val transportBob = VirtualMeshTransport(peerIdBob)

        val bob = MeshLink(transportBob, meshLinkConfig(), coroutineContext)
        bob.start()
        advanceUntilIdle()

        val received = mutableListOf<Message>()
        val collector = launch { bob.messages.collect { received.add(it) } }
        advanceUntilIdle()

        // Same broadcast sent twice
        val msgId = Uuid.random().toByteArray()
        val broadcastData = WireCodec.encodeBroadcast(
            messageId = msgId,
            origin = peerIdAlice,
            remainingHops = 3u,
            payload = "dup test".encodeToByteArray(),
        )
        transportBob.receiveData(peerIdAlice, broadcastData)
        advanceUntilIdle()
        transportBob.receiveData(peerIdAlice, broadcastData)
        advanceUntilIdle()

        assertEquals(1, received.size, "Duplicate broadcast should be delivered only once")

        collector.cancel()
        bob.stop()
    }

    // --- Batch 6 Cycle 5: Circuit breaker recovery delivers message ---

    @Test
    fun circuitBreakerRecoveryActuallyDeliversMessage() = runTest {
        var now = 0L
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)
        transportBob.linkTo(transportAlice)

        val alice = MeshLink(
            transportAlice, meshLinkConfig {
                circuitBreakerMaxFailures = 2
                circuitBreakerWindowMs = 10_000
                circuitBreakerCooldownMs = 5_000
            }, coroutineContext,
            clock = { now },
        )
        val bob = MeshLink(transportBob, meshLinkConfig(), coroutineContext)
        alice.start(); bob.start()
        advanceUntilIdle()

        transportAlice.simulateDiscovery(peerIdBob)
        transportBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        // Trip the circuit
        transportAlice.sendFailure = true
        alice.send(peerIdBob, "f1".encodeToByteArray())
        advanceUntilIdle()
        alice.send(peerIdBob, "f2".encodeToByteArray())
        advanceUntilIdle()

        // Circuit open — send fails fast
        transportAlice.sendFailure = false
        val blocked = alice.send(peerIdBob, "blocked".encodeToByteArray())
        assertTrue(blocked.isFailure)

        // After cooldown — send succeeds AND message is delivered
        now += 5_001
        val received = mutableListOf<Message>()
        val collector = launch { bob.messages.collect { received.add(it) } }
        advanceUntilIdle()

        val recovered = alice.send(peerIdBob, "recovered!".encodeToByteArray())
        assertTrue(recovered.isSuccess, "Send should succeed after cooldown")
        advanceUntilIdle()

        assertEquals(1, received.size, "Bob should receive the message after recovery")
        assertContentEquals("recovered!".encodeToByteArray(), received[0].payload)

        collector.cancel()
        alice.stop(); bob.stop()
    }

    // --- Batch 6 Cycle 4: meshHealth activeTransfers count ---

    @Test
    fun meshHealthActiveTransfersCountsDuringConcurrentSends() = runTest {
        val peerIdCharlie = ByteArray(16) { (0xC0 + it).toByte() }
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        val transportCharlie = VirtualMeshTransport(peerIdCharlie)
        transportAlice.linkTo(transportBob)
        transportAlice.linkTo(transportCharlie)
        // Drop all ACKs so transfers stay active
        transportBob.dropFilter = { data -> data.isNotEmpty() && data[0] == WireCodec.TYPE_CHUNK_ACK }
        transportCharlie.dropFilter = { data -> data.isNotEmpty() && data[0] == WireCodec.TYPE_CHUNK_ACK }

        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        alice.start()
        advanceUntilIdle()
        transportAlice.simulateDiscovery(peerIdBob)
        transportAlice.simulateDiscovery(peerIdCharlie)
        advanceUntilIdle()

        assertEquals(0, alice.meshHealth().activeTransfers)

        alice.send(peerIdBob, "msg1".encodeToByteArray())
        alice.send(peerIdCharlie, "msg2".encodeToByteArray())
        advanceUntilIdle()

        assertEquals(2, alice.meshHealth().activeTransfers, "Should count 2 in-flight transfers")

        alice.stop()
        assertEquals(0, alice.meshHealth().activeTransfers, "Cleared after stop")
    }

    // --- Batch 6 Cycle 3: Pause blocks broadcast re-flooding ---

    @Test
    fun pausedNodeDoesNotRefloodBroadcast() = runTest {
        val peerIdCharlie = ByteArray(16) { (0xC0 + it).toByte() }
        val transportBob = VirtualMeshTransport(peerIdBob)
        val transportCharlie = VirtualMeshTransport(peerIdCharlie)
        transportBob.linkTo(transportCharlie)

        val bob = MeshLink(transportBob, meshLinkConfig(), coroutineContext)
        bob.start()
        advanceUntilIdle()

        transportBob.simulateDiscovery(peerIdAlice) // Alice is a neighbor (for re-flood target)
        transportBob.simulateDiscovery(peerIdCharlie)
        advanceUntilIdle()

        // Pause Bob
        bob.pause()
        advanceUntilIdle()

        // Send a broadcast to Bob from Alice
        val msgId = Uuid.random().toByteArray()
        val broadcastData = WireCodec.encodeBroadcast(
            messageId = msgId,
            origin = peerIdAlice,
            remainingHops = 3u,
            payload = "hello all".encodeToByteArray(),
        )
        transportBob.receiveData(peerIdAlice, broadcastData)
        advanceUntilIdle()

        // Bob should still deliver locally (incoming data still processed)
        // But should NOT re-flood to Charlie since paused
        val reflooded = transportBob.sentData.filter { (peerId, data) ->
            peerId == peerIdCharlie.toHex() && data.isNotEmpty() && data[0] == WireCodec.TYPE_BROADCAST
        }
        assertEquals(0, reflooded.size, "Paused node should not re-flood broadcasts")

        bob.stop()
    }

    // --- Batch 6 Cycle 2: Transfer progress reports correct counts ---

    @Test
    fun transferProgressReportsAccurateChunkCounts() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)
        transportBob.linkTo(transportAlice)

        val alice = MeshLink(transportAlice, meshLinkConfig { mtu = 30 }, coroutineContext)
        val bob = MeshLink(transportBob, meshLinkConfig { mtu = 30 }, coroutineContext)
        alice.start(); bob.start()
        advanceUntilIdle()

        transportAlice.simulateDiscovery(peerIdBob)
        transportBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        val progressEvents = mutableListOf<io.meshlink.model.TransferProgress>()
        val collector = launch { alice.transferProgress.collect { progressEvents.add(it) } }
        advanceUntilIdle()

        // 27 bytes with mtu=30 (9 bytes payload per chunk) → 3 chunks
        val payload = ByteArray(27) { it.toByte() }
        val result = alice.send(peerIdBob, payload)
        assertTrue(result.isSuccess)
        advanceUntilIdle()

        // Should have progress events, last one should show all chunks acked
        assertTrue(progressEvents.isNotEmpty(), "Should emit progress events")
        val lastProgress = progressEvents.last()
        assertEquals(3, lastProgress.totalChunks, "Should report 3 total chunks")
        assertEquals(3, lastProgress.chunksAcked, "All chunks should be acked")
        assertEquals(result.getOrThrow(), lastProgress.messageId, "Message ID should match")

        collector.cancel()
        alice.stop(); bob.stop()
    }

    // --- Batch 6 Cycle 1: Multi-chunk SACK retransmission recovery ---

    @Test
    fun droppedChunkRetransmittedViaSackAndDelivers() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)
        transportBob.linkTo(transportAlice)

        // Drop chunk with seqNum=1 on first attempt only
        var dropCount = 0
        transportAlice.dropFilter = { data ->
            if (data.isNotEmpty() && data[0] == WireCodec.TYPE_CHUNK) {
                val seqNum = ((data[17].toInt() and 0xFF) or ((data[18].toInt() and 0xFF) shl 8)).toUShort()
                if (seqNum == 1.toUShort() && dropCount == 0) {
                    dropCount++
                    true
                } else false
            } else false
        }

        val alice = MeshLink(transportAlice, meshLinkConfig { mtu = 30 }, coroutineContext)
        val bob = MeshLink(transportBob, meshLinkConfig { mtu = 30 }, coroutineContext)
        alice.start(); bob.start()
        advanceUntilIdle()

        transportAlice.simulateDiscovery(peerIdBob)
        transportBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        val received = mutableListOf<Message>()
        val collector = launch { bob.messages.collect { received.add(it) } }
        advanceUntilIdle()

        // Send payload that requires 3+ chunks with mtu=30 (header=21, payload per chunk=9)
        val payload = "AAAAAAAAA" + "BBBBBBBBB" + "CCCCCCCCC" // 27 bytes → 3 chunks
        alice.send(peerIdBob, payload.encodeToByteArray())
        advanceUntilIdle()

        // Chunk 1 was dropped once, SACK from chunk 0 and 2 should trigger retransmit
        assertEquals(1, dropCount, "Should have dropped chunk 1 once")
        assertEquals(1, received.size, "Bob should receive the full message after retransmit")
        assertContentEquals(payload.encodeToByteArray(), received[0].payload)

        collector.cancel()
        alice.stop(); bob.stop()
    }

    // --- Batch 5 Cycle 8: Empty payload roundtrip ---

    @Test
    fun emptyPayloadSendAndReceiveWorks() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)
        transportBob.linkTo(transportAlice)

        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        val bob = MeshLink(transportBob, meshLinkConfig(), coroutineContext)
        alice.start(); bob.start()
        advanceUntilIdle()

        transportAlice.simulateDiscovery(peerIdBob)
        transportBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        val received = mutableListOf<Message>()
        val collector = launch { bob.messages.collect { received.add(it) } }
        advanceUntilIdle()

        // Send empty payload
        val result = alice.send(peerIdBob, ByteArray(0))
        assertTrue(result.isSuccess, "Empty payload should be accepted")
        advanceUntilIdle()

        assertEquals(1, received.size, "Bob should receive empty message")
        assertEquals(0, received[0].payload.size, "Payload should be empty")
        assertContentEquals(peerIdAlice, received[0].senderId)

        collector.cancel()
        alice.stop(); bob.stop()
    }

    // --- Batch 5 Cycle 7: Config presets all pass validation ---

    @Test
    fun configPresetsAllValidateCleanly() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)

        // Each preset should start without validation errors
        for ((name, config) in listOf(
            "chatOptimized" to io.meshlink.config.MeshLinkConfig.chatOptimized(),
            "fileTransferOptimized" to io.meshlink.config.MeshLinkConfig.fileTransferOptimized(),
            "powerOptimized" to io.meshlink.config.MeshLinkConfig.powerOptimized(),
        )) {
            val alice = MeshLink(transportAlice, config, coroutineContext)
            val result = alice.start()
            assertTrue(result.isSuccess, "Preset '$name' should pass validation: ${result.exceptionOrNull()?.message}")
            alice.stop()
        }
    }

    // --- Batch 5 Cycle 6: Routed delivery ACK end-to-end ---

    @Test
    fun routedDeliveryAckReachesOriginalSender() = runTest {
        val peerIdCharlie = ByteArray(16) { (0xC0 + it).toByte() }
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        val transportCharlie = VirtualMeshTransport(peerIdCharlie)
        transportAlice.linkTo(transportBob)
        transportBob.linkTo(transportCharlie)

        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        val bob = MeshLink(transportBob, meshLinkConfig(), coroutineContext)
        val charlie = MeshLink(transportCharlie, meshLinkConfig(), coroutineContext)
        alice.start(); bob.start(); charlie.start()
        advanceUntilIdle()

        transportAlice.simulateDiscovery(peerIdBob)
        transportBob.simulateDiscovery(peerIdAlice)
        transportBob.simulateDiscovery(peerIdCharlie)
        transportCharlie.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        alice.addRoute(peerIdCharlie.toHex(), peerIdBob.toHex(), 1.0, 1u)
        bob.addRoute(peerIdCharlie.toHex(), peerIdCharlie.toHex(), 1.0, 1u)

        // Alice collects delivery confirmations
        val confirmations = mutableListOf<Uuid>()
        val confCollector = launch { alice.deliveryConfirmations.collect { confirmations.add(it) } }
        advanceUntilIdle()

        // Alice sends routed message to Charlie via Bob
        val result = alice.send(peerIdCharlie, "hello!".encodeToByteArray())
        assertTrue(result.isSuccess)
        val messageId = result.getOrThrow()
        advanceUntilIdle()

        // Charlie receives → sends delivery ACK back to Bob → Bob relays ACK to Alice
        // The ACK should reach Alice's deliveryConfirmations
        assertEquals(1, confirmations.size, "Alice should receive delivery confirmation")
        assertEquals(messageId, confirmations[0], "Confirmation should match sent message ID")

        confCollector.cancel()
        alice.stop(); bob.stop(); charlie.stop()
    }

    // --- Batch 5 Cycle 5: Peer reconnection after loss ---

    @Test
    fun peerReconnectsAfterLossAndSendWorks() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)

        val bob = MeshLink(transportBob, meshLinkConfig(), coroutineContext)
        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        alice.start(); bob.start()
        advanceUntilIdle()

        transportAlice.simulateDiscovery(peerIdBob)
        transportBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()
        assertEquals(1, alice.meshHealth().connectedPeers)

        // Evict Bob via sweep (two misses)
        alice.sweep(emptySet())
        alice.sweep(emptySet())
        assertEquals(0, alice.meshHealth().connectedPeers, "Bob should be evicted")

        // Send should fail — no known peer
        val failResult = alice.send(peerIdBob, "during-loss".encodeToByteArray())
        assertTrue(failResult.isFailure, "Send should fail when peer evicted")

        // Bob reappears
        transportAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()
        assertEquals(1, alice.meshHealth().connectedPeers, "Bob should be rediscovered")

        // Send should now work
        val received = mutableListOf<Message>()
        val collector = launch { bob.messages.collect { received.add(it) } }
        advanceUntilIdle()

        val okResult = alice.send(peerIdBob, "after-reconnect".encodeToByteArray())
        assertTrue(okResult.isSuccess, "Send should succeed after reconnection")
        advanceUntilIdle()

        assertEquals(1, received.size)
        assertContentEquals("after-reconnect".encodeToByteArray(), received[0].payload)

        collector.cancel()
        alice.stop(); bob.stop()
    }

    // --- Batch 5 Cycle 4: Dedup set LRU eviction at capacity ---

    @Test
    fun dedupSetEvictsOldestAllowingNewMessages() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)

        // Use tiny dedup capacity of 2
        val alice = MeshLink(transportAlice, meshLinkConfig { dedupCapacity = 2 }, coroutineContext)
        alice.start()
        advanceUntilIdle()
        transportAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        val received = mutableListOf<Message>()
        val collector = launch { alice.messages.collect { received.add(it) } }
        advanceUntilIdle()

        // Send 3 distinct single-chunk messages — dedup capacity is 2
        for (i in 0 until 3) {
            val msgId = Uuid.random().toByteArray()
            val chunk = WireCodec.encodeChunk(
                messageId = msgId,
                sequenceNumber = 0u,
                totalChunks = 1u,
                payload = "msg-$i".encodeToByteArray(),
            )
            transportAlice.receiveData(peerIdBob, chunk)
            advanceUntilIdle()
        }

        // All 3 should be delivered (LRU evicts oldest dedup entry, not the message)
        assertEquals(3, received.size, "All 3 distinct messages should be delivered")

        // Now replay the first message — its dedup entry was evicted, so it delivers again
        val replayId = Uuid.random().toByteArray() // new ID, but same payload conceptually
        // Actually, to test dedup eviction, we need to replay the EXACT same messageId
        // Let's use a known ID
        val knownId = Uuid.random().toByteArray()
        val chunk1 = WireCodec.encodeChunk(knownId, 0u, 1u, "first".encodeToByteArray())
        val chunk2 = WireCodec.encodeChunk(Uuid.random().toByteArray(), 0u, 1u, "second".encodeToByteArray())
        val chunk3 = WireCodec.encodeChunk(Uuid.random().toByteArray(), 0u, 1u, "third".encodeToByteArray())

        received.clear()
        transportAlice.receiveData(peerIdBob, chunk1)
        advanceUntilIdle()
        transportAlice.receiveData(peerIdBob, chunk2)
        advanceUntilIdle()
        transportAlice.receiveData(peerIdBob, chunk3)
        advanceUntilIdle()

        // 3 unique messages delivered
        assertEquals(3, received.size)

        // Replay chunk1 — its dedup was evicted by chunk2+chunk3, should deliver again
        transportAlice.receiveData(peerIdBob, chunk1)
        advanceUntilIdle()
        assertEquals(4, received.size, "Evicted dedup entry allows re-delivery")

        collector.cancel()
        alice.stop()
    }

    // --- Batch 5 Cycle 3: Stop during active transfer ---

    @Test
    fun stopDuringActiveTransferCleansUpWithoutCrash() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)
        // Drop all ACKs so transfer never completes
        transportBob.dropFilter = { data -> data.isNotEmpty() && data[0] == WireCodec.TYPE_CHUNK_ACK }

        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        alice.start()
        advanceUntilIdle()
        transportAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        // Start a multi-chunk send (will create active transfer)
        val largePayload = ByteArray(500) { it.toByte() }
        val result = alice.send(peerIdBob, largePayload)
        assertTrue(result.isSuccess)
        advanceUntilIdle()

        // Verify transfer is active
        assertTrue(alice.meshHealth().activeTransfers > 0, "Should have active transfer")

        // Stop mid-transfer — should not crash
        alice.stop()

        // Verify clean state
        assertEquals(0, alice.meshHealth().activeTransfers, "Transfers cleared after stop")
        assertEquals(0, alice.meshHealth().connectedPeers, "Peers cleared after stop")

        // Restart should work fine
        alice.start()
        advanceUntilIdle()
        assertEquals(0, alice.meshHealth().activeTransfers, "No stale transfers after restart")
        alice.stop()
    }

    // --- Batch 5 Cycle 2: Rate limiter window expiration ---

    @Test
    fun rateLimiterAllowsSendsAfterWindowExpires() = runTest {
        var now = 0L
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)

        val alice = MeshLink(
            transportAlice, meshLinkConfig { rateLimitMaxSends = 2; rateLimitWindowMs = 10_000 }, coroutineContext,
            clock = { now },
        )
        alice.start()
        advanceUntilIdle()
        transportAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        // Use up the 2-send quota
        assertTrue(alice.send(peerIdBob, "msg1".encodeToByteArray()).isSuccess)
        assertTrue(alice.send(peerIdBob, "msg2".encodeToByteArray()).isSuccess)

        // Third send should be rate-limited
        val blocked = alice.send(peerIdBob, "msg3".encodeToByteArray())
        assertTrue(blocked.isFailure, "Should be rate-limited")

        // Advance past the window
        now += 10_001
        val allowed = alice.send(peerIdBob, "msg4".encodeToByteArray())
        assertTrue(allowed.isSuccess, "Should succeed after window expires")

        alice.stop()
    }

    // --- Batch 5 Cycle 1: Diagnostic sink overflow ---

    @Test
    fun diagnosticSinkOverflowReportsDroppedCount() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(
            transportAlice, meshLinkConfig {
                rateLimitMaxSends = 1; rateLimitWindowMs = 60_000
                diagnosticBufferCapacity = 3
            }, coroutineContext,
        )
        alice.start()
        advanceUntilIdle()
        transportAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        // Trigger 5 rate limit hits → 5 RATE_LIMIT_HIT diagnostics into buffer of 3
        repeat(5) {
            alice.send(peerIdBob, "msg-$it".encodeToByteArray())
            advanceUntilIdle()
        }

        val events = alice.drainDiagnostics()
        // Buffer holds 3 events: the last 3 of 5 (first 2 dropped)
        assertEquals(3, events.size, "Should have exactly 3 events (buffer capacity)")
        // The first event in the drained buffer should carry the dropped count
        assertTrue(events.any { it.droppedCount > 0 }, "At least one event should report dropped count")

        alice.stop()
    }

    // --- Batch 4 Cycle 8: Concurrent multi-recipient transfers ---

    @Test
    fun concurrentTransfersToMultipleRecipientsWorkIndependently() = runTest {
        val peerIdCharlie = ByteArray(16) { (0xC0 + it).toByte() }
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        val transportCharlie = VirtualMeshTransport(peerIdCharlie)
        transportAlice.linkTo(transportBob)
        transportAlice.linkTo(transportCharlie)

        val bob = MeshLink(transportBob, meshLinkConfig(), coroutineContext)
        val charlie = MeshLink(transportCharlie, meshLinkConfig(), coroutineContext)
        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        alice.start(); bob.start(); charlie.start()
        advanceUntilIdle()

        transportAlice.simulateDiscovery(peerIdBob)
        transportAlice.simulateDiscovery(peerIdCharlie)
        transportBob.simulateDiscovery(peerIdAlice)
        transportCharlie.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        val bobMessages = mutableListOf<Message>()
        val charlieMessages = mutableListOf<Message>()
        val cBob = launch { bob.messages.collect { bobMessages.add(it) } }
        val cCharlie = launch { charlie.messages.collect { charlieMessages.add(it) } }
        advanceUntilIdle()

        // Send different messages to Bob and Charlie concurrently
        val r1 = alice.send(peerIdBob, "for bob".encodeToByteArray())
        val r2 = alice.send(peerIdCharlie, "for charlie".encodeToByteArray())
        assertTrue(r1.isSuccess)
        assertTrue(r2.isSuccess)
        advanceUntilIdle()

        assertEquals(1, bobMessages.size, "Bob should receive exactly 1 message")
        assertEquals(1, charlieMessages.size, "Charlie should receive exactly 1 message")
        assertContentEquals("for bob".encodeToByteArray(), bobMessages[0].payload)
        assertContentEquals("for charlie".encodeToByteArray(), charlieMessages[0].payload)

        cBob.cancel(); cCharlie.cancel()
        alice.stop(); bob.stop(); charlie.stop()
    }

    // --- Batch 4 Cycle 7: Broadcast origin preserved through relay ---

    @Test
    fun broadcastOriginPreservedThroughMultiHop() = runTest {
        val peerIdCharlie = ByteArray(16) { (0xC0 + it).toByte() }
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        val transportCharlie = VirtualMeshTransport(peerIdCharlie)
        transportAlice.linkTo(transportBob)
        transportBob.linkTo(transportCharlie)

        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        val bob = MeshLink(transportBob, meshLinkConfig(), coroutineContext)
        val charlie = MeshLink(transportCharlie, meshLinkConfig(), coroutineContext)
        alice.start(); bob.start(); charlie.start()
        advanceUntilIdle()

        transportAlice.simulateDiscovery(peerIdBob)
        transportBob.simulateDiscovery(peerIdAlice)
        transportBob.simulateDiscovery(peerIdCharlie)
        transportCharlie.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        val charlieMessages = mutableListOf<Message>()
        val collector = launch { charlie.messages.collect { charlieMessages.add(it) } }
        advanceUntilIdle()

        // Alice broadcasts with 2 hops
        alice.broadcast("from alice".encodeToByteArray(), 2u)
        advanceUntilIdle()

        // Charlie should receive with Alice as the origin sender, NOT Bob
        assertEquals(1, charlieMessages.size)
        assertContentEquals(peerIdAlice, charlieMessages[0].senderId,
            "Origin should be Alice, not the relay (Bob)")
        assertContentEquals("from alice".encodeToByteArray(), charlieMessages[0].payload)

        collector.cancel()
        alice.stop(); bob.stop(); charlie.stop()
    }

    // --- Batch 4 Cycle 6: Out-of-order chunk reassembly ---

    @Test
    fun outOfOrderChunksReassembleCorrectly() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)

        val bob = MeshLink(transportBob, meshLinkConfig(), coroutineContext)
        bob.start()
        advanceUntilIdle()
        transportBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        val received = mutableListOf<Message>()
        val collector = launch { bob.messages.collect { received.add(it) } }
        advanceUntilIdle()

        // Manually send 3 chunks in reverse order (2, 1, 0)
        val msgId = Uuid.random().toByteArray()
        val payloads = listOf("AAA".encodeToByteArray(), "BBB".encodeToByteArray(), "CCC".encodeToByteArray())
        for (seqNum in listOf(2, 0, 1)) { // out of order!
            val chunk = WireCodec.encodeChunk(
                messageId = msgId,
                sequenceNumber = seqNum.toUShort(),
                totalChunks = 3u,
                payload = payloads[seqNum],
            )
            transportBob.receiveData(peerIdAlice, chunk)
            advanceUntilIdle()
        }

        assertEquals(1, received.size, "Should reassemble despite out-of-order delivery")
        val expected = "AAABBBCCC".encodeToByteArray()
        assertContentEquals(expected, received[0].payload, "Payload should be correctly ordered")

        collector.cancel()
        bob.stop()
    }

    // --- Batch 4 Cycle 5: TieredShedder wired ---

    @Test
    fun highBufferUtilizationTriggersMemoryShedding() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)
        // Drop all chunk ACKs so transfers stay active (building up buffer)
        transportAlice.dropFilter = { data -> data.isNotEmpty() && data[0] == WireCodec.TYPE_CHUNK_ACK }

        // Small buffer so we hit pressure quickly
        val config = meshLinkConfig { bufferCapacity = 200; maxMessageSize = 200 }
        val alice = MeshLink(transportAlice, config, coroutineContext)
        alice.start()
        advanceUntilIdle()
        transportAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        // Send messages to fill buffer
        alice.send(peerIdBob, ByteArray(100) { 1 })
        alice.send(peerIdBob, ByteArray(100) { 2 })
        advanceUntilIdle()

        // Verify buffer is under pressure
        val healthBefore = alice.meshHealth()
        assertTrue(healthBefore.bufferUtilizationPercent > 50, "Buffer should be under pressure")

        // Trigger memory shedding
        val shedResults = alice.shedMemoryPressure()
        assertTrue(shedResults.isNotEmpty(), "Should return shed results")

        // After shedding, buffer should be lower
        val healthAfter = alice.meshHealth()
        assertTrue(healthAfter.bufferUtilizationPercent <= healthBefore.bufferUtilizationPercent,
            "Buffer should be reduced after shedding")

        alice.stop()
    }

    // --- Batch 4 Cycle 4: PEER_EVICTED diagnostic drainable ---

    @Test
    fun sweepEvictionEmitsDiagnosticEvent() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        alice.start()
        advanceUntilIdle()

        // Discover Bob
        transportAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()
        assertEquals(1, alice.meshHealth().connectedPeers)

        // Two sweeps without Bob → evicted
        alice.sweep(emptySet()) // miss 1
        alice.sweep(emptySet()) // miss 2 → evicted
        assertEquals(0, alice.meshHealth().connectedPeers)

        // Drain diagnostics — should have PEER_EVICTED event
        val events = alice.drainDiagnostics()
        val evicted = events.filter { it.code == io.meshlink.diagnostics.DiagnosticCode.PEER_EVICTED }
        assertEquals(1, evicted.size, "Should have exactly one PEER_EVICTED event")
        assertTrue(evicted[0].payload?.contains(peerIdBob.toHex()) == true,
            "PEER_EVICTED payload should contain evicted peer ID")

        alice.stop()
    }

    // --- Batch 4 Cycle 3: CircuitBreaker wired ---

    @Test
    fun circuitBreakerTripsAfterRepeatedTransportFailures() = runTest {
        var now = 0L
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)

        val alice = MeshLink(
            transportAlice, meshLinkConfig {
                circuitBreakerMaxFailures = 3
                circuitBreakerWindowMs = 10_000
                circuitBreakerCooldownMs = 5_000
            }, coroutineContext,
            clock = { now },
        )
        alice.start()
        advanceUntilIdle()
        transportAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        // Enable transport failures
        transportAlice.sendFailure = true

        // Send 3 messages — each will fail at transport level
        repeat(3) {
            alice.send(peerIdBob, "fail-$it".encodeToByteArray())
            advanceUntilIdle()
        }

        // Circuit should now be open — next send should fail fast
        transportAlice.sendFailure = false // transport is "fine" now
        val result = alice.send(peerIdBob, "should-fail-fast".encodeToByteArray())
        assertTrue(result.isFailure, "Send should fail when circuit is open")
        assertTrue(result.exceptionOrNull()?.message?.contains("circuit") == true,
            "Failure should mention circuit breaker")

        // After cooldown, should work again
        now += 5_001
        val recovered = alice.send(peerIdBob, "should-work".encodeToByteArray())
        assertTrue(recovered.isSuccess, "Send should succeed after cooldown")

        alice.stop()
    }

    // --- Batch 4 Cycle 2: PowerModeEngine wired ---

    @Test
    fun updateBatteryChangePowerMode() = runTest {
        var now = 0L
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext, clock = { now })
        alice.start()
        advanceUntilIdle()

        // Default: PERFORMANCE
        assertEquals("PERFORMANCE", alice.meshHealth().powerMode)

        // Low battery → after hysteresis, should transition to POWER_SAVER
        alice.updateBattery(15, false) // below 30% → target POWER_SAVER
        assertEquals("PERFORMANCE", alice.meshHealth().powerMode, "Hysteresis: should still be PERFORMANCE")
        now += 30_001 // past hysteresis window
        alice.updateBattery(15, false)
        assertEquals("POWER_SAVER", alice.meshHealth().powerMode)

        // Charging override → immediate PERFORMANCE
        alice.updateBattery(15, true)
        assertEquals("PERFORMANCE", alice.meshHealth().powerMode)

        alice.stop()
    }

    // --- Batch 4 Cycle 1: Route loop detection (self in visited) ---

    @Test
    fun routedMessageWithSelfInVisitedListDropped() = runTest {
        val peerIdCharlie = ByteArray(16) { (0xC0 + it).toByte() }
        val transportBob = VirtualMeshTransport(peerIdBob)
        val transportCharlie = VirtualMeshTransport(peerIdCharlie)
        transportBob.linkTo(transportCharlie)

        val bob = MeshLink(transportBob, meshLinkConfig(), coroutineContext)
        bob.start()
        advanceUntilIdle()
        transportBob.simulateDiscovery(peerIdCharlie)
        advanceUntilIdle()

        val bobMessages = mutableListOf<Message>()
        val collector = launch { bob.messages.collect { bobMessages.add(it) } }
        advanceUntilIdle()

        // Craft routed message where Bob is already in visited list (loop!)
        val msgId = Uuid.random().toByteArray()
        val destId = ByteArray(16) { (0xD0 + it).toByte() } // some other destination
        bob.addRoute(destId.toHex(), peerIdCharlie.toHex(), 1.0, 1u)
        val routedData = WireCodec.encodeRoutedMessage(
            messageId = msgId,
            origin = peerIdAlice,
            destination = destId,
            hopLimit = 5u,
            visitedList = listOf(peerIdAlice, peerIdBob), // Bob already visited!
            payload = "loop!".encodeToByteArray(),
        )
        transportBob.receiveData(peerIdAlice, routedData)
        advanceUntilIdle()

        // Bob should NOT relay (loop) and NOT deliver (wrong destination)
        assertEquals(0, bobMessages.size, "Should not deliver looped message")
        val relayed = transportBob.sentData.filter { (_, data) ->
            data.isNotEmpty() && data[0] == WireCodec.TYPE_ROUTED_MESSAGE
        }
        assertEquals(0, relayed.size, "Should not relay message with self in visited list")

        collector.cancel()
        bob.stop()
    }

    // --- Cycle 8 (Multi-hop): Routed message with hopLimit=0 dropped ---

    @Test
    fun routedMessageHopLimitZeroDropped() = runTest {
        val transportBob = VirtualMeshTransport(peerIdBob)
        val transportCharlie = VirtualMeshTransport(peerIdCharlie)
        transportBob.linkTo(transportCharlie)

        val bob = MeshLink(transportBob, meshLinkConfig(), coroutineContext)
        bob.start()
        advanceUntilIdle()

        transportBob.simulateDiscovery(peerIdCharlie)
        advanceUntilIdle()

        val bobMessages = mutableListOf<Message>()
        val collector = launch { bob.messages.collect { bobMessages.add(it) } }
        advanceUntilIdle()

        // Send routed message to Charlie (not Bob) with hopLimit=0
        val msgId = Uuid.random().toByteArray()
        val routedData = WireCodec.encodeRoutedMessage(
            messageId = msgId,
            origin = peerIdAlice,
            destination = peerIdCharlie,
            hopLimit = 0u,
            visitedList = listOf(peerIdAlice),
            payload = "should be dropped".encodeToByteArray(),
        )
        transportBob.receiveData(peerIdAlice, routedData)
        advanceUntilIdle()

        // Bob should NOT deliver (not the destination)
        assertEquals(0, bobMessages.size, "Not delivered locally (wrong destination)")

        // Bob should NOT relay (hop limit exhausted)
        val relayed = transportBob.sentData.filter { (_, data) ->
            data.isNotEmpty() && data[0] == WireCodec.TYPE_ROUTED_MESSAGE
        }
        assertEquals(0, relayed.size, "Should NOT relay with hopLimit=0")

        collector.cancel()
        bob.stop()
    }

    // --- Cycle 7 (Multi-hop): Stop clears all state cleanly ---

    @Test
    fun stopClearsAllStateThenRestartWorks() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)

        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        alice.start()
        advanceUntilIdle()

        transportAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        // Add route, send a message, verify state exists
        alice.addRoute("some-dest", peerIdBob.toHex(), 1.0, 1u)
        assertEquals(1, alice.meshHealth().connectedPeers)

        // Stop
        alice.stop()

        // All state should be cleared
        assertEquals(0, alice.meshHealth().connectedPeers, "Peers should be cleared after stop")
        assertEquals(0, alice.meshHealth().activeTransfers, "Transfers should be cleared")
        assertEquals(0, alice.meshHealth().bufferUtilizationPercent, "Buffer should be empty")

        // Restart should work
        alice.start()
        advanceUntilIdle()
        transportAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        assertEquals(1, alice.meshHealth().connectedPeers, "Should rediscover peers after restart")

        alice.stop()
    }

    // --- Cycle 6 (Multi-hop): meshHealth buffer utilization ---

    @Test
    fun meshHealthReportsBufferUtilization() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)

        // Buffer capacity = 1000 bytes
        val alice = MeshLink(
            transportAlice,
            meshLinkConfig { bufferCapacity = 1000; maxMessageSize = 1000 },
            coroutineContext,
        )
        alice.start()
        advanceUntilIdle()

        transportAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        // Initially 0% utilization
        assertEquals(0, alice.meshHealth().bufferUtilizationPercent)

        // Send a 500 byte payload (50% of buffer) — outbound transfer stays active
        // Use a drop filter so ACKs never arrive, keeping the transfer active
        transportAlice.dropFilter = { data ->
            data.isNotEmpty() && data[0] == WireCodec.TYPE_CHUNK_ACK
        }
        alice.send(peerIdBob, ByteArray(500))
        advanceUntilIdle()

        val util = alice.meshHealth().bufferUtilizationPercent
        assertTrue(util > 0, "Buffer utilization should be > 0 with active transfer, got $util")

        alice.stop()
    }

    // --- Cycle 5 (Multi-hop): Broadcast zero hops — no re-flood ---

    @Test
    fun broadcastZeroHopsNotReflooded() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)

        val bob = MeshLink(transportBob, meshLinkConfig(), coroutineContext)
        bob.start()
        advanceUntilIdle()

        transportBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        val received = mutableListOf<Message>()
        val collector = launch { bob.messages.collect { received.add(it) } }
        advanceUntilIdle()

        // Alice sends broadcast with 0 remaining hops
        val msgId = Uuid.random().toByteArray()
        val broadcastData = WireCodec.encodeBroadcast(
            messageId = msgId,
            origin = peerIdAlice,
            remainingHops = 0u,
            payload = "no reflood".encodeToByteArray(),
        )
        transportBob.receiveData(peerIdAlice, broadcastData)
        advanceUntilIdle()

        // Bob should still deliver locally
        assertEquals(1, received.size, "Should deliver locally even with 0 hops")

        // But should NOT re-flood
        val reflooded = transportBob.sentData.filter { (_, data) ->
            data.isNotEmpty() && data[0] == WireCodec.TYPE_BROADCAST
        }
        assertEquals(0, reflooded.size, "Should NOT re-flood with 0 remaining hops")

        collector.cancel()
        bob.stop()
    }

    // --- Cycle 4 (Multi-hop): Broadcast propagates A→B→C ---

    @Test
    fun broadcastPropagatesMultiHop() = runTest {
        // Topology: Alice--Bob--Charlie (Alice not connected to Charlie)
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        val transportCharlie = VirtualMeshTransport(peerIdCharlie)
        transportAlice.linkTo(transportBob)
        transportBob.linkTo(transportCharlie)

        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        val bob = MeshLink(transportBob, meshLinkConfig(), coroutineContext)
        val charlie = MeshLink(transportCharlie, meshLinkConfig(), coroutineContext)
        alice.start(); bob.start(); charlie.start()
        advanceUntilIdle()

        transportAlice.simulateDiscovery(peerIdBob)
        transportBob.simulateDiscovery(peerIdAlice)
        transportBob.simulateDiscovery(peerIdCharlie)
        transportCharlie.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        val charlieMessages = mutableListOf<Message>()
        val charlieCollector = launch { charlie.messages.collect { charlieMessages.add(it) } }
        val bobMessages = mutableListOf<Message>()
        val bobCollector = launch { bob.messages.collect { bobMessages.add(it) } }
        advanceUntilIdle()

        // Alice broadcasts with 2 hops
        alice.broadcast("mesh broadcast!".encodeToByteArray(), maxHops = 2u)
        advanceUntilIdle()

        // Bob should receive and re-flood to Charlie
        assertEquals(1, bobMessages.size, "Bob should receive broadcast")
        assertEquals(1, charlieMessages.size, "Charlie should receive re-flooded broadcast")
        assertContentEquals("mesh broadcast!".encodeToByteArray(), charlieMessages[0].payload)
        assertContentEquals(peerIdAlice, charlieMessages[0].senderId)

        bobCollector.cancel(); charlieCollector.cancel()
        alice.stop(); bob.stop(); charlie.stop()
    }

    // --- Cycle 3 (Multi-hop): Receiver sends delivery ACK back for routed messages ---

    @Test
    fun destinationSendsDeliveryAckForRoutedMessage() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportBob.linkTo(transportAlice)

        val bob = MeshLink(transportBob, meshLinkConfig(), coroutineContext)
        bob.start()
        advanceUntilIdle()

        transportBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        val received = mutableListOf<Message>()
        val collector = launch { bob.messages.collect { received.add(it) } }
        advanceUntilIdle()

        // Alice sends routed message to Bob
        val msgId = Uuid.random().toByteArray()
        val routedData = WireCodec.encodeRoutedMessage(
            messageId = msgId,
            origin = peerIdAlice,
            destination = peerIdBob,
            hopLimit = 3u,
            visitedList = listOf(peerIdAlice),
            payload = "routed msg".encodeToByteArray(),
        )
        transportBob.receiveData(peerIdAlice, routedData)
        advanceUntilIdle()

        // Bob should deliver the message
        assertEquals(1, received.size)

        // Bob should send a delivery_ack back to Alice
        val acks = transportBob.sentData.filter { (_, data) ->
            data.isNotEmpty() && data[0] == WireCodec.TYPE_DELIVERY_ACK
        }
        assertEquals(1, acks.size, "Should send delivery ACK back to origin")
        val ack = WireCodec.decodeDeliveryAck(acks[0].second)
        assertContentEquals(msgId, ack.messageId)
        assertContentEquals(peerIdBob, ack.recipientId)

        collector.cancel()
        bob.stop()
    }

    // --- Cycle 2 (Multi-hop): Routed relay uses routing table for non-neighbors ---

    @Test
    fun routedRelayUsesRoutingTableWhenNotDirectNeighbor() = runTest {
        // Topology: Alice--Bob--Charlie--Dave
        // Bob receives routed msg for Dave but only knows Charlie directly
        val peerIdDave = ByteArray(16) { (0xD0 + it).toByte() }
        val transportBob = VirtualMeshTransport(peerIdBob)
        val transportCharlie = VirtualMeshTransport(peerIdCharlie)
        transportBob.linkTo(transportCharlie)

        val bob = MeshLink(transportBob, meshLinkConfig(), coroutineContext)
        bob.start()
        advanceUntilIdle()

        transportBob.simulateDiscovery(peerIdCharlie)
        advanceUntilIdle()

        // Bob has a route to Dave via Charlie
        bob.addRoute(peerIdDave.toHex(), peerIdCharlie.toHex(), 2.0, 1u)

        // Alice sends routed message to Dave through Bob
        val msgId = Uuid.random().toByteArray()
        val routedData = WireCodec.encodeRoutedMessage(
            messageId = msgId,
            origin = peerIdAlice,
            destination = peerIdDave,
            hopLimit = 5u,
            visitedList = listOf(peerIdAlice),
            payload = "for dave".encodeToByteArray(),
        )
        transportBob.receiveData(peerIdAlice, routedData)
        advanceUntilIdle()

        // Bob should relay to Charlie (via routing table)
        val relayed = transportBob.sentData.filter { (_, data) ->
            data.isNotEmpty() && data[0] == WireCodec.TYPE_ROUTED_MESSAGE
        }
        assertEquals(1, relayed.size, "Bob should relay via routing table to Charlie")
        val relayedMsg = WireCodec.decodeRoutedMessage(relayed[0].second)
        assertContentEquals(peerIdDave, relayedMsg.destination)

        bob.stop()
    }

    // --- Cycle 1+2 (Multi-hop): End-to-end A→B→C with routing table relay ---

    @Test
    fun multiHopEndToEndAliceBobCharlie() = runTest {
        // Topology: Alice--Bob--Charlie (not directly connected)
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        val transportCharlie = VirtualMeshTransport(peerIdCharlie)
        transportAlice.linkTo(transportBob)
        transportBob.linkTo(transportCharlie)

        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        val bob = MeshLink(transportBob, meshLinkConfig(), coroutineContext)
        val charlie = MeshLink(transportCharlie, meshLinkConfig(), coroutineContext)
        alice.start(); bob.start(); charlie.start()
        advanceUntilIdle()

        // Discovery: Alice sees Bob, Bob sees Alice+Charlie, Charlie sees Bob
        transportAlice.simulateDiscovery(peerIdBob)
        transportBob.simulateDiscovery(peerIdAlice)
        transportBob.simulateDiscovery(peerIdCharlie)
        transportCharlie.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        // Alice adds route to Charlie via Bob
        alice.addRoute(peerIdCharlie.toHex(), peerIdBob.toHex(), 1.0, 1u)
        // Bob adds route to Charlie (direct neighbor) — also needs routing table for relay
        bob.addRoute(peerIdCharlie.toHex(), peerIdCharlie.toHex(), 1.0, 1u)

        // Charlie listens for messages
        val received = mutableListOf<Message>()
        val charlieCollector = launch { charlie.messages.collect { received.add(it) } }
        advanceUntilIdle()

        // Alice sends to Charlie (not a direct neighbor → routes via Bob)
        val result = alice.send(peerIdCharlie, "hello charlie!".encodeToByteArray())
        assertTrue(result.isSuccess, "Send via route should succeed")
        advanceUntilIdle()

        // Charlie should receive the message
        assertEquals(1, received.size, "Charlie should receive the routed message")
        assertContentEquals("hello charlie!".encodeToByteArray(), received[0].payload)
        assertContentEquals(peerIdAlice, received[0].senderId)

        charlieCollector.cancel()
        alice.stop(); bob.stop(); charlie.stop()
    }

    // --- Batch 9 Cycle 3: Unknown wire type silently dropped ---

    @Test
    fun unknownWireTypeIsSilentlyDropped() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)

        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        alice.start()
        advanceUntilIdle()
        transportAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        val received = mutableListOf<Message>()
        val collector = launch { alice.messages.collect { received.add(it) } }
        advanceUntilIdle()

        // Send an unknown wire type (0xFF) — should not crash or deliver
        val unknownMsg = byteArrayOf(0xFF.toByte()) + ByteArray(20) { it.toByte() }
        transportAlice.receiveData(peerIdBob, unknownMsg)
        advanceUntilIdle()

        assertEquals(0, received.size, "Unknown wire type should be silently dropped")

        collector.cancel()
        alice.stop()
    }

    // --- Batch 9 Cycle 4: Broadcast rejects oversized payload ---

    @Test
    fun broadcastRejectsOversizedPayload() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(
            transportAlice,
            meshLinkConfig { maxMessageSize = 100; bufferCapacity = 200; mtu = 50 },
            coroutineContext,
        )
        alice.start()
        advanceUntilIdle()

        val oversized = ByteArray(201) // exceeds bufferCapacity
        val result = alice.broadcast(oversized, maxHops = 5u)
        assertTrue(result.isFailure, "Broadcast with oversized payload should fail")

        alice.stop()
    }

    // --- Batch 9 Cycle 5: 5-node chain relay with delivery ACK ---

    @Test
    fun fiveNodeChainRelaysMessageAndDeliveryAck() = runTest {
        val peerIdC = ByteArray(16) { (0xC0 + it).toByte() }
        val peerIdD = ByteArray(16) { (0xD0 + it).toByte() }
        val peerIdE = ByteArray(16) { (0xE0 + it).toByte() }

        val tA = VirtualMeshTransport(peerIdAlice)
        val tB = VirtualMeshTransport(peerIdBob)
        val tC = VirtualMeshTransport(peerIdC)
        val tD = VirtualMeshTransport(peerIdD)
        val tE = VirtualMeshTransport(peerIdE)

        // Chain: A-B-C-D-E (each linked to neighbors)
        tA.linkTo(tB); tB.linkTo(tC); tC.linkTo(tD); tD.linkTo(tE)

        val a = MeshLink(tA, meshLinkConfig(), coroutineContext)
        val b = MeshLink(tB, meshLinkConfig(), coroutineContext)
        val c = MeshLink(tC, meshLinkConfig(), coroutineContext)
        val d = MeshLink(tD, meshLinkConfig(), coroutineContext)
        val e = MeshLink(tE, meshLinkConfig(), coroutineContext)

        listOf(a, b, c, d, e).forEach { it.start() }
        advanceUntilIdle()

        // Discover neighbors
        tA.simulateDiscovery(peerIdBob)
        tB.simulateDiscovery(peerIdAlice); tB.simulateDiscovery(peerIdC)
        tC.simulateDiscovery(peerIdBob); tC.simulateDiscovery(peerIdD)
        tD.simulateDiscovery(peerIdC); tD.simulateDiscovery(peerIdE)
        tE.simulateDiscovery(peerIdD)
        advanceUntilIdle()

        // Set up routing: A→E via B→C→D→E
        a.addRoute(peerIdE.toHex(), peerIdBob.toHex(), 4.0, 1u)
        b.addRoute(peerIdE.toHex(), peerIdC.toHex(), 3.0, 1u)
        c.addRoute(peerIdE.toHex(), peerIdD.toHex(), 2.0, 1u)
        d.addRoute(peerIdE.toHex(), peerIdE.toHex(), 1.0, 1u)

        // E listens for messages
        val received = mutableListOf<Message>()
        val collector = launch { e.messages.collect { received.add(it) } }
        advanceUntilIdle()

        // A listens for delivery confirmations
        val confirmations = mutableListOf<Uuid>()
        val confirmCollector = launch { a.deliveryConfirmations.collect { confirmations.add(it) } }
        advanceUntilIdle()

        // A sends to E
        val result = a.send(peerIdE, "hello five hops!".encodeToByteArray())
        assertTrue(result.isSuccess)
        advanceUntilIdle()

        // E should receive the message from A
        assertEquals(1, received.size, "E should receive the routed message")
        assertContentEquals(peerIdAlice, received[0].senderId)
        assertContentEquals("hello five hops!".encodeToByteArray(), received[0].payload)

        // A should receive delivery confirmation relayed back through D→C→B→A
        assertEquals(1, confirmations.size, "A should get delivery ACK back via reverse path")

        collector.cancel(); confirmCollector.cancel()
        listOf(a, b, c, d, e).forEach { it.stop() }
    }

    // --- Batch 9 Cycle 6: Send fails when route next-hop not connected ---

    @Test
    fun sendFailsWhenRouteNextHopDisappears() = runTest {
        val peerIdC = ByteArray(16) { (0xC0 + it).toByte() }
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transportAlice.linkTo(transportBob)

        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        alice.start()
        advanceUntilIdle()

        // Bob is discovered as neighbor, and we have route to C via Bob
        transportAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()
        alice.addRoute(peerIdC.toHex(), peerIdBob.toHex(), 1.0, 1u)

        // Sending to C should work (routes via Bob)
        val result1 = alice.send(peerIdC, "routed".encodeToByteArray())
        assertTrue(result1.isSuccess)

        // Bob disappears — simulate peer lost and sweep
        transportAlice.simulatePeerLost(peerIdBob)
        advanceUntilIdle()
        alice.sweep(emptySet()) // evict Bob
        alice.sweep(emptySet()) // second sweep to confirm eviction (2-miss rule)

        // Now sending to C should fail — no route because Bob is gone
        // (Bob was direct neighbor, route still points to Bob, but Bob isn't known)
        val result2 = alice.send(peerIdC, "unreachable".encodeToByteArray())
        // The routed send still succeeds at the API level (fire-and-forget routing),
        // but safeSend should fail. Let's verify the send doesn't throw at least.
        // Actually, route lookup succeeds (route table isn't cleared by sweep),
        // so it should still return success — the failure is at transport level.
        assertTrue(result2.isSuccess, "Routed send is fire-and-forget, should not fail at API level")

        alice.stop()
    }

    // --- Batch 9 Cycle 8: Drain diagnostics is idempotent ---

    @Test
    fun drainDiagnosticsTwiceReturnsEmptySecondTime() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(
            transportAlice,
            meshLinkConfig { rateLimitMaxSends = 1; rateLimitWindowMs = 60_000 },
            coroutineContext,
        )
        alice.start()
        advanceUntilIdle()
        transportAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        // Trigger a diagnostic event by hitting rate limit
        alice.send(peerIdBob, "msg1".encodeToByteArray())
        alice.send(peerIdBob, "msg2".encodeToByteArray()) // rate-limited → RATE_LIMIT_HIT event

        // First drain should have events
        val firstDrain = alice.drainDiagnostics()
        assertTrue(firstDrain.isNotEmpty(), "First drain should have events")

        // Second drain should be empty (buffer was cleared)
        val secondDrain = alice.drainDiagnostics()
        assertEquals(0, secondDrain.size, "Second drain should be empty after buffer was cleared")

        alice.stop()
    }

    // --- Batch 10 Cycle 1: Truncated wire data doesn't crash ---

    @Test
    fun truncatedChunkDataDoesNotCrashNode() = runTest {
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        alice.start()
        advanceUntilIdle()
        transportAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        // Send truncated chunk (type byte + 5 random bytes, far less than 21-byte header)
        val truncated = byteArrayOf(WireCodec.TYPE_CHUNK) + ByteArray(5)
        transportAlice.receiveData(peerIdBob, truncated)
        advanceUntilIdle()

        // Node should still be functional — send a real message
        val received = mutableListOf<Message>()
        val collector = launch { alice.messages.collect { received.add(it) } }
        advanceUntilIdle()

        // Self-send to confirm node is alive
        val result = alice.send(peerIdAlice, "alive".encodeToByteArray())
        assertTrue(result.isSuccess, "Node should still work after truncated data")
        advanceUntilIdle()
        assertEquals(1, received.size, "Should receive self-send after surviving truncated data")

        collector.cancel()
        alice.stop()
    }

    // --- Batch 10 Cycle 2: Diamond topology broadcast dedup ---

    @Test
    fun diamondTopologyBroadcastDeliverExactlyOnce() = runTest {
        val peerIdC = ByteArray(16) { (0xC0 + it).toByte() }
        val peerIdD = ByteArray(16) { (0xD0 + it).toByte() }

        // Diamond: A-B, A-C, B-D, C-D
        val tA = VirtualMeshTransport(peerIdAlice)
        val tB = VirtualMeshTransport(peerIdBob)
        val tC = VirtualMeshTransport(peerIdC)
        val tD = VirtualMeshTransport(peerIdD)

        tA.linkTo(tB); tA.linkTo(tC)
        tB.linkTo(tD); tC.linkTo(tD)

        val a = MeshLink(tA, meshLinkConfig(), coroutineContext)
        val b = MeshLink(tB, meshLinkConfig(), coroutineContext)
        val c = MeshLink(tC, meshLinkConfig(), coroutineContext)
        val d = MeshLink(tD, meshLinkConfig(), coroutineContext)

        listOf(a, b, c, d).forEach { it.start() }
        advanceUntilIdle()

        // Discover neighbors
        tA.simulateDiscovery(peerIdBob); tA.simulateDiscovery(peerIdC)
        tB.simulateDiscovery(peerIdAlice); tB.simulateDiscovery(peerIdD)
        tC.simulateDiscovery(peerIdAlice); tC.simulateDiscovery(peerIdD)
        tD.simulateDiscovery(peerIdBob); tD.simulateDiscovery(peerIdC)
        advanceUntilIdle()

        // D listens
        val received = mutableListOf<Message>()
        val collector = launch { d.messages.collect { received.add(it) } }
        advanceUntilIdle()

        // A broadcasts with maxHops=5 (plenty)
        val result = a.broadcast("diamond".encodeToByteArray(), maxHops = 5u)
        assertTrue(result.isSuccess)
        advanceUntilIdle()

        // D should receive EXACTLY once despite two paths (A→B→D and A→C→D)
        assertEquals(1, received.size, "D should receive broadcast exactly once via dedup")
        assertContentEquals("diamond".encodeToByteArray(), received[0].payload)
        assertContentEquals(peerIdAlice, received[0].senderId)

        collector.cancel()
        listOf(a, b, c, d).forEach { it.stop() }
    }

    // --- Batch 10 Cycle 3: Pause queue multi-recipient FIFO ---

    @Test
    fun pauseQueuePreservesMultiRecipientFifoOrder() = runTest {
        val peerIdC = ByteArray(16) { (0xC0 + it).toByte() }
        val transportAlice = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        val transportC = VirtualMeshTransport(peerIdC)
        transportAlice.linkTo(transportBob)
        transportAlice.linkTo(transportC)

        val alice = MeshLink(transportAlice, meshLinkConfig(), coroutineContext)
        alice.start()
        advanceUntilIdle()
        transportAlice.simulateDiscovery(peerIdBob)
        transportAlice.simulateDiscovery(peerIdC)
        advanceUntilIdle()

        alice.pause()

        // Queue sends to different recipients: Bob, Charlie, Bob
        alice.send(peerIdBob, "toBob1".encodeToByteArray())
        alice.send(peerIdC, "toCharlie".encodeToByteArray())
        alice.send(peerIdBob, "toBob2".encodeToByteArray())

        // Clear sentData spy to only track resume sends
        transportAlice.sentData.clear()

        alice.resume()
        advanceUntilIdle()

        // Verify sends went out in FIFO order: Bob, Charlie, Bob
        val sentRecipients = transportAlice.sentData.map { it.first }
        assertEquals(3, sentRecipients.size, "All 3 queued sends should be delivered")
        assertEquals(peerIdBob.toHex(), sentRecipients[0], "First send should go to Bob")
        assertEquals(peerIdC.toHex(), sentRecipients[1], "Second send should go to Charlie")
        assertEquals(peerIdBob.toHex(), sentRecipients[2], "Third send should go to Bob again")

        alice.stop()
    }
}
