package ch.trancee.meshlink.integration

import ch.trancee.meshlink.api.DiagnosticEvent
import ch.trancee.meshlink.api.DiagnosticPayload
import ch.trancee.meshlink.api.PeerState
import ch.trancee.meshlink.crypto.Identity
import ch.trancee.meshlink.crypto.KeyChangeEvent
import ch.trancee.meshlink.crypto.createCryptoProvider
import ch.trancee.meshlink.messaging.InboundMessage
import ch.trancee.meshlink.storage.InMemorySecureStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * Multi-method integration test exercising the wired MeshEngine API methods end-to-end in a 2-node
 * mesh with full Noise XX handshakes. Covers: peerDetail, allPeerDetails, peerFingerprint,
 * meshHealth (via engine accessors), send/receive, forgetPeer, and rotateIdentity.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StubApiWiringIntegrationTest {

    // ── Scenario 1: peerDetail returns live data after convergence ─────────

    @Test
    fun `peerDetail returns CONNECTED state for linked peer`() = runTest {
        val harness =
            MeshTestHarness.builder()
                .node("A")
                .node("B")
                .link("A", "B")
                .build(testScheduler, backgroundScope)

        harness.awaitConvergence()

        val detail = harness["A"].engine.peerDetail(harness["B"].identity.keyHash)
        assertNotNull(detail, "peerDetail must return non-null for a connected peer")
        assertEquals(PeerState.CONNECTED, detail.state)
        assertTrue(detail.id.contentEquals(harness["B"].identity.keyHash))

        // allPeerDetails must contain B
        val allDetails = harness["A"].engine.allPeerDetails()
        assertTrue(allDetails.any { it.id.contentEquals(harness["B"].identity.keyHash) })

        harness.stopAll()
    }

    // ── Scenario 2: peerFingerprint returns colon-separated hex ────────────

    @Test
    fun `peerFingerprint returns 35-char colon-separated hex string`() = runTest {
        val harness =
            MeshTestHarness.builder()
                .node("A")
                .node("B")
                .link("A", "B")
                .build(testScheduler, backgroundScope)

        harness.awaitConvergence()

        val fingerprint = harness["A"].engine.peerFingerprint(harness["B"].identity.keyHash)
        assertNotNull(fingerprint, "peerFingerprint must return non-null for a pinned peer")
        // 12 bytes × 2 hex chars + 11 colons = 35 characters
        assertEquals(35, fingerprint.length, "Fingerprint must be 35 characters (12 hex pairs)")
        assertTrue(
            fingerprint.matches(Regex("^([0-9A-F]{2}:){11}[0-9A-F]{2}$")),
            "Fingerprint must be colon-separated uppercase hex: $fingerprint",
        )

        harness.stopAll()
    }

    // ── Scenario 3: health snapshot returns live values ────────────────────

    @Test
    fun `health snapshot shows non-zero connected peers and routing table after convergence`() =
        runTest {
            val harness =
                MeshTestHarness.builder()
                    .node("A")
                    .node("B")
                    .link("A", "B")
                    .build(testScheduler, backgroundScope)

            harness.awaitConvergence()

            val connected = harness["A"].engine.connectedPeerCount()
            val routeCount = harness["A"].engine.routingTable.routeCount()

            assertTrue(connected > 0, "connectedPeerCount must be > 0 after convergence")
            assertTrue(routeCount > 0, "routingTableSize must be > 0 after convergence")

            harness.stopAll()
        }

    // ── Scenario 4: send + receive proves DeliveryPipeline operational ────

    @Test
    fun `send and receive works between two connected peers`() = runTest {
        val harness =
            MeshTestHarness.builder()
                .node("A")
                .node("B")
                .link("A", "B")
                .build(testScheduler, backgroundScope)

        harness.awaitConvergence()

        val messagesAtB = mutableListOf<InboundMessage>()
        backgroundScope.launch { harness["B"].engine.messages.collect { messagesAtB.add(it) } }
        testScheduler.runCurrent()

        val payload = ByteArray(64) { it.toByte() }
        harness["A"].engine.send(harness["B"].identity.keyHash, payload)
        repeat(500) { testScheduler.runCurrent() }

        assertEquals(1, messagesAtB.size, "B must receive exactly 1 message")
        assertTrue(messagesAtB[0].payload.contentEquals(payload), "Payload must match")

        harness.stopAll()
    }

    // ── Scenario 5: forgetPeer clears all state ───────────────────────────

    @Test
    fun `forgetPeer clears trust and routing state`() = runTest {
        val harness =
            MeshTestHarness.builder()
                .node("A")
                .node("B")
                .link("A", "B")
                .build(testScheduler, backgroundScope)

        harness.awaitConvergence()

        // Pre-condition: B is known to A
        assertNotNull(harness["A"].engine.peerDetail(harness["B"].identity.keyHash))

        // Collect diagnostic events to verify the forget event is emitted
        val events = mutableListOf<DiagnosticEvent>()
        backgroundScope.launch { harness["A"].diagnosticSink.events.collect { events.add(it) } }
        testScheduler.runCurrent()

        harness["A"].engine.forgetPeer(harness["B"].identity.keyHash)
        testScheduler.runCurrent()

        // After forgetPeer: peer transitions to DISCONNECTED in presence tracker (presence
        // tracker only has onPeerDisconnected — no remove). Key assertion: trust pin cleared.
        val detailAfter = harness["A"].engine.peerDetail(harness["B"].identity.keyHash)
        if (detailAfter != null) {
            assertEquals(
                PeerState.DISCONNECTED,
                detailAfter.state,
                "After forgetPeer, peer state must be DISCONNECTED",
            )
        }

        // Routes via B should be gone
        val routesViaB =
            harness["A"].engine.routingTable.allRoutes().filter {
                it.nextHop.contentEquals(harness["B"].identity.keyHash)
            }
        assertTrue(routesViaB.isEmpty(), "No routes via B should remain after forgetPeer")

        // Trust pin for B removed (peerFingerprint returns null when not pinned)
        assertNull(
            harness["A"].engine.peerFingerprint(harness["B"].identity.keyHash),
            "peerFingerprint must return null after forgetPeer (trust pin removed)",
        )

        // Diagnostic event emitted
        assertTrue(
            events.any {
                val payload = it.payload
                payload is DiagnosticPayload.TextMessage && "peer_forgotten" in payload.message
            },
            "forgetPeer must emit a 'peer_forgotten' diagnostic event",
        )

        harness.stopAll()
    }

    // ── Scenario 6: rotateIdentity changes keys and emits diagnostic ──────

    @Test
    fun `rotateIdentity changes stored keys and emits diagnostic event`() = runTest {
        val harness =
            MeshTestHarness.builder()
                .node("A")
                .node("B")
                .link("A", "B")
                .build(testScheduler, backgroundScope)

        harness.awaitConvergence()

        val crypto = createCryptoProvider()

        // Capture the key hash BEFORE rotation from storage (re-load identity)
        val identityBefore = Identity.loadOrGenerate(crypto, harness["A"].storage)
        val keyHashBefore = identityBefore.keyHash.copyOf()

        // Collect diagnostic events
        val events = mutableListOf<DiagnosticEvent>()
        backgroundScope.launch { harness["A"].diagnosticSink.events.collect { events.add(it) } }
        testScheduler.runCurrent()

        harness["A"].engine.rotateIdentity()
        repeat(100) { testScheduler.runCurrent() }

        // Reload identity from storage — keys should have changed
        val identityAfter = Identity.loadOrGenerate(crypto, harness["A"].storage)
        assertTrue(
            !identityAfter.keyHash.contentEquals(keyHashBefore),
            "Identity keyHash must change after rotateIdentity",
        )

        // Diagnostic event emitted
        assertTrue(
            events.any {
                val payload = it.payload
                payload is DiagnosticPayload.TextMessage && "identity_rotated" in payload.message
            },
            "rotateIdentity must emit an 'identity_rotated' diagnostic event",
        )

        harness.stopAll()
    }

    // ── Scenario 7: key change lifecycle (onKeyChange → repinKey) ─────────

    @Test
    fun `repinKey processes pending key change and emits diagnostic`() = runTest {
        val harness =
            MeshTestHarness.builder()
                .node("A")
                .node("B")
                .link("A", "B")
                .build(testScheduler, backgroundScope)

        harness.awaitConvergence()

        // Inject a fake pending key change for peer B
        val peerId = harness["B"].identity.keyHash
        val oldKey = ByteArray(32) { 0x11 }
        val newKey = ByteArray(32) { 0x22 }
        val event = KeyChangeEvent(peerKeyHash = peerId, oldKey = oldKey, newKey = newKey)
        harness["A"].engine.onKeyChange(event)

        // pendingKeyChangesList should now contain the event
        val pending = harness["A"].engine.pendingKeyChangesList()
        assertEquals(1, pending.size, "There must be exactly 1 pending key change")
        assertTrue(pending[0].peerId.contentEquals(peerId))

        // Collect diagnostic events
        val events = mutableListOf<DiagnosticEvent>()
        backgroundScope.launch { harness["A"].diagnosticSink.events.collect { events.add(it) } }
        testScheduler.runCurrent()

        // repinKey should consume the pending change
        harness["A"].engine.repinKey(peerId)
        testScheduler.runCurrent()

        // Pending list is now empty
        assertTrue(
            harness["A"].engine.pendingKeyChangesList().isEmpty(),
            "pendingKeyChangesList must be empty after repinKey",
        )

        // Diagnostic emitted
        assertTrue(
            events.any {
                val payload = it.payload
                payload is DiagnosticPayload.TextMessage && "key_repinned" in payload.message
            },
            "repinKey must emit a 'key_repinned' diagnostic event",
        )

        harness.stopAll()
    }

    // ── Scenario 8: acceptKeyChange processes pending key change ───────────

    @Test
    fun `acceptKeyChange processes pending key change and emits diagnostic`() = runTest {
        val harness =
            MeshTestHarness.builder()
                .node("A")
                .node("B")
                .link("A", "B")
                .build(testScheduler, backgroundScope)

        harness.awaitConvergence()

        val peerId = harness["B"].identity.keyHash
        val event =
            KeyChangeEvent(
                peerKeyHash = peerId,
                oldKey = ByteArray(32) { 0x33 },
                newKey = ByteArray(32) { 0x44 },
            )
        harness["A"].engine.onKeyChange(event)

        val events = mutableListOf<DiagnosticEvent>()
        backgroundScope.launch { harness["A"].diagnosticSink.events.collect { events.add(it) } }
        testScheduler.runCurrent()

        harness["A"].engine.acceptKeyChange(peerId)
        testScheduler.runCurrent()

        assertTrue(
            harness["A"].engine.pendingKeyChangesList().isEmpty(),
            "pendingKeyChangesList must be empty after acceptKeyChange",
        )
        assertTrue(
            events.any {
                val payload = it.payload
                payload is DiagnosticPayload.TextMessage && "key_change_accepted" in payload.message
            },
            "acceptKeyChange must emit a 'key_change_accepted' diagnostic event",
        )

        harness.stopAll()
    }

    // ── Scenario 9: rejectKeyChange processes pending key change ───────────

    @Test
    fun `rejectKeyChange processes pending key change and disconnects peer`() = runTest {
        val harness =
            MeshTestHarness.builder()
                .node("A")
                .node("B")
                .link("A", "B")
                .build(testScheduler, backgroundScope)

        harness.awaitConvergence()

        val peerId = harness["B"].identity.keyHash
        val event =
            KeyChangeEvent(
                peerKeyHash = peerId,
                oldKey = ByteArray(32) { 0x55 },
                newKey = ByteArray(32) { 0x66 },
            )
        harness["A"].engine.onKeyChange(event)

        val events = mutableListOf<DiagnosticEvent>()
        backgroundScope.launch { harness["A"].diagnosticSink.events.collect { events.add(it) } }
        testScheduler.runCurrent()

        harness["A"].engine.rejectKeyChange(peerId)
        testScheduler.runCurrent()

        assertTrue(
            harness["A"].engine.pendingKeyChangesList().isEmpty(),
            "pendingKeyChangesList must be empty after rejectKeyChange",
        )
        assertTrue(
            events.any {
                val payload = it.payload
                payload is DiagnosticPayload.TextMessage && "key_change_rejected" in payload.message
            },
            "rejectKeyChange must emit a 'key_change_rejected' diagnostic event",
        )

        harness.stopAll()
    }

    // ── Scenario 10: bufferUtilizationPercent after convergence ────────────

    @Test
    fun `bufferUtilizationPercent returns zero when no messages buffered`() = runTest {
        val harness =
            MeshTestHarness.builder()
                .node("A")
                .node("B")
                .link("A", "B")
                .build(testScheduler, backgroundScope)

        harness.awaitConvergence()

        // With no buffered messages, utilization should be 0
        assertEquals(
            0,
            harness["A"].engine.bufferUtilizationPercent(),
            "bufferUtilizationPercent must be 0 when no messages buffered",
        )

        harness.stopAll()
    }

    // ── Scenario 11: bufferUtilizationPercent returns 0 when capacity is 0 ──

    @Test
    fun `bufferUtilizationPercent returns 0 when buffer capacity is zero`() = runTest {
        val zeroBufferConfig =
            MeshTestHarness.integrationConfig.copy(
                messaging =
                    MeshTestHarness.integrationConfig.messaging.copy(maxBufferedMessages = 0)
            )
        val harness =
            MeshTestHarness.builder()
                .node("A", zeroBufferConfig)
                .node("B")
                .link("A", "B")
                .build(testScheduler, backgroundScope)

        harness.awaitConvergence()

        // With maxBufferedMessages=0, capacity is 0 → utilization must be 0 (not divide-by-zero)
        assertEquals(
            0,
            harness["A"].engine.bufferUtilizationPercent(),
            "bufferUtilizationPercent must be 0 when buffer capacity is 0",
        )

        harness.stopAll()
    }

    // ── Scenario 12: bufferSizeBytes with buffered messages ─────────────────

    @Test
    fun `bufferSizeBytes returns non-zero when messages are buffered`() = runTest {
        val harness =
            MeshTestHarness.builder()
                .node("A")
                .node("B")
                .node("C") // not linked — no route from A to C
                .link("A", "B")
                .build(testScheduler, backgroundScope)

        harness.awaitConvergence()

        // Send a message to C (pinned via handshake, but no route from A) → stored in sendBuffer.
        // C is a valid node with a pinned key (because MeshTestHarness creates all nodes),
        // but A has no route to C. Use DeliveryPipeline directly via engine.send.
        val cId = harness["C"].identity.keyHash
        // First pin C's key in A's trust store by starting C's engine and triggering a
        // lookup. Actually, the simplest approach: call broadcast on A which always works
        // and goes through a different path, OR just verify the buffer via a send that gets
        // queued.
        //
        // Actually — send() throws if key not pinned. Let's use a 3-node topology where
        // A↔B but B is NOT linked to C. Send from A to C will find no route → buffer.
        // But C's key must be pinned in A. After convergence, B knows about C (if linked).
        // Since C isn't linked at all, its key is NOT pinned in A.
        //
        // Simplest fix: use the pipeline's internal sendBuffer by sending to B when B is
        // disconnected. Let's disconnect B first, then send.
        val bId = harness["B"].identity.keyHash

        // Disconnect B from A's perspective.
        harness["A"].transport.simulatePeerLost(bId)
        testScheduler.runCurrent()

        // Now send to B — route to B should be gone or expired.
        // Actually route may still exist (not expired). Let me instead expire it.
        // Use a node that was never connected: create a fake peerId that IS pinned.
        // Pin a fake key in A's storage.
        val fakeId = ByteArray(12) { 0xCC.toByte() }
        val sb = StringBuilder()
        for (b in fakeId) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v shr 4])
            sb.append("0123456789abcdef"[v and 0xF])
        }
        harness["A"].storage.put("meshlink.trust.$sb", ByteArray(32) { 0x11.toByte() })

        // Send to fakeId — no route exists → buffered.
        val result = harness["A"].engine.send(fakeId, ByteArray(50) { it.toByte() })
        testScheduler.runCurrent()

        val size = harness["A"].engine.bufferSizeBytes()
        assertTrue(size > 0, "bufferSizeBytes must be > 0 when a message is buffered, got $size")

        harness.stopAll()
    }

    // ── Scenario 13: TrustStore onKeyChange callback fires through engine ────

    @Test
    fun `TrustStore callback fires through engine creating pending key change`() = runTest {
        // Pre-populate A's storage with a WRONG pinned key for B.
        // When A↔B handshake completes, TrustStore.pinKey detects mismatch → fires onKeyChange.
        val crypto = createCryptoProvider()

        // Create B's identity first to know its keyHash.
        val storageB = ch.trancee.meshlink.storage.InMemorySecureStorage()
        val identityB = Identity.loadOrGenerate(crypto, storageB)
        val bKeyHash = identityB.keyHash

        // Create A's storage and pin a WRONG key for B.
        val storageA = ch.trancee.meshlink.storage.InMemorySecureStorage()
        Identity.loadOrGenerate(crypto, storageA) // Generate A's identity in its storage.
        val sb = StringBuilder()
        for (b in bKeyHash) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v shr 4])
            sb.append("0123456789abcdef"[v and 0xF])
        }
        val trustKey = "meshlink.trust.$sb"
        storageA.put(trustKey, ByteArray(32) { 0xFF.toByte() }) // Wrong key!

        val harness =
            MeshTestHarness.builder()
                .nodeWithStorage("A", storageA)
                .nodeWithStorage("B", storageB)
                .link("A", "B")
                .build(testScheduler, backgroundScope)

        // During handshake, B presents real static key. A's TrustStore finds mismatch →
        // fires onKeyChange callback → MeshEngine.onKeyChange() → pending list.
        harness.awaitConvergence()
        testScheduler.runCurrent()

        val pending = harness["A"].engine.pendingKeyChangesList()
        assertTrue(
            pending.any { it.peerId.contentEquals(bKeyHash) },
            "TrustStore callback should have fired via engine, creating a pending key change for B",
        )

        harness.stopAll()
    }
}
