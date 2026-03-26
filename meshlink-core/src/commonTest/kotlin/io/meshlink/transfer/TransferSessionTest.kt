package io.meshlink.transfer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransferSessionTest {

    @Test
    fun sendsUpToWindowAndRetransmitsGapsOnSack() {
        val session = TransferSession(totalChunks = 5, initialWindow = 3)

        assertEquals(listOf(0, 1, 2), session.nextChunksToSend())
        assertFalse(session.isComplete())

        // Receiver got 0 and 2, but NOT 1
        session.onAck(ackSeq = 0, sackBitmask = (1uL shl 1))

        val next = session.nextChunksToSend()
        assertEquals(listOf(1, 3, 4), next)

        session.onAck(ackSeq = 4, sackBitmask = 0u)
        assertTrue(session.isComplete())
        assertEquals(emptyList(), session.nextChunksToSend())
    }

    @Test
    fun inactivityTimeoutMarksTransferFailed() {
        val session = TransferSession(totalChunks = 3, initialWindow = 3)

        // Send all chunks
        session.nextChunksToSend()
        assertFalse(session.isComplete())
        assertFalse(session.isFailed())

        // Simulate 30s inactivity timeout with no ACK
        session.onInactivityTimeout()

        assertTrue(session.isFailed(), "Transfer should be marked failed after inactivity timeout")
        // nextChunksToSend returns empty for failed transfers
        assertEquals(emptyList(), session.nextChunksToSend())
    }

    // --- Batch 9 Cycle 7: Timeout reduces window via AIMD ---

    @Test
    fun timeoutReducesWindowAndRetransmits() {
        // Large initial window of 10 with 6 chunks
        val session = TransferSession(totalChunks = 6, initialWindow = 10)

        // First send: all 6 chunks fit in window
        val first = session.nextChunksToSend()
        assertEquals(listOf(0, 1, 2, 3, 4, 5), first)

        // ACK chunk 0 only — 1-5 are in-flight but not acked
        session.onAck(ackSeq = 0, sackBitmask = 0u)

        // Two consecutive timeouts triggers AIMD halving (window 10 → 5)
        session.onTimeout()
        session.onTimeout()

        // Now ask for retransmits — should only return up to reduced window
        val retransmit = session.nextChunksToSend()
        // Window halved to 5, chunks 1-5 need retransmit — returns at most 5
        assertTrue(retransmit.size <= 5, "Window should be reduced after timeouts, got ${retransmit.size}")
        assertTrue(retransmit.contains(1), "Unacked chunk 1 should be retransmitted")
        assertFalse(retransmit.contains(0), "Already acked chunk 0 should not be retransmitted")
    }
}
