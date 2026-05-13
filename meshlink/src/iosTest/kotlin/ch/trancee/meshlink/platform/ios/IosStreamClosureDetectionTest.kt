package ch.trancee.meshlink.platform.ios

import platform.Foundation.NSStreamStatusAtEnd
import platform.Foundation.NSStreamStatusClosed
import platform.Foundation.NSStreamStatusError
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosStreamClosureDetectionTest {
    @Test
    fun reportsClosedWhenStreamStatusIsAtEnd(): Unit {
        // Act
        val closed = isStreamClosed(streamStatus = NSStreamStatusAtEnd, hasError = false)

        // Assert
        assertTrue(closed)
    }

    @Test
    fun reportsClosedWhenStreamStatusIsClosed(): Unit {
        // Act
        val closed = isStreamClosed(streamStatus = NSStreamStatusClosed, hasError = false)

        // Assert
        assertTrue(closed)
    }

    @Test
    fun reportsClosedWhenStreamStatusIsError(): Unit {
        // Act
        val closed = isStreamClosed(streamStatus = NSStreamStatusError, hasError = false)

        // Assert
        assertTrue(closed)
    }

    @Test
    fun reportsClosedWhenStreamHasErrorEvenIfStatusIsOpen(): Unit {
        // Act
        val closed = isStreamClosed(streamStatus = 2u, hasError = true)

        // Assert
        assertTrue(closed)
    }

    @Test
    fun reportsOpenWhenStreamStatusIsNotTerminalAndNoErrorExists(): Unit {
        // Act
        val closed = isStreamClosed(streamStatus = 2u, hasError = false)

        // Assert
        assertFalse(closed)
    }

    @Test
    fun reportsStalledWhenNoWriteProgressExceedsTimeout(): Unit {
        // Act
        val stalled = isWriteStalled(lastProgressAtMs = 1_000L, nowMs = 6_500L, stallTimeoutMs = 5_000L)

        // Assert
        assertTrue(stalled)
    }

    @Test
    fun reportsNotStalledWhenWriteProgressIsWithinTimeout(): Unit {
        // Act
        val stalled = isWriteStalled(lastProgressAtMs = 1_000L, nowMs = 5_900L, stallTimeoutMs = 5_000L)

        // Assert
        assertFalse(stalled)
    }
}
