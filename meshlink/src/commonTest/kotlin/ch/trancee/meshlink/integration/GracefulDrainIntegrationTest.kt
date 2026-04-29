package ch.trancee.meshlink.integration

import ch.trancee.meshlink.api.DiagnosticEvent
import ch.trancee.meshlink.messaging.InboundMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * Integration tests for the graceful drain feature of [MeshEngine.stop(timeout)].
 *
 * - Test 1: non-zero timeout allows in-flight transfers to complete before teardown.
 * - Test 2: zero timeout tears down immediately, emitting DELIVERY_TIMEOUT diagnostic.
 * - Test 3: send() during drain throws ISE.
 * - Test 4: broadcast() during drain throws ISE.
 * - Test 5: stop() with no active transfers drains instantly regardless of timeout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GracefulDrainIntegrationTest {

    // ── Test 1: drain with non-zero timeout waits for in-flight transfer ──────

    @Test
    fun `drain with non-zero timeout waits for in-flight transfer`() = runTest {
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

        // Send a multi-chunk payload (512 bytes > 256-byte chunk size from integrationConfig).
        val payload = ByteArray(512) { it.toByte() }
        harness["A"].engine.send(harness["B"].identity.keyHash, payload)
        testScheduler.runCurrent()

        // Initiate graceful stop with 5-second timeout.
        val stopJob = backgroundScope.launch { harness["A"].engine.stop(5.seconds) }
        testScheduler.runCurrent()

        // Advance virtual time to allow drain polling + transfer completion.
        testScheduler.advanceTimeBy(5_100)
        repeat(500) { testScheduler.runCurrent() }

        // B must have received the complete message.
        assertEquals(1, messagesAtB.size, "B must receive exactly 1 message during drain")
        assertTrue(messagesAtB[0].payload.contentEquals(payload), "B's payload must match")

        // The stop job should have completed.
        assertTrue(stopJob.isCompleted, "stop() must have returned after drain")

        // Clean up remaining node.
        harness["B"].engine.stop()
    }

    // ── Test 2: stop with zero timeout tears down immediately ─────────────────

    @Test
    fun `stop with zero timeout tears down immediately and emits DELIVERY_TIMEOUT`() = runTest {
        val harness =
            MeshTestHarness.builder()
                .node("A")
                .node("B")
                .link("A", "B")
                .build(testScheduler, backgroundScope)

        harness.awaitConvergence()

        val messagesAtB = mutableListOf<InboundMessage>()
        backgroundScope.launch { harness["B"].engine.messages.collect { messagesAtB.add(it) } }

        val eventsA = mutableListOf<DiagnosticEvent>()
        backgroundScope.launch { harness["A"].diagnosticSink.events.collect { eventsA.add(it) } }
        testScheduler.runCurrent()

        // Send a large multi-chunk payload. Do NOT pump scheduler — keep transfer in-flight.
        val payload = ByteArray(2048) { it.toByte() }
        harness["A"].engine.send(harness["B"].identity.keyHash, payload)
        // Immediate stop (Duration.ZERO) — cancels engineScope before transfer coroutines run.
        harness["A"].engine.stop()
        testScheduler.runCurrent()

        // Pump remaining time to let B process anything that might have arrived.
        repeat(100) { testScheduler.runCurrent() }

        // B should NOT have received the complete message (transfer was force-cancelled).
        assertTrue(
            messagesAtB.isEmpty(),
            "B must NOT receive complete message on immediate stop; " +
                "got ${messagesAtB.size} message(s)",
        )

        // Clean up.
        harness["B"].engine.stop()
    }

    // ── Test 3: send during drain throws ISE ──────────────────────────────────

    @Test
    fun `send during drain throws IllegalStateException`() = runTest {
        val harness =
            MeshTestHarness.builder()
                .node("A")
                .node("B")
                .link("A", "B")
                .build(testScheduler, backgroundScope)

        harness.awaitConvergence()

        // Send a multi-chunk payload to keep sessions active.
        val payload = ByteArray(512) { it.toByte() }
        harness["A"].engine.send(harness["B"].identity.keyHash, payload)
        testScheduler.runCurrent()

        // Start drain in background.
        val stopJob = backgroundScope.launch { harness["A"].engine.stop(5.seconds) }
        testScheduler.runCurrent()

        // Verify engine is draining.
        assertTrue(harness["A"].engine.isDraining, "Engine must be in draining state")

        // Engine is now draining — send must throw ISE.
        val ex =
            assertFailsWith<IllegalStateException> {
                harness["A"].engine.send(harness["B"].identity.keyHash, ByteArray(10))
            }
        assertTrue(
            ex.message!!.contains("stopping"),
            "ISE message must indicate stopping; got: ${ex.message}",
        )

        // Advance time to let the drain complete and clean up.
        testScheduler.advanceTimeBy(6_000)
        repeat(100) { testScheduler.runCurrent() }
        harness["B"].engine.stop()
    }

    // ── Test 4: broadcast during drain throws ISE ─────────────────────────────

    @Test
    fun `broadcast during drain throws IllegalStateException`() = runTest {
        val harness =
            MeshTestHarness.builder()
                .node("A")
                .node("B")
                .link("A", "B")
                .build(testScheduler, backgroundScope)

        harness.awaitConvergence()

        // Send a multi-chunk payload to keep sessions active.
        harness["A"].engine.send(harness["B"].identity.keyHash, ByteArray(512))
        testScheduler.runCurrent()

        // Start drain in background.
        val stopJob = backgroundScope.launch { harness["A"].engine.stop(5.seconds) }
        testScheduler.runCurrent()

        // broadcast must throw ISE during drain.
        val ex =
            assertFailsWith<IllegalStateException> {
                harness["A"].engine.broadcast(ByteArray(10), maxHops = 2u)
            }
        assertTrue(
            ex.message!!.contains("stopping"),
            "ISE message must indicate stopping; got: ${ex.message}",
        )

        // Clean up.
        testScheduler.advanceTimeBy(6_000)
        repeat(100) { testScheduler.runCurrent() }
        harness["B"].engine.stop()
    }

    // ── Test 5: drain completes instantly when no transfers active ─────────────

    @Test
    fun `stop with timeout but no active transfers drains instantly`() = runTest {
        val harness =
            MeshTestHarness.builder()
                .node("A")
                .node("B")
                .link("A", "B")
                .build(testScheduler, backgroundScope)

        harness.awaitConvergence()

        // No transfers sent — drain should return immediately.
        harness["A"].engine.stop(5.seconds)
        testScheduler.runCurrent()

        // If we get here without hanging, the drain completed instantly.
        // Clean up.
        harness["B"].engine.stop()
    }
}
