package ch.trancee.meshlink.integration

import ch.trancee.meshlink.api.toHexString
import ch.trancee.meshlink.messaging.DeliveryFailed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * Targeted coverage tests for methods that previously carried CoverageIgnore annotations and are
 * not already covered by the seven main integration scenarios in [MeshEngineIntegrationTest].
 *
 * - `processAckDeadline` — send unicast, disconnect recipient, advance past ack deadline, assert
 *   [DeliveryFailed].
 * - `ByteArray.toHexString()` — direct test now that it's `internal`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoverageIntegrationTest {

    // ── processAckDeadline: ack timeout triggers DeliveryFailed ────────────────

    @Test
    fun `processAckDeadline fires DeliveryFailed after ack timeout`() = runTest {
        // Build a 2-node harness: A ↔ B. A will send to B's real keyHash (whose X25519 key is
        // pinned during the Noise XX handshake). We then sever B's transport so the ACK never
        // arrives, causing the ack-deadline timer to fire processAckDeadline.
        val harness =
            MeshTestHarness.builder()
                .node("A")
                .node("B")
                .link("A", "B")
                .build(testScheduler, backgroundScope)

        harness.awaitConvergence()

        val failures = mutableListOf<DeliveryFailed>()
        backgroundScope.launch { harness["A"].engine.transferFailures.collect { failures.add(it) } }
        testScheduler.runCurrent()

        // Stop B's engine so it can't process incoming chunks or send ACKs back.
        harness["B"].engine.stop()
        testScheduler.runCurrent()

        // Send from A to B — the route to B is still in A's routing table (not expired).
        // TransferEngine will emit chunks to the transport, but B's engine is stopped so
        // no DeliveryAck will arrive.
        val payload = ByteArray(64) { it.toByte() }
        harness["A"].engine.send(harness["B"].identity.keyHash, payload)
        repeat(50) { testScheduler.runCurrent() }

        // Advance time past the ack deadline (inactivityBaseTimeoutMillis = 30_000).
        testScheduler.advanceTimeBy(35_000L)
        repeat(50) { testScheduler.runCurrent() }

        assertTrue(failures.isNotEmpty(), "A must receive at least 1 DeliveryFailed (ack timeout)")

        // Stop A's engine to clean up.
        harness["A"].engine.stop()
    }

    // ── ByteArray.toHexString() direct coverage ───────────────────────────────

    @Test
    fun `toHexString encodes bytes to lowercase hex`() {
        val input = byteArrayOf(0x00, 0x0F, 0x10, 0xFF.toByte(), 0xAB.toByte())
        val hex = input.toHexString()
        assertEquals("000f10ffab", hex)
    }

    @Test
    fun `toHexString on empty array returns empty string`() {
        assertEquals("", ByteArray(0).toHexString())
    }
}
