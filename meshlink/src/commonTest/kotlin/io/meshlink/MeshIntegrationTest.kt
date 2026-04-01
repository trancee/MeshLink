package io.meshlink

import io.meshlink.config.testMeshLinkConfig
import io.meshlink.crypto.CryptoProvider
import io.meshlink.diagnostics.DiagnosticCode
import io.meshlink.diagnostics.DiagnosticEvent
import io.meshlink.model.Message
import io.meshlink.model.PeerEvent
import io.meshlink.model.TransferFailure
import io.meshlink.model.TransferProgress
import io.meshlink.transport.VirtualMeshTransport
import io.meshlink.util.DeliveryOutcome
import io.meshlink.util.toHex
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Integration tests exercising full MeshLink flows through the VirtualMeshTransport.
 *
 * Each test spins up real [MeshLink] instances wired to [VirtualMeshTransport]
 * peers and exercises end-to-end flows: discovery → handshake → messaging →
 * delivery confirmation → stop.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalUuidApi::class)
class MeshIntegrationTest {

    private fun peerId(index: Int) = ByteArray(16) { ((index shl 4) + it).toByte() }

    private val peerIdAlice = peerId(0xA)
    private val peerIdBob = peerId(0xB)
    private val peerIdCharlie = peerId(0xC)

    // ── Plaintext peer discovery + direct messaging ────────────────

    @Test
    fun plaintextDiscoveryAndDirectMessage() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val tBob = VirtualMeshTransport(peerIdBob)
        tAlice.linkTo(tBob)

        val config = testMeshLinkConfig { requireEncryption = false }
        val alice = MeshLink(tAlice, config, coroutineContext)
        val bob = MeshLink(tBob, config, coroutineContext)
        alice.start(); bob.start()
        advanceUntilIdle()

        // Mutual discovery
        tAlice.simulateDiscovery(peerIdBob)
        tBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        // Bob collects one message
        val receiveJob = launch {
            val msg = bob.messages.first()
            assertContentEquals(peerIdAlice, msg.senderId)
            assertEquals("hello mesh", String(msg.payload))
        }

        alice.send(peerIdBob, "hello mesh".encodeToByteArray())
        advanceUntilIdle()
        receiveJob.join()

        alice.stop(); bob.stop()
    }

    // ── Encrypted handshake → direct message round-trip ───────────

    @Test
    fun encryptedHandshakeAndDirectMessage() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val tBob = VirtualMeshTransport(peerIdBob)
        tAlice.linkTo(tBob)

        val crypto = CryptoProvider()
        val config = testMeshLinkConfig { requireEncryption = false }
        val alice = MeshLink(tAlice, config, coroutineContext, crypto = crypto)
        val bob = MeshLink(tBob, config, coroutineContext, crypto = crypto)
        alice.start(); bob.start()
        advanceUntilIdle()

        // Discover peers — keys are exchanged via Noise XX handshake
        tAlice.simulateDiscovery(peerIdBob)
        tBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        val receiveJob = launch {
            val msg = bob.messages.first()
            assertEquals("encrypted hello", String(msg.payload))
        }

        val result = alice.send(peerIdBob, "encrypted hello".encodeToByteArray())
        assertTrue(result.isSuccess, "send should succeed")
        advanceUntilIdle()
        receiveJob.join()

        alice.stop(); bob.stop()
    }

    // ── Handshake key registration via completed Noise XX ─────────

    @Test
    fun handshakeRegistersKeyWithoutAdvertisement() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val tBob = VirtualMeshTransport(peerIdBob)
        tAlice.linkTo(tBob)

        val config = testMeshLinkConfig { requireEncryption = false }
        val alice = MeshLink(tAlice, config, coroutineContext, crypto = CryptoProvider())
        val bob = MeshLink(tBob, config, coroutineContext, crypto = CryptoProvider())
        alice.start(); bob.start()
        advanceUntilIdle()

        // Discover WITHOUT public key in advertisement (empty payload)
        tAlice.simulateDiscovery(peerIdBob)
        tBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        // The handshake should have run and registered the peer keys
        // Allow time for the 3-message Noise XX exchange
        advanceUntilIdle()

        // After handshake completes, peer public key should be registered
        val bobKeyOnAlice = alice.peerPublicKey(peerIdBob.toHex())
        val aliceKeyOnBob = bob.peerPublicKey(peerIdAlice.toHex())
        assertNotNull(bobKeyOnAlice, "Alice should know Bob's key after handshake")
        assertNotNull(aliceKeyOnBob, "Bob should know Alice's key after handshake")

        alice.stop(); bob.stop()
    }

    // ── Delivery confirmation round-trip ──────────────────────────

    @Test
    fun deliveryConfirmationRoundTrip() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val tBob = VirtualMeshTransport(peerIdBob)
        tAlice.linkTo(tBob)

        val config = testMeshLinkConfig { requireEncryption = false }
        val alice = MeshLink(tAlice, config, coroutineContext)
        val bob = MeshLink(tBob, config, coroutineContext)
        alice.start(); bob.start()
        advanceUntilIdle()

        tAlice.simulateDiscovery(peerIdBob)
        tBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        // Bob must consume the message for the delivery ACK to fire
        val bobJob = launch { bob.messages.first() }

        var confirmedId: kotlin.uuid.Uuid? = null
        val confirmJob = launch {
            confirmedId = alice.deliveryConfirmations.first()
        }
        advanceUntilIdle()

        val msgId = alice.send(peerIdBob, "ack me".encodeToByteArray()).getOrThrow()
        advanceUntilIdle()
        bobJob.join()
        confirmJob.join()

        assertEquals(msgId, confirmedId, "delivery confirmation should match sent message ID")

        alice.stop(); bob.stop()
    }

    // ── Broadcast to multiple neighbors ───────────────────────────

    @Test
    fun broadcastReachesAllNeighbors() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val tBob = VirtualMeshTransport(peerIdBob)
        val tCharlie = VirtualMeshTransport(peerIdCharlie)
        tAlice.linkTo(tBob)
        tAlice.linkTo(tCharlie)

        val config = testMeshLinkConfig { requireEncryption = false }
        val alice = MeshLink(tAlice, config, coroutineContext)
        val bob = MeshLink(tBob, config, coroutineContext)
        val charlie = MeshLink(tCharlie, config, coroutineContext)
        alice.start(); bob.start(); charlie.start()
        advanceUntilIdle()

        // Alice discovers both; they discover Alice
        tAlice.simulateDiscovery(peerIdBob)
        tAlice.simulateDiscovery(peerIdCharlie)
        tBob.simulateDiscovery(peerIdAlice)
        tCharlie.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        val bobMessages = mutableListOf<Message>()
        val charlieMessages = mutableListOf<Message>()
        val bobJob = launch { bob.messages.first().let { bobMessages.add(it) } }
        val charlieJob = launch { charlie.messages.first().let { charlieMessages.add(it) } }
        advanceUntilIdle()

        alice.broadcast("hello everyone".encodeToByteArray(), maxHops = 3u.toUByte())
        advanceUntilIdle()
        bobJob.join()
        charlieJob.join()

        assertEquals(1, bobMessages.size)
        assertEquals(1, charlieMessages.size)
        assertEquals("hello everyone", String(bobMessages[0].payload))
        assertEquals("hello everyone", String(charlieMessages[0].payload))

        alice.stop(); bob.stop(); charlie.stop()
    }

    // ── Three-hop routed message ──────────────────────────────────

    @Test
    fun threeHopRoutedMessage() = runTest {
        // A ↔ B ↔ C (linear chain): A sends to C via B
        val idA = peerId(1); val idB = peerId(2); val idC = peerId(3)
        val hexA = idA.toHex(); val hexB = idB.toHex(); val hexC = idC.toHex()
        val tA = VirtualMeshTransport(idA)
        val tB = VirtualMeshTransport(idB)
        val tC = VirtualMeshTransport(idC)
        tA.linkTo(tB)
        tB.linkTo(tC)

        val config = testMeshLinkConfig { requireEncryption = false; gossipIntervalMillis = 100L }
        val a = MeshLink(tA, config, coroutineContext)
        val b = MeshLink(tB, config, coroutineContext)
        val c = MeshLink(tC, config, coroutineContext)

        // Set up direct neighbor routes
        a.addRoute(hexB, hexB, 1.0, 1u)
        b.addRoute(hexA, hexA, 1.0, 1u)
        b.addRoute(hexC, hexC, 1.0, 1u)
        c.addRoute(hexB, hexB, 1.0, 1u)

        a.start(); b.start(); c.start()
        testScheduler.advanceTimeBy(1L)

        // Neighbor discovery (only adjacent peers see each other)
        tA.simulateDiscovery(idB); tB.simulateDiscovery(idA)
        tB.simulateDiscovery(idC); tC.simulateDiscovery(idB)
        testScheduler.advanceTimeBy(1L)

        // Allow gossip to propagate routes (A learns about C via B)
        testScheduler.advanceTimeBy(500L)

        // A should have learned routes via gossip (avgRouteCost > 0)
        val health = a.meshHealth()
        assertTrue(health.avgRouteCost > 0.0, "A should have routes after gossip")

        // Send message from A to C — routed via B
        val result = a.send(idC, "routed hello".encodeToByteArray())
        testScheduler.advanceTimeBy(10L)

        assertTrue(result.isSuccess, "send to C should succeed: $result")

        // Verify B relayed the message toward C
        val relayed = tB.sentData.filter { (peer, data) ->
            peer == hexC && data.isNotEmpty() && data[0] == WireCodec.TYPE_ROUTED_MESSAGE
        }
        assertTrue(relayed.isNotEmpty(), "B should relay the routed message to C")

        a.stop(); b.stop(); c.stop()
        testScheduler.advanceTimeBy(1L)
    }

    // ── Peer discovery and loss lifecycle ──────────────────────────

    @Test
    fun peerFoundAndLostLifecycle() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val tBob = VirtualMeshTransport(peerIdBob)
        tAlice.linkTo(tBob)

        val config = testMeshLinkConfig { requireEncryption = false }
        val alice = MeshLink(tAlice, config, coroutineContext)
        alice.start()
        advanceUntilIdle()

        val events = mutableListOf<PeerEvent>()
        val collector = launch { alice.peers.take(2).toList().let { events.addAll(it) } }

        // Discover then lose
        tAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()
        tAlice.simulatePeerLost(peerIdBob)
        advanceUntilIdle()
        collector.join()

        assertEquals(2, events.size)
        assertIs<PeerEvent.Found>(events[0])
        assertIs<PeerEvent.Lost>(events[1])

        alice.stop()
    }

    // ── Multiple sequential messages ──────────────────────────────

    @Test
    fun multipleSequentialMessages() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val tBob = VirtualMeshTransport(peerIdBob)
        tAlice.linkTo(tBob)

        val config = testMeshLinkConfig { requireEncryption = false }
        val alice = MeshLink(tAlice, config, coroutineContext)
        val bob = MeshLink(tBob, config, coroutineContext)
        alice.start(); bob.start()
        advanceUntilIdle()

        tAlice.simulateDiscovery(peerIdBob)
        tBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        val count = 5
        val received = mutableListOf<Message>()
        val collector = launch {
            bob.messages.take(count).toList().let { received.addAll(it) }
        }
        advanceUntilIdle()

        for (i in 0 until count) {
            alice.send(peerIdBob, "msg-$i".encodeToByteArray())
        }
        advanceUntilIdle()
        collector.join()

        assertEquals(count, received.size)
        for (i in 0 until count) {
            assertEquals("msg-$i", String(received[i].payload))
        }

        alice.stop(); bob.stop()
    }

    // ── Bidirectional messaging ───────────────────────────────────

    @Test
    fun bidirectionalMessaging() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val tBob = VirtualMeshTransport(peerIdBob)
        tAlice.linkTo(tBob)

        val config = testMeshLinkConfig { requireEncryption = false }
        val alice = MeshLink(tAlice, config, coroutineContext)
        val bob = MeshLink(tBob, config, coroutineContext)
        alice.start(); bob.start()
        advanceUntilIdle()

        tAlice.simulateDiscovery(peerIdBob)
        tBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        val aliceReceived = mutableListOf<Message>()
        val bobReceived = mutableListOf<Message>()
        val aliceCollector = launch { alice.messages.first().let { aliceReceived.add(it) } }
        val bobCollector = launch { bob.messages.first().let { bobReceived.add(it) } }
        advanceUntilIdle()

        alice.send(peerIdBob, "from alice".encodeToByteArray())
        bob.send(peerIdAlice, "from bob".encodeToByteArray())
        advanceUntilIdle()

        aliceCollector.join()
        bobCollector.join()

        assertEquals("from bob", String(aliceReceived[0].payload))
        assertEquals("from alice", String(bobReceived[0].payload))

        alice.stop(); bob.stop()
    }

    // ── Large message chunking and reassembly ─────────────────────

    @Test
    fun largeMessageChunkingAndReassembly() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val tBob = VirtualMeshTransport(peerIdBob)
        tAlice.linkTo(tBob)

        val config = testMeshLinkConfig { requireEncryption = false; mtu = 185 }
        val alice = MeshLink(tAlice, config, coroutineContext)
        val bob = MeshLink(tBob, config, coroutineContext)
        alice.start(); bob.start()
        advanceUntilIdle()

        tAlice.simulateDiscovery(peerIdBob)
        tBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        // 2KB payload — requires multiple chunks at 185 MTU
        val largePayload = ByteArray(2_000) { (it % 256).toByte() }

        val receiveJob = launch {
            val msg = bob.messages.first()
            assertContentEquals(largePayload, msg.payload)
        }

        val result = alice.send(peerIdBob, largePayload)
        assertTrue(result.isSuccess, "large send should succeed")
        advanceUntilIdle()
        receiveJob.join()

        alice.stop(); bob.stop()
    }

    // ── Stop clears mesh health ───────────────────────────────────

    @Test
    fun stopResetsMeshHealth() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val tBob = VirtualMeshTransport(peerIdBob)
        tAlice.linkTo(tBob)

        val config = testMeshLinkConfig { requireEncryption = false }
        val alice = MeshLink(tAlice, config, coroutineContext)
        alice.start()
        advanceUntilIdle()

        tAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        val healthBefore = alice.meshHealth()
        assertEquals(1, healthBefore.connectedPeers)

        alice.stop()

        val healthAfter = alice.meshHealth()
        assertEquals(0, healthAfter.connectedPeers)
        assertEquals(0, healthAfter.reachablePeers)
    }

    // ── Pause queues messages, resume delivers them ───────────────

    @Test
    fun pauseAndResumeQueuesMessages() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val tBob = VirtualMeshTransport(peerIdBob)
        tAlice.linkTo(tBob)

        val config = testMeshLinkConfig { requireEncryption = false }
        val alice = MeshLink(tAlice, config, coroutineContext)
        val bob = MeshLink(tBob, config, coroutineContext)
        alice.start(); bob.start()
        advanceUntilIdle()

        tAlice.simulateDiscovery(peerIdBob)
        tBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        // Pause Alice
        alice.pause()
        advanceUntilIdle()

        // Send while paused — should be queued, not delivered
        alice.send(peerIdBob, "queued msg".encodeToByteArray())
        advanceUntilIdle()

        // No data should have been sent on the transport
        val sentDuringPause = tAlice.sentData.size
        assertEquals(0, sentDuringPause, "no data should be sent while paused")

        // Resume — queued message should flush
        val receiveJob = launch { bob.messages.first() }
        alice.resume()
        advanceUntilIdle()
        receiveJob.join()

        alice.stop(); bob.stop()
    }

    // ── Self-send (loopback) ──────────────────────────────────────

    @Test
    fun selfSendLoopback() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val config = testMeshLinkConfig { requireEncryption = false }
        val alice = MeshLink(tAlice, config, coroutineContext)
        alice.start()
        advanceUntilIdle()

        val receiveJob = launch {
            val msg = alice.messages.first()
            assertContentEquals(peerIdAlice, msg.senderId)
            assertEquals("self hello", String(msg.payload))
        }

        alice.send(peerIdAlice, "self hello".encodeToByteArray())
        advanceUntilIdle()
        receiveJob.join()

        alice.stop()
    }

    // ── Handshake collision (both peers initiate simultaneously) ──

    @Test
    fun handshakeCollisionResolvesGracefully() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val tBob = VirtualMeshTransport(peerIdBob)
        tAlice.linkTo(tBob)

        val config = testMeshLinkConfig { requireEncryption = false }
        val alice = MeshLink(tAlice, config, coroutineContext, crypto = CryptoProvider())
        val bob = MeshLink(tBob, config, coroutineContext, crypto = CryptoProvider())
        alice.start(); bob.start()
        advanceUntilIdle()

        // Both discover each other simultaneously with empty advertisement
        // (no public key in adv), forcing handshake for key exchange.
        // Both may try to initiate, causing a collision.
        tAlice.simulateDiscovery(peerIdBob)
        tBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        // Give handshake messages time to complete the 3-round exchange
        advanceUntilIdle()

        // Both peers should have each other's keys registered after handshake
        val bobKeyOnAlice = alice.peerPublicKey(peerIdBob.toHex())
        val aliceKeyOnBob = bob.peerPublicKey(peerIdAlice.toHex())
        assertNotNull(bobKeyOnAlice, "Alice should know Bob's key after collision-resolved handshake")
        assertNotNull(aliceKeyOnBob, "Bob should know Alice's key after collision-resolved handshake")

        alice.stop(); bob.stop()
    }

    // ── Diagnostic events emitted during handshake ────────────────

    @Test
    fun handshakeDiagnosticEventsEmitted() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val tBob = VirtualMeshTransport(peerIdBob)
        tAlice.linkTo(tBob)

        val config = testMeshLinkConfig { requireEncryption = false }
        val alice = MeshLink(tAlice, config, coroutineContext, crypto = CryptoProvider())
        val bob = MeshLink(tBob, config, coroutineContext, crypto = CryptoProvider())
        alice.start(); bob.start()
        advanceUntilIdle()

        val diagnostics = mutableListOf<DiagnosticEvent>()
        val diagJob = launch {
            alice.diagnosticEvents.collect { diagnostics.add(it) }
        }
        advanceUntilIdle()

        // Discover with empty advertisement to trigger handshake
        tAlice.simulateDiscovery(peerIdBob)
        tBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        diagJob.cancel()

        val handshakeEvents = diagnostics.filter { it.code == DiagnosticCode.HANDSHAKE_EVENT }
        assertTrue(
            handshakeEvents.isNotEmpty(),
            "should emit HANDSHAKE_EVENT diagnostics during handshake"
        )

        // Should see at least an initiation and a completion
        assertTrue(
            handshakeEvents.any { it.payload?.contains("initiating") == true },
            "should log handshake initiation"
        )
        assertTrue(
            handshakeEvents.any { it.payload?.contains("handshake complete") == true },
            "should log handshake completion"
        )

        alice.stop(); bob.stop()
    }

    // ── Five-node full mesh discovery ──────────────────────────────

    @Test
    fun fiveNodeFullMeshDiscovery() = runTest {
        val count = 5
        val ids = (0 until count).map { peerId(it + 1) }
        val transports = ids.map { VirtualMeshTransport(it) }

        // Full mesh link
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

        // Every node should see count-1 connected peers
        for (i in 0 until count) {
            val health = nodes[i].meshHealth()
            assertEquals(count - 1, health.connectedPeers, "node $i should see ${count - 1} peers")
        }

        nodes.forEach { it.stop() }
    }

    // ── Start, stop, restart cycle ────────────────────────────────

    @Test
    fun startStopRestartCycle() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val tBob = VirtualMeshTransport(peerIdBob)
        tAlice.linkTo(tBob)

        val config = testMeshLinkConfig { requireEncryption = false }
        val alice = MeshLink(tAlice, config, coroutineContext)
        val bob = MeshLink(tBob, config, coroutineContext)

        // Cycle 1
        alice.start(); bob.start()
        advanceUntilIdle()
        tAlice.simulateDiscovery(peerIdBob); tBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()
        assertEquals(1, alice.meshHealth().connectedPeers)
        alice.stop(); bob.stop()

        // After stop, health should be cleared
        assertEquals(0, alice.meshHealth().connectedPeers)

        // Cycle 2 — mesh should work again
        alice.start(); bob.start()
        advanceUntilIdle()
        tAlice.simulateDiscovery(peerIdBob); tBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()
        assertEquals(1, alice.meshHealth().connectedPeers)

        val receiveJob = launch { bob.messages.first() }
        alice.send(peerIdBob, "after restart".encodeToByteArray())
        advanceUntilIdle()
        receiveJob.join()

        alice.stop(); bob.stop()
    }

    // ── Transport failure emits diagnostic ────────────────────────

    @Test
    fun transportFailureEmitsDiagnostic() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val tBob = VirtualMeshTransport(peerIdBob)
        tAlice.linkTo(tBob)

        val config = testMeshLinkConfig { requireEncryption = false }
        val alice = MeshLink(tAlice, config, coroutineContext)
        val bob = MeshLink(tBob, config, coroutineContext)
        alice.start(); bob.start()
        advanceUntilIdle()

        tAlice.simulateDiscovery(peerIdBob); tBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        val diagnostics = mutableListOf<DiagnosticEvent>()
        val diagJob = launch { alice.diagnosticEvents.collect { diagnostics.add(it) } }
        advanceUntilIdle()

        // Trigger transport failure
        tAlice.sendFailure = true
        alice.send(peerIdBob, "will fail".encodeToByteArray())
        advanceUntilIdle()

        diagJob.cancel()
        tAlice.sendFailure = false

        val sendFailed = diagnostics.filter { it.code == DiagnosticCode.SEND_FAILED }
        assertTrue(sendFailed.isNotEmpty(), "should emit SEND_FAILED diagnostic on transport error")

        alice.stop(); bob.stop()
    }

    // ── Send rejection: message exceeds max size ──────────────────

    @Test
    fun sendExceedingMaxMessageSizeIsRejected() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val tBob = VirtualMeshTransport(peerIdBob)
        tAlice.linkTo(tBob)

        // bufferCapacity = 500, so a 600-byte payload exceeds the buffer
        val config = testMeshLinkConfig { requireEncryption = false; bufferCapacity = 500; maxMessageSize = 500 }
        val alice = MeshLink(tAlice, config, coroutineContext)
        alice.start()
        advanceUntilIdle()

        tAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        val result = alice.send(peerIdBob, ByteArray(600))
        assertTrue(result.isFailure, "send exceeding buffer capacity should fail")

        alice.stop()
    }

    // ── Send rejection: unreachable peer ──────────────────────────

    @Test
    fun sendToUnreachablePeerFails() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val config = testMeshLinkConfig { requireEncryption = false }
        val alice = MeshLink(tAlice, config, coroutineContext)
        alice.start()
        advanceUntilIdle()

        // No discovery — Bob is unknown
        val unknownPeer = ByteArray(16) { 0xFF.toByte() }
        val result = alice.send(unknownPeer, "hello".encodeToByteArray())
        assertTrue(result.isFailure, "send to undiscovered peer should fail")

        alice.stop()
    }

    // ── Send rejection: before start ──────────────────────────────

    @Test
    fun sendBeforeStartThrows() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(tAlice, testMeshLinkConfig { requireEncryption = false }, coroutineContext)

        assertFailsWith<IllegalStateException> {
            alice.send(peerIdBob, "hello".encodeToByteArray())
        }
    }

    // ── Transfer failure: delivery timeout on stop ────────────────

    @Test
    fun stopEmitsDeliveryTimeoutForInFlightTransfers() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val tBob = VirtualMeshTransport(peerIdBob)
        tAlice.linkTo(tBob)

        val config = testMeshLinkConfig { requireEncryption = false }
        val alice = MeshLink(tAlice, config, coroutineContext)
        alice.start()
        advanceUntilIdle()

        tAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        val failures = mutableListOf<TransferFailure>()
        val failJob = launch { alice.transferFailures.collect { failures.add(it) } }
        advanceUntilIdle()

        // Send — Bob is not running so won't send delivery ACK back
        alice.send(peerIdBob, "in flight".encodeToByteArray())
        advanceUntilIdle()

        // Stop before delivery completes — should emit FAILED_DELIVERY_TIMEOUT
        alice.stop()
        failJob.cancel()

        val timeouts = failures.filter { it.reason == DeliveryOutcome.FAILED_DELIVERY_TIMEOUT }
        assertTrue(timeouts.isNotEmpty(), "should emit FAILED_DELIVERY_TIMEOUT on stop with in-flight transfer")
    }

    // ── Transfer progress events ──────────────────────────────────

    @Test
    fun chunkedTransferEmitsProgressUpdates() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val tBob = VirtualMeshTransport(peerIdBob)
        tAlice.linkTo(tBob)

        val config = testMeshLinkConfig { requireEncryption = false; mtu = 185 }
        val alice = MeshLink(tAlice, config, coroutineContext)
        val bob = MeshLink(tBob, config, coroutineContext)
        alice.start(); bob.start()
        advanceUntilIdle()

        tAlice.simulateDiscovery(peerIdBob)
        tBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        val progress = mutableListOf<TransferProgress>()
        val progressJob = launch { alice.transferProgress.collect { progress.add(it) } }
        advanceUntilIdle()

        val bobJob = launch { bob.messages.first() }

        val msgId = alice.send(peerIdBob, ByteArray(2000)).getOrThrow()
        advanceUntilIdle()
        bobJob.join()
        advanceUntilIdle()

        progressJob.cancel()

        assertTrue(progress.isNotEmpty(), "should emit at least one progress event")
        val last = progress.last()
        assertEquals(msgId, last.messageId)
        assertEquals(1f, last.fraction, "final progress should be 100%")

        alice.stop(); bob.stop()
    }

    // ── Identity rotation ─────────────────────────────────────────

    @Test
    fun rotateIdentityGeneratesNewKeyPair() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(
            tAlice,
            testMeshLinkConfig { requireEncryption = false },
            coroutineContext,
            crypto = CryptoProvider()
        )
        alice.start()
        advanceUntilIdle()

        val originalKey = alice.localPublicKey!!.copyOf()
        val result = alice.rotateIdentity()
        assertTrue(result.isSuccess, "rotation should succeed")

        val newKey = alice.localPublicKey!!
        assertNotEquals(originalKey.toList(), newKey.toList(), "key should change after rotation")

        alice.stop()
    }

    @Test
    fun rotateIdentityWithoutCryptoFails() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(tAlice, testMeshLinkConfig { requireEncryption = false }, coroutineContext)
        alice.start()
        advanceUntilIdle()

        val result = alice.rotateIdentity()
        assertTrue(result.isFailure, "rotation without crypto should fail")

        alice.stop()
    }

    // ── Power mode management ─────────────────────────────────────

    @Test
    fun lowBatteryTriggersPowerSaver() = runTest {
        var now = 0L
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(tAlice, testMeshLinkConfig { requireEncryption = false }, coroutineContext, clock = { now })
        alice.start()
        advanceUntilIdle()

        // Request downgrade — hysteresis delays it
        alice.updateBattery(10, isCharging = false)
        // Advance past 30s hysteresis and re-evaluate
        now += 30_001
        alice.updateBattery(10, isCharging = false)
        assertEquals("POWER_SAVER", alice.meshHealth().powerMode)

        alice.stop()
    }

    @Test
    fun chargingActivatesPerformanceMode() = runTest {
        var now = 0L
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(tAlice, testMeshLinkConfig { requireEncryption = false }, coroutineContext, clock = { now })
        alice.start()
        advanceUntilIdle()

        // First drop to POWER_SAVER
        alice.updateBattery(10, isCharging = false)
        now += 30_001
        alice.updateBattery(10, isCharging = false)
        assertEquals("POWER_SAVER", alice.meshHealth().powerMode)

        // Now plug in — upward transition is immediate (no hysteresis)
        alice.updateBattery(10, isCharging = true)
        assertEquals("PERFORMANCE", alice.meshHealth().powerMode)

        alice.stop()
    }

    @Test
    fun mediumBatteryActivatesBalancedMode() = runTest {
        var now = 0L
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(tAlice, testMeshLinkConfig { requireEncryption = false }, coroutineContext, clock = { now })
        alice.start()
        advanceUntilIdle()

        alice.updateBattery(50, isCharging = false)
        now += 30_001
        alice.updateBattery(50, isCharging = false)
        assertEquals("BALANCED", alice.meshHealth().powerMode)

        alice.stop()
    }

    // ── Diagnostics: malformed data ───────────────────────────────

    @Test
    fun malformedDataEmitsDiagnostic() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(tAlice, testMeshLinkConfig { requireEncryption = false }, coroutineContext)
        alice.start()
        advanceUntilIdle()

        tAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        val diagnostics = mutableListOf<DiagnosticEvent>()
        val diagJob = launch { alice.diagnosticEvents.collect { diagnostics.add(it) } }
        advanceUntilIdle()

        // Inject truncated routed message (valid type byte, too short to parse)
        val garbled = byteArrayOf(WireCodec.TYPE_ROUTED_MESSAGE) + ByteArray(5)
        tAlice.receiveData(peerIdBob, garbled)
        advanceUntilIdle()

        diagJob.cancel()

        val malformed = diagnostics.filter { it.code == DiagnosticCode.MALFORMED_DATA }
        assertTrue(malformed.isNotEmpty(), "should emit MALFORMED_DATA diagnostic for garbled input")

        alice.stop()
    }

    // ── Diagnostics: hop limit exceeded ───────────────────────────

    @Test
    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    fun hopLimitExceededEmitsDiagnostic() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(tAlice, testMeshLinkConfig { requireEncryption = false }, coroutineContext)
        alice.start()
        advanceUntilIdle()

        tAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        val diagnostics = mutableListOf<DiagnosticEvent>()
        val diagJob = launch { alice.diagnosticEvents.collect { diagnostics.add(it) } }
        advanceUntilIdle()

        // Inject a routed message with hopLimit=0 (already exhausted)
        val sender = ByteArray(16) { 0xCC.toByte() }
        val target = ByteArray(16) { 0xDD.toByte() }
        val msg = WireCodec.encodeRoutedMessage(
            messageId = kotlin.uuid.Uuid.random().toByteArray(),
            origin = sender,
            destination = target,
            hopLimit = 0u,
            visitedList = listOf(sender),
            payload = "expired".encodeToByteArray(),
            replayCounter = 1u,
        )
        tAlice.receiveData(peerIdBob, msg)
        advanceUntilIdle()

        diagJob.cancel()

        val hopDiags = diagnostics.filter { it.code == DiagnosticCode.HOP_LIMIT_EXCEEDED }
        assertTrue(hopDiags.isNotEmpty(), "should emit HOP_LIMIT_EXCEEDED diagnostic")

        alice.stop()
    }

    // ── Peer eviction after missed sweeps ─────────────────────────

    @Test
    fun peerEvictedAfterConsecutiveSweepMisses() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(tAlice, testMeshLinkConfig { requireEncryption = false }, coroutineContext)
        alice.start()
        advanceUntilIdle()

        tAlice.simulateDiscovery(peerIdBob)
        advanceUntilIdle()
        assertEquals(1, alice.meshHealth().connectedPeers)

        // First sweep miss — still tracked
        alice.sweep(emptySet())
        assertEquals(1, alice.meshHealth().connectedPeers, "first miss: still tracked")

        // Second sweep miss — evicted
        val evicted = alice.sweep(emptySet())
        assertEquals(0, alice.meshHealth().connectedPeers, "second miss: evicted")
        assertEquals(1, evicted.size, "should report 1 evicted peer")

        alice.stop()
    }

    // ── Memory pressure shedding ──────────────────────────────────

    @Test
    fun shedMemoryPressureReturnsActions() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val alice = MeshLink(tAlice, testMeshLinkConfig { requireEncryption = false }, coroutineContext)
        alice.start()
        advanceUntilIdle()

        // With no buffer pressure, shedding should return empty
        val actions = alice.shedMemoryPressure()
        assertTrue(actions.isEmpty(), "no pressure = no actions: $actions")

        alice.stop()
    }

    // ── Encrypted multi-hop routed message ────────────────────────

    @Test
    fun encryptedThreeHopRoutedMessage() = runTest {
        // A ↔ B ↔ C (linear chain): A sends encrypted message to C via relay B
        val idA = peerId(1); val idB = peerId(2); val idC = peerId(3)
        val hexA = idA.toHex(); val hexB = idB.toHex(); val hexC = idC.toHex()
        val tA = VirtualMeshTransport(idA)
        val tB = VirtualMeshTransport(idB)
        val tC = VirtualMeshTransport(idC)
        tA.linkTo(tB)
        tB.linkTo(tC)

        val config = testMeshLinkConfig { requireEncryption = false; gossipIntervalMillis = 100L }
        val a = MeshLink(tA, config, coroutineContext, crypto = CryptoProvider())
        val b = MeshLink(tB, config, coroutineContext, crypto = CryptoProvider())
        val c = MeshLink(tC, config, coroutineContext, crypto = CryptoProvider())

        // Direct neighbor routes
        a.addRoute(hexB, hexB, 1.0, 1u)
        b.addRoute(hexA, hexA, 1.0, 1u)
        b.addRoute(hexC, hexC, 1.0, 1u)
        c.addRoute(hexB, hexB, 1.0, 1u)

        a.start(); b.start(); c.start()
        testScheduler.advanceTimeBy(1L)

        // Neighbor discovery triggers Noise XX handshake between adjacent peers
        tA.simulateDiscovery(idB); tB.simulateDiscovery(idA)
        tB.simulateDiscovery(idC); tC.simulateDiscovery(idB)
        testScheduler.advanceTimeBy(50L)

        // Gossip propagates routes (A learns about C via B)
        testScheduler.advanceTimeBy(500L)

        val health = a.meshHealth()
        assertTrue(health.avgRouteCost > 0.0, "A should have routes after gossip")

        // Send encrypted message from A to C — routed via B
        val result = a.send(idC, "encrypted routed hello".encodeToByteArray())
        testScheduler.advanceTimeBy(50L)
        assertTrue(result.isSuccess, "send to C should succeed: $result")

        // Verify B relayed an encrypted routed message toward C
        val relayed = tB.sentData.filter { (peer, data) ->
            peer == hexC && data.isNotEmpty() && data[0] == WireCodec.TYPE_ROUTED_MESSAGE
        }
        assertTrue(relayed.isNotEmpty(), "B should relay the routed message to C")

        a.stop(); b.stop(); c.stop()
        testScheduler.advanceTimeBy(1L)
    }

    // ── Broadcast with multi-hop relay and TTL decrement ──────────

    @Test
    fun broadcastRelaysAcrossHopsWithTtlDecrement() = runTest {
        // A ↔ B ↔ C (linear chain): A broadcasts, B relays to C
        val idA = peerId(1); val idB = peerId(2); val idC = peerId(3)
        val hexC = idC.toHex()
        val tA = VirtualMeshTransport(idA)
        val tB = VirtualMeshTransport(idB)
        val tC = VirtualMeshTransport(idC)
        tA.linkTo(tB)
        tB.linkTo(tC)
        // Note: A is NOT linked to C — C can only receive via relay through B

        val config = testMeshLinkConfig { requireEncryption = false; gossipIntervalMillis = 100L }
        val a = MeshLink(tA, config, coroutineContext)
        val b = MeshLink(tB, config, coroutineContext)
        val c = MeshLink(tC, config, coroutineContext)

        a.start(); b.start(); c.start()
        testScheduler.advanceTimeBy(1L)

        // Discovery: only adjacent peers see each other
        tA.simulateDiscovery(idB); tB.simulateDiscovery(idA)
        tB.simulateDiscovery(idC); tC.simulateDiscovery(idB)
        testScheduler.advanceTimeBy(1L)

        // A broadcasts with maxHops=3 — should reach B directly and C via relay
        a.broadcast("multi-hop broadcast".encodeToByteArray(), maxHops = 3u.toUByte())
        testScheduler.advanceTimeBy(50L)

        // Verify B relayed the broadcast toward C
        val relayedToC = tB.sentData.filter { (peer, data) ->
            peer == hexC && data.isNotEmpty() && data[0] == WireCodec.TYPE_BROADCAST
        }
        assertTrue(relayedToC.isNotEmpty(), "B should relay broadcast to C")

        // Verify the relayed broadcast has decremented remaining hops
        val relayedFrame = relayedToC.first().second
        val decoded = WireCodec.decodeBroadcast(relayedFrame)
        assertTrue(
            decoded.remainingHops < 3u,
            "remaining hops should be decremented from 3, got ${decoded.remainingHops}"
        )

        a.stop(); b.stop(); c.stop()
        testScheduler.advanceTimeBy(1L)
    }

    // ── Gossip convergence timing ─────────────────────────────────

    @Test
    fun gossipConvergesRoutingTableWithinOneInterval() = runTest {
        // A ↔ B ↔ C: after one gossip interval, A should learn route to C via B
        val idA = peerId(1); val idB = peerId(2); val idC = peerId(3)
        val hexA = idA.toHex(); val hexB = idB.toHex(); val hexC = idC.toHex()
        val tA = VirtualMeshTransport(idA)
        val tB = VirtualMeshTransport(idB)
        val tC = VirtualMeshTransport(idC)
        tA.linkTo(tB)
        tB.linkTo(tC)

        val gossipInterval = 200L
        val config = testMeshLinkConfig {
            requireEncryption = false
            gossipIntervalMillis = gossipInterval
        }
        val a = MeshLink(tA, config, coroutineContext)
        val b = MeshLink(tB, config, coroutineContext)
        val c = MeshLink(tC, config, coroutineContext)

        // Seed direct neighbor routes
        a.addRoute(hexB, hexB, 1.0, 1u)
        b.addRoute(hexA, hexA, 1.0, 1u)
        b.addRoute(hexC, hexC, 1.0, 1u)
        c.addRoute(hexB, hexB, 1.0, 1u)

        a.start(); b.start(); c.start()
        testScheduler.advanceTimeBy(1L)

        tA.simulateDiscovery(idB); tB.simulateDiscovery(idA)
        tB.simulateDiscovery(idC); tC.simulateDiscovery(idB)
        testScheduler.advanceTimeBy(1L)

        // Before gossip: A only knows direct neighbor B
        val healthBefore = a.meshHealth()

        // Advance exactly 2 gossip intervals — routes should propagate A→B→C
        testScheduler.advanceTimeBy(gossipInterval * 2 + 50L)

        val healthAfter = a.meshHealth()

        // A should have learned more routes after gossip convergence
        assertTrue(
            healthAfter.reachablePeers >= healthBefore.reachablePeers,
            "A should discover more peers after gossip: before=${healthBefore.reachablePeers}, after=${healthAfter.reachablePeers}"
        )

        // Verify A can now route a message to C
        val result = a.send(idC, "post-gossip".encodeToByteArray())
        testScheduler.advanceTimeBy(10L)
        assertTrue(result.isSuccess, "A should be able to send to C after gossip convergence: $result")

        a.stop(); b.stop(); c.stop()
        testScheduler.advanceTimeBy(1L)
    }

    // ── Concurrent transfers ──────────────────────────────────────

    @Test
    fun concurrentTransfersDeliverAllMessages() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val tBob = VirtualMeshTransport(peerIdBob)
        tAlice.linkTo(tBob)

        val config = testMeshLinkConfig { requireEncryption = false }
        val alice = MeshLink(tAlice, config, coroutineContext)
        val bob = MeshLink(tBob, config, coroutineContext)
        alice.start(); bob.start()
        advanceUntilIdle()

        tAlice.simulateDiscovery(peerIdBob)
        tBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        val messageCount = 5
        val receivedMessages = mutableListOf<Message>()
        val receiveJob = launch {
            bob.messages.take(messageCount).toList().let { receivedMessages.addAll(it) }
        }

        // Send multiple messages concurrently
        val sendResults = (1..messageCount).map { i ->
            alice.send(peerIdBob, "message-$i".encodeToByteArray())
        }
        advanceUntilIdle()
        receiveJob.join()

        // All sends should succeed
        sendResults.forEachIndexed { i, result ->
            assertTrue(result.isSuccess, "send ${i + 1} should succeed: $result")
        }

        // All messages should be received
        assertEquals(messageCount, receivedMessages.size, "all $messageCount messages should arrive")
        val payloads = receivedMessages.map { String(it.payload) }.toSet()
        for (i in 1..messageCount) {
            assertTrue("message-$i" in payloads, "message-$i should be received")
        }

        alice.stop(); bob.stop()
    }
}
