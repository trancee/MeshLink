package ch.trancee.meshlink.platform.android

import kotlin.test.Test
import kotlin.test.assertEquals

class GattNotifyChunkSizingTest {
    @Test
    fun maximumGattWriteChunkBytesSubtractsAttOverheadFromTheDefaultMtu(): Unit {
        // Arrange
        val currentMtu = 23

        // Act
        val chunkBytes = maximumGattWriteChunkBytes(currentMtu)

        // Assert
        assertEquals(20, chunkBytes)
    }

    @Test
    fun maximumGattWriteChunkBytesCapsLargeMtuValuesAtTheSafeWriteLimit(): Unit {
        // Arrange
        val currentMtu = 517

        // Act
        val chunkBytes = maximumGattWriteChunkBytes(currentMtu)

        // Assert
        assertEquals(512, chunkBytes)
    }

    @Test
    fun maximumGattWriteChunkBytesNeverDropsBelowOneByte(): Unit {
        // Arrange
        val currentMtu = 0

        // Act
        val chunkBytes = maximumGattWriteChunkBytes(currentMtu)

        // Assert
        assertEquals(1, chunkBytes)
    }
}
