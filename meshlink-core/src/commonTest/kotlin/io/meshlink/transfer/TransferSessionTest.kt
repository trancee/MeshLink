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
}
