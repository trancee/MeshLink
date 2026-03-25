package io.meshlink.transfer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SackTrackerTest {

    @Test
    fun recordChunksProducesCorrectAckSeqAndBitmask() {
        val tracker = SackTracker(totalChunks = 5)

        // Receive chunks 0, 1, 3 (gap at 2)
        tracker.record(0)
        tracker.record(1)
        tracker.record(3)

        val status = tracker.status()
        // ackSeq = highest contiguous from 0 = 1
        assertEquals(1, status.ackSeq)
        // sackBitmask: bit for chunk 3 should be set (offset from ackSeq+1=2: chunk 3 is offset 1)
        assertTrue(status.sackBitmask and (1uL shl 1) != 0uL, "bit for chunk 3 should be set")
        // Bit for chunk 2 should NOT be set (not received)
        assertTrue(status.sackBitmask and (1uL shl 0) == 0uL, "bit for chunk 2 should NOT be set")

        // Not yet complete
        assertFalse(tracker.isComplete())
    }

    @Test
    fun missingChunksFromSack() {
        // Sender sent 5 chunks (0..4), receiver ACKs {0,1,3}: ackSeq=1, sack bit for 3
        val missing = SackTracker.missingChunks(
            totalChunks = 5,
            ackSeq = 1,
            sackBitmask = (1uL shl 1), // bit 1 = chunk 3 (base = ackSeq+1 = 2, offset 1 = chunk 3)
        )

        // Missing: 2 (gap) and 4 (beyond sack)
        assertEquals(listOf(2, 4), missing)
    }
}
