package ch.trancee.meshlink.platform.ios

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class L2capWriteBatchSupportTest {
    @Test
    fun buildCoalescedBufferSnapshotConcatenatesFramesAndCapturesHeadAndTailHex(): Unit {
        // Arrange
        val firstFrame = byteArrayOf(0x01, 0x02, 0x03)
        val secondFrame = ByteArray(20) { index -> (index + 4).toByte() }

        // Act
        val snapshot = buildCoalescedBufferSnapshot(listOf(firstFrame, secondFrame))

        // Assert
        assertContentEquals(firstFrame + secondFrame, snapshot.buffer)
        assertEquals((firstFrame + secondFrame).copyOf(16).toHexString(), snapshot.batchHeadHex)
        assertEquals(
            (firstFrame + secondFrame)
                .copyOfRange(
                    (firstFrame.size + secondFrame.size) - 16,
                    firstFrame.size + secondFrame.size,
                )
                .toHexString(),
            snapshot.batchTailHex,
        )
    }

    @Test
    fun writeProgressTracksBatchChunkAndGapExtremes(): Unit {
        // Arrange
        val progress = L2capWriteProgress(lastWriteProgressAtMs = 100L)

        // Act
        progress.recordBatch(batchBytes = 512)
        progress.recordBatch(batchBytes = 128)
        progress.recordBackpressure(readyFalse = true)
        progress.recordBackpressure(readyFalse = false)
        progress.recordWrite(writtenBytes = 64, attemptAtMs = 140L)
        progress.recordWrite(writtenBytes = 16, attemptAtMs = 205L)
        val stats = progress.toStats(totalElapsedMs = 220L)

        // Assert
        assertEquals(2, stats.writeBatches)
        assertEquals(2, stats.backpressureSpins)
        assertEquals(1, stats.readyFalseCount)
        assertEquals(16, stats.minWriteChunkBytes)
        assertEquals(64, stats.maxWriteChunkBytes)
        assertEquals(128, stats.minWriteBatchBytes)
        assertEquals(512, stats.maxWriteBatchBytes)
        assertEquals(65L, stats.maxInterWriteGapMs)
        assertEquals(220L, stats.totalElapsedMs)
    }

    @Test
    fun writeProgressNormalizesUnsetMinimumsToZero(): Unit {
        // Arrange
        val progress = L2capWriteProgress(lastWriteProgressAtMs = 100L)

        // Act
        val stats = progress.toStats(totalElapsedMs = 0L)

        // Assert
        assertEquals(0, stats.minWriteChunkBytes)
        assertEquals(0, stats.minWriteBatchBytes)
        assertEquals(0, stats.maxWriteChunkBytes)
        assertEquals(0, stats.maxWriteBatchBytes)
        assertEquals(0L, stats.maxInterWriteGapMs)
    }
}
