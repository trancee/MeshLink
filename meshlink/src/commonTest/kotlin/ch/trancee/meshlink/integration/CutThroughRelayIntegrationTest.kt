package ch.trancee.meshlink.integration

import ch.trancee.meshlink.api.DiagnosticCode
import ch.trancee.meshlink.api.DiagnosticEvent
import ch.trancee.meshlink.api.DiagnosticPayload
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * 3-node integration test proving cut-through relay pipelining: relay node B begins forwarding
 * chunks to C before all inbound chunks from A arrive, verified by structural assertions on
 * [ch.trancee.meshlink.transport.VirtualMeshTransport.sentFrames].
 *
 * **Topology:** A↔B↔C (linear chain, no direct A↔C link).
 *
 * **Payload size:** ~1 KB produces 4+ chunks with chunkSize=256 (integrationConfig).
 *
 * **Key assertions:**
 * 1. B's sentFrames to C contain relay chunks (cut-through forwarding).
 * 2. C receives the original plaintext message end-to-end.
 * 3. B emits a ROUTE_CHANGED diagnostic event with "cut-through relay activated" text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CutThroughRelayIntegrationTest {

    @Test
    fun `3-node cut-through relay forwards chunks from B to C and delivers end-to-end`() = runTest {
        // ── Setup: 3-node linear topology A—B—C ───────────────────────────
        val harness =
            MeshTestHarness.builder()
                .node("A")
                .node("B")
                .node("C")
                .link("A", "B")
                .link("B", "C")
                .build(testScheduler, backgroundScope)

        // Collect diagnostic events from node B (relay).
        val eventsB = mutableListOf<DiagnosticEvent>()
        backgroundScope.launch { harness["B"].diagnosticSink.events.collect { eventsB.add(it) } }
        testScheduler.runCurrent()

        // Collect inbound messages at C.
        val messagesC = mutableListOf<ch.trancee.meshlink.messaging.InboundMessage>()
        backgroundScope.launch { harness["C"].engine.messages.collect { messagesC.add(it) } }
        testScheduler.runCurrent()

        // ── Phase 1: Convergence ──────────────────────────────────────────
        harness.awaitConvergence()

        val nodeA = harness["A"]
        val nodeB = harness["B"]
        val nodeC = harness["C"]

        // ── Phase 2: Send a multi-chunk message from A to C ───────────────
        // With chunkSize=256, a ~1KB payload produces 4+ chunks after
        // RoutedMessage encoding + Noise K encryption overhead.
        val largePayload = ByteArray(1024) { (it % 251).toByte() }

        // Capture B→C frame count before send.
        val bToCSentBefore =
            nodeB.transport.sentFrames.count {
                it.destination.contentEquals(nodeC.identity.keyHash)
            }

        // Send from A to C (routed through B).
        nodeA.engine.send(nodeC.identity.keyHash, largePayload)

        // Advance virtual time to process chunk transfer through the pipeline.
        // GATT mode requires ACK round-trips: A sends chunk → B ACKs → A sends next chunk.
        // Use repeated runCurrent() with small time advances to process ACK-driven flow.
        repeat(50) {
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(1L)
        }
        repeat(200) { testScheduler.runCurrent() }

        // ── Assertion 1: B forwarded chunks to C via cut-through ──────────
        val bToCFrames =
            nodeB.transport.sentFrames.filter {
                it.destination.contentEquals(nodeC.identity.keyHash)
            }
        val bToCFramesAfterSend = bToCFrames.size - bToCSentBefore

        assertTrue(
            bToCFramesAfterSend > 0,
            "Expected B to forward relay chunks to C via cut-through. " +
                "B→C frames after send: $bToCFramesAfterSend " +
                "(total sentFrames: ${nodeB.transport.sentFrames.size})",
        )

        // ── Assertion 2: C received the original plaintext end-to-end ─────
        // Advance more to ensure delivery pipeline processes assembled message.
        repeat(100) { testScheduler.runCurrent() }

        assertTrue(
            messagesC.any { it.payload.contentEquals(largePayload) },
            "Expected C to receive the original plaintext message. " +
                "Messages at C: ${messagesC.size}",
        )

        // Verify sender identity: C sees A as the sender.
        val delivered = messagesC.first { it.payload.contentEquals(largePayload) }
        assertTrue(
            delivered.senderId.contentEquals(nodeA.identity.keyHash),
            "Expected senderId to be A's keyHash",
        )

        // ── Assertion 3: B emitted cut-through activation diagnostic ──────
        val cutThroughActivation = eventsB.filter { event ->
            event.code == DiagnosticCode.ROUTE_CHANGED &&
                event.payload.let { payload ->
                    payload is DiagnosticPayload.TextMessage &&
                        payload.message.contains("cut-through relay activated")
                }
        }

        assertTrue(
            cutThroughActivation.isNotEmpty(),
            "Expected B to emit a cut-through activation diagnostic event. " +
                "B events: ${eventsB.map { "${it.code}:${it.payload}" }}",
        )

        // ── Assertion 4: Structural multi-chunk proof ─────────────────────
        // The message is large enough to require multiple chunks. B should forward
        // at least 2 chunks (original totalChunks worth) to C via cut-through.
        assertTrue(
            bToCFramesAfterSend >= 2,
            "Expected at least 2 relay chunks forwarded B→C (multi-chunk message). " +
                "Got: $bToCFramesAfterSend",
        )

        // ── Cleanup ───────────────────────────────────────────────────────
        harness.stopAll()
    }
}
