package io.meshlink.transfer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SackTrackerTest {

    @Test
    fun contiguousChunksProduceCorrectAckSeq() {
        val tracker = SackTracker(totalChunks = 10)
        tracker.record(0)
        tracker.record(1)
        tracker.record(2)

        val status = tracker.status()
        assertEquals(2, status.ackSeq)
        assertEquals(0uL, status.sackBitmask)
    }

    @Test
    fun outOfOrderChunksSetsCorrectSackBits() {
        val tracker = SackTracker(totalChunks = 10)
        tracker.record(0)
        // Skip chunk 1
        tracker.record(2)
        tracker.record(4)

        val status = tracker.status()
        assertEquals(0, status.ackSeq) // Only chunk 0 is contiguous
        // Bit 0 = chunk 1 (NOT received → bit unset)
        // Bit 1 = chunk 2 (received → bit set)
        // Bit 3 = chunk 4 (received → bit set)
        assertTrue(status.sackBitmask and (1uL shl 1) != 0uL, "Chunk 2 should be SACKed at bit 1")
        assertTrue(status.sackBitmask and (1uL shl 3) != 0uL, "Chunk 4 should be SACKed at bit 3")
        assertFalse(status.sackBitmask and (1uL shl 0) != 0uL, "Chunk 1 was not received")
    }

    @Test
    fun completeTransferDetection() {
        val tracker = SackTracker(totalChunks = 3)
        assertFalse(tracker.isComplete())
        tracker.record(0); tracker.record(1); tracker.record(2)
        assertTrue(tracker.isComplete())
    }

    @Test
    fun missingChunksWithSackBitmask() {
        val tracker = SackTracker(totalChunks = 100)
        // Receive chunks 0..10, plus 50
        for (i in 0..10) tracker.record(i)
        tracker.record(50)

        val status = tracker.status()
        assertEquals(10, status.ackSeq)

        val missing = SackTracker.missingChunks(100, status.ackSeq, status.sackBitmask)
        assertTrue(12 in missing, "Chunk 12 should be missing")
        assertFalse(50 in missing, "Chunk 50 was SACKed — should not be missing")
        assertFalse(5 in missing, "Chunk 5 is below ackSeq, not in missing")
    }

    @Test
    fun chunkBeyond64BitSackWindowTreatedAsMissing() {
        val tracker = SackTracker(totalChunks = 100)

        // Receive chunks 0..10 contiguously, plus chunk 80 (beyond 64-bit SACK range)
        for (i in 0..10) tracker.record(i)
        tracker.record(80)

        val status = tracker.status()
        assertEquals(10, status.ackSeq)
        // Chunk 80: offset from base(11) = 69 → beyond 64-bit SACK range

        val missing = SackTracker.missingChunks(100, status.ackSeq, status.sackBitmask)
        // With 64-bit SACK, chunk 80 is outside the window and appears as missing
        assertTrue(80 in missing,
            "Chunk 80 (beyond 64-bit SACK window) should be in missing list")
        assertTrue(12 in missing, "Chunk 12 (within window, not received) should be missing")
        assertFalse(5 in missing, "Chunk 5 is below ackSeq, not in missing")
    }

    @Test
    fun chunkWithinSackWindowNotMissing() {
        val tracker = SackTracker(totalChunks = 100)

        // Receive chunks 0..10 contiguously, plus chunk 40 (within 64-bit window)
        for (i in 0..10) tracker.record(i)
        tracker.record(40)

        val status = tracker.status()
        assertEquals(10, status.ackSeq)
        // Chunk 40: offset from base(11) = 29 → within 64-bit range
        assertTrue(status.sackBitmask and (1uL shl 29) != 0uL, "Chunk 40 (offset 29) in bitmask")

        val missing = SackTracker.missingChunks(100, status.ackSeq, status.sackBitmask)
        assertFalse(40 in missing, "Chunk 40 should not be missing (SACKed)")
        assertTrue(12 in missing, "Chunk 12 (not received) should be missing")
    }
}
