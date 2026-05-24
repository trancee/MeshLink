package ch.trancee.meshlink.platform.ios

import kotlin.test.Test
import kotlin.test.assertEquals

class IosGattNotifyChunkSizingTest {
    @Test
    fun maximumGattNotificationChunkBytesUsesTheCentralBudgetWhenItIsBelowThePreferredLimit():
        Unit {
        // Arrange
        val maximumUpdateValueLength = 182

        // Act
        val chunkBytes = maximumGattNotificationChunkBytes(maximumUpdateValueLength)

        // Assert
        assertEquals(182, chunkBytes)
    }

    @Test
    fun maximumGattNotificationChunkBytesCapsLargeBudgetsAtThePreferredFrameSize(): Unit {
        // Arrange
        val maximumUpdateValueLength = 600

        // Act
        val chunkBytes = maximumGattNotificationChunkBytes(maximumUpdateValueLength)

        // Assert
        assertEquals(495, chunkBytes)
    }

    @Test
    fun maximumGattNotificationChunkBytesNeverDropsBelowOneByte(): Unit {
        // Arrange
        val maximumUpdateValueLength = 0

        // Act
        val chunkBytes = maximumGattNotificationChunkBytes(maximumUpdateValueLength)

        // Assert
        assertEquals(1, chunkBytes)
    }
}
