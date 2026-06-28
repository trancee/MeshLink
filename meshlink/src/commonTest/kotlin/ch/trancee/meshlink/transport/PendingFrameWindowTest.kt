package ch.trancee.meshlink.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class PendingFrameWindowTest {
    @Test
    fun `acquire waits until reserved frame capacity is released`() =
        runBlocking<Unit> {
            // Arrange
            val window = PendingFrameWindow(maxPendingFrames = 2, maxPendingBytes = 128)
            assertTrue(window.acquire(frameBytes = 64))
            assertTrue(window.acquire(frameBytes = 32))
            val blockedAcquire = async { window.acquire(frameBytes = 16) }

            // Act
            val resultBeforeRelease = withTimeoutOrNull(20.milliseconds) { blockedAcquire.await() }
            window.release(frameBytes = 64)
            val resultAfterRelease = blockedAcquire.await()

            // Assert
            assertEquals(null, resultBeforeRelease)
            assertTrue(resultAfterRelease)
            assertEquals(2, window.snapshot().pendingFrames)
            assertEquals(48, window.snapshot().pendingBytes)
        }

    @Test
    fun `acquire allows an oversized first frame when the window is otherwise empty`() =
        runBlocking<Unit> {
            // Arrange
            val window = PendingFrameWindow(maxPendingFrames = 2, maxPendingBytes = 128)

            // Act
            val oversizedReservationSucceeded = window.acquire(frameBytes = 256)
            val blockedAcquire = async { window.acquire(frameBytes = 16) }
            val resultBeforeRelease = withTimeoutOrNull(20.milliseconds) { blockedAcquire.await() }
            window.release(frameBytes = 256)
            val resultAfterRelease = blockedAcquire.await()

            // Assert
            assertTrue(oversizedReservationSucceeded)
            assertEquals(null, resultBeforeRelease)
            assertTrue(resultAfterRelease)
            assertEquals(1, window.snapshot().pendingFrames)
            assertEquals(16, window.snapshot().pendingBytes)
        }

    @Test
    fun `close wakes suspended acquirers and reports failure`() =
        runBlocking<Unit> {
            // Arrange
            val window = PendingFrameWindow(maxPendingFrames = 1, maxPendingBytes = 32)
            assertTrue(window.acquire(frameBytes = 32))
            val blockedAcquire = async { window.acquire(frameBytes = 8) }
            delay(10)

            // Act
            window.close()
            val acquireResult = blockedAcquire.await()

            // Assert
            assertFalse(acquireResult)
            assertTrue(window.snapshot().closed)
        }
}
