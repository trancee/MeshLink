package ch.trancee.meshlink.platform.ios

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class GattNotifyPendingFrameTest {
    @Test
    fun nextChunkOrNullReturnsChunksInOrderAndThenNull(): Unit {
        // Arrange
        val frame = GattNotifyPendingFrame(listOf(byteArrayOf(0x01), byteArrayOf(0x02, 0x03)))

        // Act / Assert
        assertContentEquals(byteArrayOf(0x01), frame.nextChunkOrNull())
        assertEquals(false, frame.markCurrentChunkSent())
        assertContentEquals(byteArrayOf(0x02, 0x03), frame.nextChunkOrNull())
        assertEquals(true, frame.markCurrentChunkSent())
        assertEquals(null, frame.nextChunkOrNull())
    }

    @Test
    fun remainingChunkCountTracksProgressAndNeverDropsBelowZero(): Unit {
        // Arrange
        val frame = GattNotifyPendingFrame(listOf(byteArrayOf(0x01), byteArrayOf(0x02)))

        // Act
        val before = frame.remainingChunkCount()
        frame.markCurrentChunkSent()
        val middle = frame.remainingChunkCount()
        frame.markCurrentChunkSent()
        frame.markCurrentChunkSent()
        val after = frame.remainingChunkCount()

        // Assert
        assertEquals(2, before)
        assertEquals(1, middle)
        assertEquals(0, after)
    }

    @Test
    fun completeIfPendingOnlyCompletesTheFrameOnce(): Unit = runBlocking {
        // Arrange
        val frame = GattNotifyPendingFrame(listOf(byteArrayOf(0x01)))

        // Act
        frame.completeIfPending(false)
        frame.completeIfPending(true)
        val completion = frame.awaitCompletion()

        // Assert
        assertEquals(false, completion)
    }
}
