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
    fun maximumGattWriteChunkBytesCapsLargeMtuValuesAtTheDleTwoPacketSweetSpot(): Unit {
        // Arrange
        val currentMtu = 517

        // Act
        val chunkBytes = maximumGattWriteChunkBytes(currentMtu)

        // Assert: capped at 495 bytes (two full DLE Link Layer packets), not the naive
        // MTU-minus-overhead 514 or the old 512-byte cap, to avoid spilling a mostly-empty
        // third LL packet on every full-size write.
        assertEquals(495, chunkBytes)
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
