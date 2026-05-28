package ch.trancee.meshlink.platform.ios

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GattNotifyPumpStateTest {
    @Test
    fun beginPumpRejectsClosedOrConcurrentPumps(): Unit {
        // Arrange
        val state = GattNotifyPumpState()
        val firstBegin = state.beginPump()
        val secondBegin = state.beginPump()
        state.close()

        // Act
        val beginAfterClose = state.beginPump()

        // Assert
        assertTrue(firstBegin)
        assertFalse(secondBegin)
        assertFalse(beginAfterClose)
    }

    @Test
    fun discardQueuedFramesPreservesTheHeadFrameWhileAPumpIsInProgress(): Unit {
        // Arrange
        val state = GattNotifyPumpState()
        val headFrame = GattNotifyPendingFrame(listOf(byteArrayOf(0x01)))
        val tailFrame = GattNotifyPendingFrame(listOf(byteArrayOf(0x02)))
        state.enqueue(headFrame)
        state.enqueue(tailFrame)
        state.beginPump()

        // Act
        val discardedFrames = state.discardQueuedFrames()
        val nextChunk = state.nextPendingChunkOrNull()

        // Assert
        assertEquals(1, discardedFrames.size)
        assertSame(tailFrame, discardedFrames.single())
        assertContentEquals(byteArrayOf(0x01), nextChunk)
    }

    @Test
    fun recordPumpAttemptCompletesTheHeadFrameAfterItsFinalChunk(): Unit {
        // Arrange
        val state = GattNotifyPumpState()
        val frame = GattNotifyPendingFrame(listOf(byteArrayOf(0x01), byteArrayOf(0x02, 0x03)))
        state.enqueue(frame)
        state.beginPump()

        // Act
        val firstOutcome = state.recordPumpAttempt(didSend = true)
        val secondOutcome = state.recordPumpAttempt(didSend = true)

        // Assert
        assertNull(firstOutcome.completedFrame)
        assertEquals(1, firstOutcome.pendingChunkCount)
        assertSame(frame, secondOutcome.completedFrame)
        assertEquals(0, secondOutcome.pendingChunkCount)
    }

    @Test
    fun recordPumpAttemptSchedulesOnlyOneRetryPerPumpRun(): Unit {
        // Arrange
        val state = GattNotifyPumpState()
        state.enqueue(GattNotifyPendingFrame(listOf(byteArrayOf(0x01))))
        state.beginPump()

        // Act
        val firstOutcome = state.recordPumpAttempt(didSend = false)
        val secondOutcome = state.recordPumpAttempt(didSend = false)

        // Assert
        assertTrue(firstOutcome.shouldScheduleRetryPump)
        assertEquals(1, firstOutcome.pendingChunkCount)
        assertFalse(secondOutcome.shouldScheduleRetryPump)
        assertEquals(1, secondOutcome.pendingChunkCount)
    }

    @Test
    fun closeDrainsPendingFramesAndRejectsFurtherWork(): Unit {
        // Arrange
        val state = GattNotifyPumpState()
        val frame = GattNotifyPendingFrame(listOf(byteArrayOf(0x01)))
        state.enqueue(frame)

        // Act
        val discardedFrames = state.close()
        val enqueueAfterClose = state.enqueue(GattNotifyPendingFrame(listOf(byteArrayOf(0x02))))
        val nextChunk = state.nextPendingChunkOrNull()

        // Assert
        assertEquals(1, discardedFrames.size)
        assertSame(frame, discardedFrames.single())
        assertFalse(enqueueAfterClose)
        assertNull(nextChunk)
        assertTrue(state.isClosed())
    }
}
