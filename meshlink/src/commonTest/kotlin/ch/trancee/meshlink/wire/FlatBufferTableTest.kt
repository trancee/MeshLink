package ch.trancee.meshlink.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FlatBufferTableTest {
    @Test
    fun `builder round-trips scalar string and vector fields`() {
        // Arrange
        val encoded =
            FlatBufferTableBuilder(fieldCount = 5)
                .addByte(fieldIndex = 0, value = 7)
                .addInt(fieldIndex = 1, value = 123_456)
                .addLong(fieldIndex = 2, value = 9_876_543_210L)
                .addString(fieldIndex = 3, value = "meshlink")
                .addByteVector(fieldIndex = 4, value = byteArrayOf(1, 2, 3, 4))
                .finish()

        // Act
        val table = FlatBufferTable.fromRoot(encoded)

        // Assert
        assertEquals(7, table.readByte(0).toInt())
        assertEquals(123_456, table.readInt(1))
        assertEquals(9_876_543_210L, table.readLong(2))
        assertEquals("meshlink", table.readString(3))
        assertContentEquals(byteArrayOf(1, 2, 3, 4), table.readByteVector(4))
    }

    @Test
    fun `reader returns caller defaults for omitted fields`() {
        // Arrange
        val encoded =
            FlatBufferTableBuilder(fieldCount = 2)
                .addByte(fieldIndex = 0, value = 0, defaultValue = 0)
                .addInt(fieldIndex = 1, value = 10, defaultValue = 10)
                .finish()

        // Act
        val table = FlatBufferTable.fromRoot(encoded)

        // Assert
        assertEquals(5, table.readByte(0, defaultValue = 5).toInt())
        assertEquals(99, table.readInt(1, defaultValue = 99))
        assertEquals(123L, table.readLong(2, defaultValue = 123L))
        assertEquals(null, table.readString(3))
        assertEquals(null, table.readByteVector(4))
    }

    @Test
    fun `builder rejects field indexes outside the declared range`() {
        // Arrange
        val builder = FlatBufferTableBuilder(fieldCount = 2)

        // Act
        val failure =
            assertFailsWith<IllegalStateException> { builder.addInt(fieldIndex = 2, value = 1) }

        // Assert
        assertEquals("FlatBuffer field index 2 is outside 0 until 2", failure.message)
    }

    @Test
    fun `table rejects payloads without a root offset`() {
        // Arrange
        val encoded = byteArrayOf(1, 2, 3)

        // Act
        val failure = assertFailsWith<IllegalStateException> { FlatBufferTable.fromRoot(encoded) }

        // Assert
        assertEquals("FlatBuffer root offset is missing", failure.message)
    }

    @Test
    fun `table rejects payloads with an invalid root offset`() {
        // Arrange
        val encoded = ByteArray(4)
        writeIntLittleEndian(encoded, offset = 0, value = 4)

        // Act
        val failure = assertFailsWith<IllegalStateException> { FlatBufferTable.fromRoot(encoded) }

        // Assert
        assertEquals("FlatBuffer root table offset is invalid", failure.message)
    }

    @Test
    fun `table rejects payloads with an invalid vtable distance`() {
        // Arrange
        val encoded =
            FlatBufferTableBuilder(fieldCount = 1).addInt(fieldIndex = 0, value = 7).finish()
        val tableStart = readIntLittleEndian(encoded, offset = 0)
        writeIntLittleEndian(encoded, offset = tableStart, value = 0)

        // Act
        val failure = assertFailsWith<IllegalStateException> { FlatBufferTable.fromRoot(encoded) }

        // Assert
        assertEquals("FlatBuffer table has an invalid vtable offset", failure.message)
    }

    @Test
    fun `reader rejects string fields with invalid relative offsets`() {
        // Arrange
        val encoded =
            FlatBufferTableBuilder(fieldCount = 1)
                .addString(fieldIndex = 0, value = "mesh")
                .finish()
        val tableStart = readIntLittleEndian(encoded, offset = 0)
        val offsetFieldPosition = tableStart + 4
        writeIntLittleEndian(encoded, offset = offsetFieldPosition, value = 0)
        val table = FlatBufferTable.fromRoot(encoded)

        // Act
        val failure = assertFailsWith<IllegalStateException> { table.readString(0) }

        // Assert
        assertEquals("FlatBuffer offset field is invalid", failure.message)
    }

    @Test
    fun `reader rejects vectors with negative lengths`() {
        // Arrange
        val encoded =
            FlatBufferTableBuilder(fieldCount = 1)
                .addByteVector(fieldIndex = 0, value = byteArrayOf(9, 8, 7))
                .finish()
        val tableStart = readIntLittleEndian(encoded, offset = 0)
        val offsetFieldPosition = tableStart + 4
        val targetPosition =
            offsetFieldPosition + readIntLittleEndian(encoded, offset = offsetFieldPosition)
        writeIntLittleEndian(encoded, offset = targetPosition, value = -1)
        val table = FlatBufferTable.fromRoot(encoded)

        // Act
        val failure = assertFailsWith<IllegalStateException> { table.readByteVector(0) }

        // Assert
        assertEquals("FlatBuffer vector length is negative", failure.message)
    }
}

private fun readIntLittleEndian(source: ByteArray, offset: Int): Int {
    var value = 0
    repeat(4) { index ->
        value = value or ((source[offset + index].toInt() and 0xFF) shl (index * 8))
    }
    return value
}

private fun writeIntLittleEndian(target: ByteArray, offset: Int, value: Int) {
    repeat(4) { index -> target[offset + index] = ((value ushr (index * 8)) and 0xFF).toByte() }
}
