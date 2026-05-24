package ch.trancee.meshlink.platform.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosL2capOutputWriteStateTest {
    @Test
    fun nextRequestOrNullTracksBatchBoundariesAndRemainingBatchBytes(): Unit {
        // Arrange
        val state =
            IosL2capOutputWriteState(totalBytes = 26, startedAtMs = 100L, maxBatchBytes = 16)

        // Act
        val firstRequest = state.nextRequestOrNull()
        state.recordWriteCall()
        state.recordProgress(writtenBytes = 10, attemptAtMs = 110L)
        val secondRequest = state.nextRequestOrNull()
        state.recordWriteCall()
        state.recordProgress(writtenBytes = 6, attemptAtMs = 120L)
        val thirdRequest = state.nextRequestOrNull()
        state.recordWriteCall()
        state.recordProgress(writtenBytes = 10, attemptAtMs = 150L)
        val noMoreRequests = state.nextRequestOrNull()
        val stats = state.toStats(totalElapsedMs = 170L)

        // Assert
        assertEquals(0, firstRequest?.offset)
        assertEquals(16, firstRequest?.requestedBytes)
        assertEquals(10, secondRequest?.offset)
        assertEquals(6, secondRequest?.requestedBytes)
        assertEquals(16, thirdRequest?.offset)
        assertEquals(10, thirdRequest?.requestedBytes)
        assertNull(noMoreRequests)
        assertEquals(3, stats.writeCalls)
        assertEquals(2, stats.writeBatches)
        assertEquals(10, stats.minWriteBatchBytes)
        assertEquals(16, stats.maxWriteBatchBytes)
        assertEquals(6, stats.minWriteChunkBytes)
        assertEquals(10, stats.maxWriteChunkBytes)
        assertEquals(30L, stats.maxInterWriteGapMs)
    }

    @Test
    fun recordReadyFalseDoesNotStallBeforeTheTimeout(): Unit {
        // Arrange
        val state =
            IosL2capOutputWriteState(totalBytes = 1, startedAtMs = 1_000L, maxBatchBytes = 16)
        state.nextRequestOrNull()

        // Act
        val notStalled = state.recordReadyFalse(nowMs = 5_900L, stallTimeoutMs = 5_000L)
        val stats = state.toStats(totalElapsedMs = 0L)

        // Assert
        assertTrue(notStalled)
        assertEquals(1, stats.backpressureSpins)
        assertEquals(1, stats.readyFalseCount)
    }

    @Test
    fun recordZeroWriteStallsAfterTheTimeout(): Unit {
        // Arrange
        val state =
            IosL2capOutputWriteState(totalBytes = 1, startedAtMs = 1_000L, maxBatchBytes = 16)
        state.nextRequestOrNull()

        // Act
        val notStalled = state.recordZeroWrite(nowMs = 6_100L, stallTimeoutMs = 5_000L)
        val stats = state.toStats(totalElapsedMs = 0L)

        // Assert
        assertFalse(notStalled)
        assertEquals(1, stats.backpressureSpins)
        assertEquals(0, stats.readyFalseCount)
    }
}
