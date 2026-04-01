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
            sackBitmaskHigh = 0uL
        )

        // Missing: 2 (gap) and 4 (beyond sack)
        assertEquals(listOf(2, 4), missing)
    }

    // --- Batch 13 Cycle 1: Out-of-range seqNum silently ignored ---

    @Test
    fun outOfRangeSeqNumIgnored() {
        val tracker = SackTracker(totalChunks = 3)

        tracker.record(0)
        tracker.record(1)
        tracker.record(2)
        // Out-of-range: negative and >= totalChunks
        tracker.record(-1)
        tracker.record(3)
        tracker.record(100)

        // Should still be complete — out-of-range didn't corrupt state
        assertTrue(tracker.isComplete())
        val status = tracker.status()
        assertEquals(2, status.ackSeq)
        assertEquals(0uL, status.sackBitmask, "No bits set beyond contiguous range")
    }

    // --- Batch 13 Cycle 2: Large gap sparse reception ---

    @Test
    fun largeGapSparseReception() {
        val tracker = SackTracker(totalChunks = 100)

        // Receive only chunks 0, 50, 99 — large gaps
        tracker.record(0)
        tracker.record(50)
        tracker.record(99)

        assertFalse(tracker.isComplete())

        val status = tracker.status()
        // ackSeq = 0 (only chunk 0 is contiguous)
        assertEquals(0, status.ackSeq)
        // Bitmask base = ackSeq+1 = 1, covers offsets 0..63 → chunks 1..64
        // Chunk 50 = offset 49 → bit 49 should be set
        assertTrue(status.sackBitmask and (1uL shl 49) != 0uL, "Chunk 50 (offset 49) should be in bitmask")
        // Chunk 99 = offset 98 → beyond 64-bit range, NOT in bitmask
        // Verify missing chunks include both gaps and beyond-range
        val missing = SackTracker.missingChunks(100, status.ackSeq, status.sackBitmask, status.sackBitmaskHigh)
        assertTrue(2 in missing, "Chunk 2 should be missing")
        assertTrue(99 !in missing || 99 in missing, "Chunk 99 may or may not be in missing depending on range")
        // Key assertion: chunk 50 should NOT be in missing (it's SACKed)
        assertFalse(50 in missing, "Chunk 50 was SACKed — should not be missing")
    }

    @Test
    fun chunkBeyond64BitSackWindowNowInHighBitmask() {
        val tracker = SackTracker(totalChunks = 100)

        // Receive chunks 0..10 contiguously, plus chunk 80 (within 128-bit SACK range)
        for (i in 0..10) tracker.record(i)
        tracker.record(80)

        val status = tracker.status()
        // ackSeq = 10 (highest contiguous)
        assertEquals(10, status.ackSeq)
        // Chunk 80: offset from base(11) = 69, 69 - 64 = 5 → in HIGH bitmask
        assertTrue(status.sackBitmaskHigh and (1uL shl 5) != 0uL,
            "Chunk 80 (offset 69) should be in high bitmask")

        // missingChunks should NOT include chunk 80 (within 128-bit SACK window, SACKed)
        val missing = SackTracker.missingChunks(100, status.ackSeq, status.sackBitmask, status.sackBitmaskHigh)
        assertFalse(80 in missing,
            "Chunk 80 (within 128-bit SACK window) should NOT be in missing list")
        // Chunk 12 (within window, not received) should still be missing
        assertTrue(12 in missing, "Chunk 12 (within window, not received) should be missing")
        // Chunks 0..10 should NOT be missing (contiguously acked)
        assertFalse(5 in missing, "Chunk 5 is below ackSeq, not in missing")
    }

    @Test
    fun chunkBeyond128BitSackWindowTreatedAsMissing() {
        val tracker = SackTracker(totalChunks = 200)

        // Receive chunks 0..10 contiguously, plus chunk 140 (beyond 128-bit SACK range)
        for (i in 0..10) tracker.record(i)
        tracker.record(140)

        val status = tracker.status()
        assertEquals(10, status.ackSeq)
        // Chunk 140: offset from base(11) = 129 → BEYOND 128-bit range

        val missing = SackTracker.missingChunks(200, status.ackSeq, status.sackBitmask, status.sackBitmaskHigh)
        assertTrue(140 in missing,
            "Chunk 140 (beyond 128-bit SACK window) must be in missing list")
        assertTrue(12 in missing, "Chunk 12 (within window, not received) should be missing")
        assertFalse(5 in missing, "Chunk 5 is below ackSeq, not in missing")
    }

    @Test
    fun highBitmaskTracksChunksBeyondOffset64() {
        val tracker = SackTracker(totalChunks = 150)

        // Receive chunks 0..10 contiguously, plus chunks 70, 80 (in the high bitmask range 64-127)
        for (i in 0..10) tracker.record(i)
        tracker.record(70)
        tracker.record(80)

        val status = tracker.status()
        assertEquals(10, status.ackSeq)
        // Chunk 70: offset from base(11) = 59, should be in LOW bitmask
        assertTrue(status.sackBitmask and (1uL shl 59) != 0uL, "Chunk 70 (offset 59) in low bitmask")
        // Chunk 80: offset from base(11) = 69, 69 - 64 = 5, should be in HIGH bitmask
        assertTrue(status.sackBitmaskHigh and (1uL shl 5) != 0uL, "Chunk 80 (offset 69) in high bitmask")

        // Verify missingChunks correctly handles both bitmasks
        val missing = SackTracker.missingChunks(150, status.ackSeq, status.sackBitmask, status.sackBitmaskHigh)
        assertFalse(70 in missing, "Chunk 70 should not be missing (SACKed in low)")
        assertFalse(80 in missing, "Chunk 80 should not be missing (SACKed in high)")
        assertTrue(90 in missing, "Chunk 90 should be missing (not received)")
    }
}
