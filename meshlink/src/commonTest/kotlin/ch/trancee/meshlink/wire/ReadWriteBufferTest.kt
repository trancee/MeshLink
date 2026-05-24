package ch.trancee.meshlink.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReadWriteBufferTest {
    @Test
    fun `write buffer serializes little endian primitives and read buffer restores them`() {
        // Arrange
        val expectedTail = byteArrayOf(9, 8, 7)
        val writeBuffer = WriteBuffer()
        writeBuffer.writeByte(0x7F)
        writeBuffer.writeShortLittleEndian(0x1234.toShort())
        writeBuffer.writeIntLittleEndian(-123_456_789)
        writeBuffer.writeLongLittleEndian(-9_876_543_210_123_456L)
        writeBuffer.writeBytes(expectedTail)

        // Act
        val encoded = writeBuffer.toByteArray()
        val readBuffer = ReadBuffer(encoded)

        // Assert
        assertEquals(0x7F.toByte(), readBuffer.readByte())
        assertEquals(0x1234.toShort(), readBuffer.readShortLittleEndian())
        assertEquals(-123_456_789, readBuffer.readIntLittleEndian())
        assertEquals(-9_876_543_210_123_456L, readBuffer.readLongLittleEndian())
        assertContentEquals(expectedTail, readBuffer.readBytes(expectedTail.size))
        assertEquals(0, readBuffer.remaining())
    }

    @Test
    fun `write buffer grows beyond the initial capacity while preserving written bytes`() {
        // Arrange
        val payload = ByteArray(100) { index -> index.toByte() }
        val writeBuffer = WriteBuffer(initialCapacity = 1)

        // Act
        writeBuffer.writeBytes(payload)
        val encoded = writeBuffer.toByteArray()

        // Assert
        assertEquals(payload.size, encoded.size)
        assertContentEquals(payload, encoded)
    }

    @Test
    fun `read buffer returns slices in order and tracks remaining bytes`() {
        // Arrange
        val readBuffer = ReadBuffer(byteArrayOf(1, 2, 3, 4, 5))

        // Act
        val firstSlice = readBuffer.readBytes(2)
        val middleByte = readBuffer.readByte()
        val secondSlice = readBuffer.readBytes(2)

        // Assert
        assertContentEquals(byteArrayOf(1, 2), firstSlice)
        assertEquals(3, middleByte.toInt())
        assertContentEquals(byteArrayOf(4, 5), secondSlice)
        assertEquals(0, readBuffer.remaining())
    }

    @Test
    fun `read buffer fails when insufficient bytes remain`() {
        // Arrange
        val readBuffer = ReadBuffer(byteArrayOf(1, 2, 3))

        // Act
        val failure = assertFailsWith<IllegalStateException> { readBuffer.readIntLittleEndian() }

        // Assert
        assertEquals("Insufficient bytes in ReadBuffer", failure.message)
    }
}
