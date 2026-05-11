package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.MeshLinkException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IosL2capFrameBufferTest {
    @Test
    fun appendReassemblesSplitFrame(): Unit {
        // Arrange
        val buffer = IosL2capFrameBuffer()
        val frame = "hello ios mesh".encodeToByteArray()
        val encoded = buffer.encode(frame)

        // Act
        val firstAppend = buffer.append(encoded.copyOfRange(0, 5))
        val secondAppend = buffer.append(encoded.copyOfRange(5, encoded.size))

        // Assert
        assertEquals(0, firstAppend.size)
        assertEquals(1, secondAppend.size)
        assertContentEquals(frame, secondAppend.single())
    }

    @Test
    fun appendYieldsMultipleFramesFromOneChunk(): Unit {
        // Arrange
        val buffer = IosL2capFrameBuffer()
        val firstFrame = "first".encodeToByteArray()
        val secondFrame = "second".encodeToByteArray()
        val encoded = buffer.encode(firstFrame) + buffer.encode(secondFrame)

        // Act
        val decoded = buffer.append(encoded)

        // Assert
        assertEquals(2, decoded.size)
        assertContentEquals(firstFrame, decoded[0])
        assertContentEquals(secondFrame, decoded[1])
    }

    @Test
    fun appendRejectsOversizedDeclaredFrames(): Unit {
        // Arrange
        val buffer = IosL2capFrameBuffer(maxFrameSizeBytes = 8)
        val oversizedHeader = byteArrayOf(9, 0, 0, 0)

        // Act
        val error = assertFailsWith<MeshLinkException.TransportFailure> {
            buffer.append(oversizedHeader)
        }

        // Assert
        assertEquals("L2CAP frame exceeds max size 8 bytes", error.message)
    }
}
