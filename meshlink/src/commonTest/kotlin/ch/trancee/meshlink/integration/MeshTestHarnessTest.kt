package ch.trancee.meshlink.integration

import ch.trancee.meshlink.messaging.InboundMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * Smoke tests for [MeshTestHarness] proving:
 * 1. Builder creates nodes and links correctly.
 * 2. Full 3-step Noise XX handshake completes (no pre-pinned keys).
 * 3. A→B unicast delivery works end-to-end after [MeshTestHarness.awaitConvergence].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshTestHarnessTest {

    @Test
    fun `2-node A to B message delivery over full Noise XX`() = runTest {
        val harness =
            MeshTestHarness.builder()
                .node("A")
                .node("B")
                .link("A", "B")
                .build(testScheduler, backgroundScope)

        harness.awaitConvergence()

        // Collect messages at B
        val messagesAtB = mutableListOf<InboundMessage>()
        backgroundScope.launch { harness["B"].engine.messages.collect { messagesAtB.add(it) } }
        testScheduler.runCurrent()

        // Send a small payload from A to B
        val payload = ByteArray(64) { it.toByte() }
        harness["A"].engine.send(harness["B"].identity.keyHash, payload)
        testScheduler.runCurrent()

        // Transfer is coroutine-driven (no timer advance needed); flush frames
        repeat(200) { testScheduler.runCurrent() }

        // B received exactly 1 message with correct payload
        assertEquals(1, messagesAtB.size, "B must receive exactly 1 message")
        assertTrue(
            messagesAtB[0].payload.contentEquals(payload),
            "B's payload must match sent data",
        )

        // Transport-level evidence: A sent at least one frame to B
        assertTrue(
            harness["A"].transport.sentFrames.isNotEmpty(),
            "A must have sent at least 1 frame",
        )

        harness.stopAll()
    }

    @Test
    fun `3-node A to C via relay B over full Noise XX`() = runTest {
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

        // Send from A to C (relayed via B)
        val payload = ByteArray(128) { (it * 3).toByte() }
        harness["A"].engine.send(harness["C"].identity.keyHash, payload)
        testScheduler.runCurrent()

        // Flush relay + ACK round-trips
        repeat(500) { testScheduler.runCurrent() }

        // C received the message
        assertEquals(1, messagesAtC.size, "C must receive exactly 1 message via relay B")
        assertTrue(
            messagesAtC[0].payload.contentEquals(payload),
            "C's payload must match sent data",
        )

        // B relayed (transport B sent at least one frame)
        assertTrue(
            harness["B"].transport.sentFrames.isNotEmpty(),
            "B must have relayed at least 1 frame",
        )

        harness.stopAll()
    }

    @Test
    fun `node accessor throws for unknown name`() = runTest {
        val harness =
            MeshTestHarness.builder()
                .node("A")
                .node("B")
                .link("A", "B")
                .build(testScheduler, backgroundScope)

        val error =
            try {
                harness["Z"]
                null
            } catch (e: IllegalArgumentException) {
                e
            }
        assertTrue(error != null, "Accessing unknown node must throw")
        assertTrue(error.message!!.contains("Z"), "Error must mention the missing node name")

        harness.stopAll()
    }
}
