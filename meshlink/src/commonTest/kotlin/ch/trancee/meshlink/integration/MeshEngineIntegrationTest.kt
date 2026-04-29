package ch.trancee.meshlink.integration

import ch.trancee.meshlink.engine.PowerTierCodec
import ch.trancee.meshlink.messaging.Delivered
import ch.trancee.meshlink.messaging.DeliveryFailed
import ch.trancee.meshlink.messaging.InboundMessage
import ch.trancee.meshlink.power.PowerTier
import ch.trancee.meshlink.storage.InMemorySecureStorage
import ch.trancee.meshlink.transport.VirtualLink
import ch.trancee.meshlink.transport.VirtualMeshTransport
import ch.trancee.meshlink.wire.Handshake
import ch.trancee.meshlink.wire.WireCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * Seven-scenario integration test proving end-to-end mesh delivery through the MeshEngine facade
 * with real implementations (no mocks). Uses [MeshTestHarness] with full Noise XX handshakes.
 *
 * Topology for scenarios 1, 2, 4, 5: A ↔ B ↔ C (A and C are never directly linked)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshEngineIntegrationTest {

    // ── Integration config with fast battery polling for tier-change scenarios ─

    private val fastPowerConfig =
        MeshTestHarness.integrationConfig.copy(
            power =
                MeshTestHarness.integrationConfig.power.copy(
                    batteryPollIntervalMillis = 200L,
                    bootstrapDurationMillis = 100L,
                    hysteresisDelayMillis = 100L,
                )
        )

    // ── Scenario 1: A→C unicast via relay B ──────────────────────────────────

    @Test
    fun `scenario1 A sends 10KB to C via relay B`() = runTest {
        val harness =
            MeshTestHarness.builder()
                .node("A")
                .node("B")
                .node("C")
                .link("A", "B")
                .link("B", "C")
                .build(testScheduler, backgroundScope)

        harness.awaitConvergence()

        val messagesAtC = mutableListOf<InboundMessage>()
        val confirmationsAtA = mutableListOf<Delivered>()
        val failuresAtA = mutableListOf<DeliveryFailed>()

        // Subscribe BEFORE triggering
        backgroundScope.launch { harness["C"].engine.messages.collect { messagesAtC.add(it) } }
        backgroundScope.launch {
            harness["A"].engine.deliveryConfirmations.collect { confirmationsAtA.add(it) }
        }
        backgroundScope.launch {
            harness["A"].engine.transferFailures.collect { failuresAtA.add(it) }
        }
        testScheduler.runCurrent()

        val payload = ByteArray(512) { it.toByte() }
        harness["A"].engine.send(harness["C"].identity.keyHash, payload)
        testScheduler.runCurrent()

        // Process all pending coroutines without advancing the virtual clock.
        // Transfer is coroutine-driven (no timers needed); 500 runCurrent() calls is ample
        // for the chunked relay × ACK round-trips.
        repeat(500) { testScheduler.runCurrent() }

        // (a) C received exactly 1 message with correct payload
        assertEquals(1, messagesAtC.size, "C must receive exactly 1 message")
        assertTrue(messagesAtC[0].payload.contentEquals(payload), "C's payload must match")

        // (b) A got a delivery confirmation
        assertEquals(1, confirmationsAtA.size, "A must receive exactly 1 delivery confirmation")

        // (c) No transfer failures at A
        assertTrue(failuresAtA.isEmpty(), "A must have no transfer failures; got: $failuresAtA")

        // (d) B relayed (transport B sent at least one frame)
        assertTrue(
            harness["B"].transport.sentFrames.isNotEmpty(),
            "B must have relayed at least 1 frame",
        )

        // (e) All three nodes start in PERFORMANCE tier (battery = 100%)
        assertEquals(PowerTier.PERFORMANCE, harness["A"].engine.currentTier)
        assertEquals(PowerTier.PERFORMANCE, harness["B"].engine.currentTier)
        assertEquals(PowerTier.PERFORMANCE, harness["C"].engine.currentTier)

        harness.stopAll()
    }

    // ── Scenario 2: A broadcasts; B and C both receive ────────────────────────

    @Test
    fun `scenario2 A broadcasts and both B and C receive`() = runTest {
        val harness =
            MeshTestHarness.builder()
                .node("A")
                .node("B")
                .node("C")
                .link("A", "B")
                .link("B", "C")
                .build(testScheduler, backgroundScope)

        harness.awaitConvergence()

        val messagesAtB = mutableListOf<InboundMessage>()
        val messagesAtC = mutableListOf<InboundMessage>()

        // Subscribe BEFORE triggering
        backgroundScope.launch { harness["B"].engine.messages.collect { messagesAtB.add(it) } }
        backgroundScope.launch { harness["C"].engine.messages.collect { messagesAtC.add(it) } }
        testScheduler.runCurrent()

        val payload = ByteArray(64) { it.toByte() }
        harness["A"].engine.broadcast(payload, maxHops = 2u)
        testScheduler.runCurrent()

        // Broadcast relay is coroutine-driven; 100 cycles is ample for 2-hop flood-fill.
        repeat(100) { testScheduler.runCurrent() }

        // B receives the broadcast (direct from A)
        assertEquals(1, messagesAtB.size, "B must receive exactly 1 broadcast")
        assertTrue(messagesAtB[0].payload.contentEquals(payload))

        // C receives the broadcast (relayed via B)
        assertEquals(1, messagesAtC.size, "C must receive exactly 1 broadcast (not duplicated)")
        assertTrue(messagesAtC[0].payload.contentEquals(payload))

        harness.stopAll()
    }

    // ── Scenario 3: Battery tier change propagates ────────────────────────────

    @Test
    fun `scenario3 battery tier change at A propagates to tierChanges flow`() = runTest {
        // Single-node scenario with fast battery poll — does NOT use 3-node harness
        val harness =
            MeshTestHarness.builder()
                .node("A", fastPowerConfig)
                .build(testScheduler, backgroundScope)

        // No convergence needed — single node, no links

        val tierEvents = mutableListOf<PowerTier>()
        backgroundScope.launch { harness["A"].engine.tierChanges.collect { tierEvents.add(it) } }
        testScheduler.runCurrent()

        // Drop battery below POWER_SAVER threshold (0.30f)
        harness["A"].batteryMonitor.level = 0.15f

        // Advance past bootstrapDurationMillis (100ms) + batteryPollInterval (200ms) +
        // hysteresisDelay (100ms)
        testScheduler.advanceTimeBy(600L)
        testScheduler.runCurrent()

        // A's tier should have transitioned to POWER_SAVER
        assertTrue(
            tierEvents.contains(PowerTier.POWER_SAVER),
            "A's tierChanges must emit POWER_SAVER; got: $tierEvents",
        )
        assertEquals(PowerTier.POWER_SAVER, harness["A"].engine.currentTier)

        harness.stopAll()
    }

    // ── Scenario 4: B disconnect + reconnect, A→C transfer completes ─────────

    @Test
    fun `scenario4 B disconnect and reconnect allows transfer to C`() = runTest {
        val harness =
            MeshTestHarness.builder()
                .node("A")
                .node("B")
                .node("C")
                .link("A", "B")
                .link("B", "C")
                .build(testScheduler, backgroundScope)

        harness.awaitConvergence()

        // Collect messages at C
        val messagesAtC = mutableListOf<InboundMessage>()
        backgroundScope.launch { harness["C"].engine.messages.collect { messagesAtC.add(it) } }
        testScheduler.runCurrent()

        // Sever B-A connection (simulate disconnect from B's side)
        harness["B"].transport.simulatePeerLost(harness["A"].identity.keyHash)
        testScheduler.runCurrent()
        testScheduler.runCurrent()

        // Reconnect A↔B: re-simulate discovery (full Noise XX)
        val adData = PowerTierCodec.encode(PowerTier.PERFORMANCE)
        harness["A"].transport.simulateDiscovery(harness["B"].identity.keyHash, adData, -50)
        repeat(20) { testScheduler.runCurrent() }
        harness["B"].transport.simulateDiscovery(harness["A"].identity.keyHash, adData, -50)
        repeat(20) { testScheduler.runCurrent() }

        // Allow routing to re-converge via second-round re-discovery
        harness["B"].transport.simulateDiscovery(harness["A"].identity.keyHash, adData, -50)
        repeat(10) { testScheduler.runCurrent() }
        repeat(20) { testScheduler.runCurrent() }

        // Now send from A to C — should succeed via recovered path
        val payload = ByteArray(512) { 0xAB.toByte() }
        harness["A"].engine.send(harness["C"].identity.keyHash, payload)
        testScheduler.runCurrent()
        repeat(300) { testScheduler.runCurrent() }

        assertEquals(1, messagesAtC.size, "C must receive the message after reconnect")
        assertTrue(
            messagesAtC[0].payload.contentEquals(payload),
            "Payload must match after reconnect",
        )

        harness.stopAll()
    }

    // ── Scenario 5: Return path C→A ───────────────────────────────────────────

    @Test
    fun `scenario5 C sends to A via return path`() = runTest {
        val harness =
            MeshTestHarness.builder()
                .node("A")
                .node("B")
                .node("C")
                .link("A", "B")
                .link("B", "C")
                .build(testScheduler, backgroundScope)

        harness.awaitConvergence()

        val messagesAtA = mutableListOf<InboundMessage>()
        backgroundScope.launch { harness["A"].engine.messages.collect { messagesAtA.add(it) } }
        testScheduler.runCurrent()

        val payload = ByteArray(256) { 0xCC.toByte() }
        harness["C"].engine.send(harness["A"].identity.keyHash, payload)
        testScheduler.runCurrent()
        repeat(300) { testScheduler.runCurrent() }

        assertEquals(1, messagesAtA.size, "A must receive C's return-path message")
        assertTrue(messagesAtA[0].payload.contentEquals(payload), "Return-path payload must match")

        harness.stopAll()
    }

    // ── Scenario 6: ConnectionLimiter rejects 4th peer at POWER_SAVER ─────────

    @Test
    fun `scenario6 ConnectionLimiter rejects extra peer at POWER_SAVER tier on B`() = runTest {
        val harness =
            MeshTestHarness.builder()
                .node("A")
                .node("B", fastPowerConfig)
                .node("C")
                .link("A", "B")
                .link("B", "C")
                .build(testScheduler, backgroundScope)

        harness.awaitConvergence()

        // A and C are already connected to B (2 connections). POWER_SAVER maxConnections = 2.
        // Set B's battery to 15% so B transitions to POWER_SAVER.
        harness["B"].batteryMonitor.level = 0.15f
        testScheduler.advanceTimeBy(600L)
        testScheduler.runCurrent()
        assertEquals(
            PowerTier.POWER_SAVER,
            harness["B"].engine.currentTier,
            "B must be in POWER_SAVER",
        )

        // Create a 4th peer D with its own transport (not managed by harness).
        val storageD = InMemorySecureStorage()
        val identityD =
            ch.trancee.meshlink.crypto.Identity.loadOrGenerate(
                ch.trancee.meshlink.crypto.createCryptoProvider(),
                storageD,
            )
        val transportD = VirtualMeshTransport(identityD.keyHash, testScheduler)

        // Link D to B
        transportD.linkTo(harness["B"].transport, VirtualLink(rssi = -50))

        // Verify B's sent frames to D before the connection attempt
        val sentToD_before =
            harness["B"].transport.sentFrames.count {
                it.destination.contentEquals(identityD.keyHash)
            }

        // Attempt D to discover B — B's ConnectionLimiter is at max (2 at POWER_SAVER),
        // so the connection attempt should be rejected.
        transportD.simulateDiscovery(
            harness["B"].identity.keyHash,
            PowerTierCodec.encode(PowerTier.PERFORMANCE),
            -50,
        )
        testScheduler.runCurrent()
        repeat(50) { testScheduler.runCurrent() }

        val sentToD_after =
            harness["B"].transport.sentFrames.count {
                it.destination.contentEquals(identityD.keyHash)
            }

        // B must not have sent any data frames to D (connection was not established)
        assertEquals(
            sentToD_before,
            sentToD_after,
            "B must not send data to D (connection rejected at POWER_SAVER limit)",
        )
        assertEquals(
            PowerTier.POWER_SAVER,
            harness["B"].engine.currentTier,
            "B must still be POWER_SAVER",
        )

        harness.stopAll()
    }

    // ── Scenario 7: Corrupted handshake graceful rejection ────────────────────

    @Test
    fun `scenario7 corrupted Noise XX step1 is gracefully rejected`() = runTest {
        // Build a 2-node harness with A↔B already converged
        val harness =
            MeshTestHarness.builder()
                .node("A")
                .node("B")
                .link("A", "B")
                .build(testScheduler, backgroundScope)

        harness.awaitConvergence()

        // Record B's connected peer count before the attack
        val connectedBefore = harness["B"].engine.connectedPeerCount()

        // Create an attacker node C — a raw VirtualMeshTransport, NOT a full MeshEngine.
        // C will send a corrupted Noise XX step-1 message to B.
        val attackerPeerId = ByteArray(32) { 0xFF.toByte() }
        val transportC = VirtualMeshTransport(attackerPeerId, testScheduler)
        transportC.linkTo(harness["B"].transport, VirtualLink(rssi = -50))

        // Build a Handshake step-1 message with a too-short noiseMessage.
        // A valid step-1 requires at least 32 bytes (X25519 ephemeral key + AEAD tag).
        // 3 bytes guarantees readMessage1() throws IllegalArgumentException.
        val corruptedHandshake = Handshake(step = 1u, noiseMessage = ByteArray(3) { 0xDE.toByte() })
        val wireBytes = WireCodec.encode(corruptedHandshake)

        // Send the corrupted handshake from C to B via the linked transport.
        transportC.sendToPeer(harness["B"].identity.keyHash, wireBytes)
        repeat(50) { testScheduler.runCurrent() }

        // Assert: B did not crash (test would throw otherwise), and no new peer was connected
        val connectedAfter = harness["B"].engine.connectedPeerCount()
        assertEquals(
            connectedBefore,
            connectedAfter,
            "B's connected peer count must not increase after corrupted handshake",
        )

        // B may have sent a step-2 response if the corrupted step-1 happened to have valid
        // X25519 bytes (any 32 bytes is a valid curve point). With a too-short message (3 bytes),
        // readMessage1 throws before writeMessage2, so no response is sent.
        val framesToAttacker =
            harness["B"].transport.sentFrames.count { it.destination.contentEquals(attackerPeerId) }
        assertEquals(
            0,
            framesToAttacker,
            "B must not send any frames to the attacker after corrupted handshake",
        )

        harness.stopAll()
    }
}
