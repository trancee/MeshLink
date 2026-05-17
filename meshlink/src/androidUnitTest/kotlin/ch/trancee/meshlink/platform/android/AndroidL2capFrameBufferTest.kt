package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.api.MeshLinkException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidL2capFrameBufferTest {
    @Test
    fun `append reassembles a split frame`() {
        // Arrange
        val buffer = AndroidL2capFrameBuffer()
        val frame = "hello mesh".encodeToByteArray()
        val encoded = buffer.encode(frame)

        // Act
        val firstAppend = buffer.append(encoded.copyOfRange(0, 3))
        val secondAppend = buffer.append(encoded.copyOfRange(3, encoded.size))

        // Assert
        assertEquals(0, firstAppend.size)
        assertEquals(1, secondAppend.size)
        assertContentEquals(frame, secondAppend.single())
    }

    @Test
    fun `append yields multiple frames from one chunk`() {
        // Arrange
        val buffer = AndroidL2capFrameBuffer()
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
    fun `append keeps trailing partial frame for later`() {
        // Arrange
        val buffer = AndroidL2capFrameBuffer()
        val firstFrame = "alpha".encodeToByteArray()
        val secondFrame = "beta".encodeToByteArray()
        val encodedFirst = buffer.encode(firstFrame)
        val encodedSecond = buffer.encode(secondFrame)

        // Act
        val firstDecoded = buffer.append(encodedFirst + encodedSecond.copyOfRange(0, 5))
        val secondDecoded = buffer.append(encodedSecond.copyOfRange(5, encodedSecond.size))

        // Assert
        assertEquals(1, firstDecoded.size)
        assertContentEquals(firstFrame, firstDecoded.single())
        assertEquals(1, secondDecoded.size)
        assertContentEquals(secondFrame, secondDecoded.single())
    }

    @Test
    fun `append rejects oversized declared frames`() {
        // Arrange
        val buffer = AndroidL2capFrameBuffer(maxFrameSizeBytes = 8)
        val oversizedHeader = byteArrayOf(9, 0, 0, 0)

        // Act
        val error =
            assertFailsWith<MeshLinkException.TransportFailure> { buffer.append(oversizedHeader) }

        // Assert
        assertEquals("L2CAP frame exceeds max size 8 bytes", error.message)
    }

    @Test
    fun `append fast path matches appendDetailed for split multi-frame input`() {
        // Arrange
        val fastBuffer = AndroidL2capFrameBuffer()
        val detailedBuffer = AndroidL2capFrameBuffer()
        val firstFrame = "first".encodeToByteArray()
        val secondFrame = "second".encodeToByteArray()
        val encoded = fastBuffer.encode(firstFrame) + fastBuffer.encode(secondFrame)
        val chunks =
            listOf(
                encoded.copyOfRange(0, 4),
                encoded.copyOfRange(4, 9),
                encoded.copyOfRange(9, encoded.size),
            )

        // Act
        val fastDecoded = chunks.flatMap(fastBuffer::append)
        val detailedDecoded = chunks.flatMap { chunk -> detailedBuffer.appendDetailed(chunk).frames }

        // Assert
        assertEquals(2, fastDecoded.size)
        assertEquals(2, detailedDecoded.size)
        assertContentEquals(firstFrame, fastDecoded[0])
        assertContentEquals(secondFrame, fastDecoded[1])
        assertContentEquals(firstFrame, detailedDecoded[0])
        assertContentEquals(secondFrame, detailedDecoded[1])
    }

    @Test
    fun `appendDetailed captures zero length frame context`() {
        // Arrange
        val buffer = AndroidL2capFrameBuffer()
        val zeroLengthHeader = byteArrayOf(0, 0, 0, 0)

        // Act
        val result = buffer.appendDetailed(zeroLengthHeader)

        // Assert
        assertEquals(1, result.frames.size)
        assertContentEquals(byteArrayOf(), result.frames.single())
        assertEquals(1, result.observations.size)
        assertEquals(0, result.bufferedBytesBeforeAppend)
        assertEquals(4, result.appendedChunkBytes)
        assertEquals("00000000", result.appendedChunkPrefixHex)
        assertEquals("00000000", result.appendedChunkSuffixHex)
        assertEquals(0, result.pendingBytesAfterAppend)

        val observation = result.observations.single()
        assertEquals(1, observation.frameIndexInAppend)
        assertEquals(0, observation.frameSizeBytes)
        assertEquals("00000000", observation.headerHex)
        assertEquals(0, observation.readOffsetBeforeFrame)
        assertEquals(4, observation.frameStartOffset)
        assertEquals(4, observation.frameEndOffset)
        assertEquals(0, observation.bufferedBytesBeforeAppend)
        assertEquals(4, observation.totalBufferedBytesAfterAppend)
        assertEquals(0, observation.remainingBufferedBytesAfterFrame)
        assertEquals(false, observation.headerStartsInPreviouslyBufferedBytes)
        assertEquals(true, observation.frameEndsBeyondPreviouslyBufferedBytes)
    }
}
