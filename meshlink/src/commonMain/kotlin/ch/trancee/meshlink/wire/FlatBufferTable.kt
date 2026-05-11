package ch.trancee.meshlink.wire

private fun align(value: Int, alignment: Int): Int {
    val mask = alignment - 1
    return (value + mask) and mask.inv()
}

private fun readUnsignedShortLittleEndian(source: ByteArray, offset: Int): Int {
    return (source[offset].toInt() and 0xFF) or ((source[offset + 1].toInt() and 0xFF) shl 8)
}

private fun readIntLittleEndian(source: ByteArray, offset: Int): Int {
    return (source[offset].toInt() and 0xFF) or
        ((source[offset + 1].toInt() and 0xFF) shl 8) or
        ((source[offset + 2].toInt() and 0xFF) shl 16) or
        ((source[offset + 3].toInt() and 0xFF) shl 24)
}

private fun readLongLittleEndian(source: ByteArray, offset: Int): Long {
    var value = 0L
    repeat(8) { index ->
        value = value or ((source[offset + index].toLong() and 0xFF) shl (index * 8))
    }
    return value
}

private fun writeShortLittleEndian(target: ByteArray, offset: Int, value: Int): Unit {
    target[offset] = (value and 0xFF).toByte()
    target[offset + 1] = ((value shr 8) and 0xFF).toByte()
}

private fun writeIntLittleEndian(target: ByteArray, offset: Int, value: Int): Unit {
    target[offset] = (value and 0xFF).toByte()
    target[offset + 1] = ((value shr 8) and 0xFF).toByte()
    target[offset + 2] = ((value shr 16) and 0xFF).toByte()
    target[offset + 3] = ((value shr 24) and 0xFF).toByte()
}

private fun writeLongLittleEndian(target: ByteArray, offset: Int, value: Long): Unit {
    repeat(8) { index ->
        target[offset + index] = ((value shr (index * 8)) and 0xFF).toByte()
    }
}

private sealed class FlatBufferField(
    internal val index: Int,
    internal val size: Int,
    internal val alignment: Int,
) {
    internal class ByteField internal constructor(
        index: Int,
        internal val value: Byte,
    ) : FlatBufferField(index = index, size = 1, alignment = 1)

    internal class IntField internal constructor(
        index: Int,
        internal val value: Int,
    ) : FlatBufferField(index = index, size = 4, alignment = 4)

    internal class LongField internal constructor(
        index: Int,
        internal val value: Long,
    ) : FlatBufferField(index = index, size = 8, alignment = 8)

    internal class OffsetField internal constructor(
        index: Int,
        internal val objectBytes: ByteArray,
    ) : FlatBufferField(index = index, size = 4, alignment = 4)
}

internal class FlatBufferTableBuilder internal constructor(
    private val fieldCount: Int,
) {
    private val fields: MutableMap<Int, FlatBufferField> = linkedMapOf()

    internal fun addByte(fieldIndex: Int, value: Byte, defaultValue: Byte = 0): FlatBufferTableBuilder {
        validateFieldIndex(fieldIndex)
        if (value != defaultValue) {
            fields[fieldIndex] = FlatBufferField.ByteField(fieldIndex, value)
        }
        return this
    }

    internal fun addInt(fieldIndex: Int, value: Int, defaultValue: Int = 0): FlatBufferTableBuilder {
        validateFieldIndex(fieldIndex)
        if (value != defaultValue) {
            fields[fieldIndex] = FlatBufferField.IntField(fieldIndex, value)
        }
        return this
    }

    internal fun addLong(fieldIndex: Int, value: Long, defaultValue: Long = 0): FlatBufferTableBuilder {
        validateFieldIndex(fieldIndex)
        if (value != defaultValue) {
            fields[fieldIndex] = FlatBufferField.LongField(fieldIndex, value)
        }
        return this
    }

    internal fun addString(fieldIndex: Int, value: String): FlatBufferTableBuilder {
        validateFieldIndex(fieldIndex)
        fields[fieldIndex] = FlatBufferField.OffsetField(fieldIndex, encodeStringObject(value.encodeToByteArray()))
        return this
    }

    internal fun addByteVector(fieldIndex: Int, value: ByteArray): FlatBufferTableBuilder {
        validateFieldIndex(fieldIndex)
        fields[fieldIndex] = FlatBufferField.OffsetField(fieldIndex, encodeByteVectorObject(value))
        return this
    }

    internal fun finish(): ByteArray {
        val presentFields = fields.values.sortedBy { field -> field.index }
        val maxAlignment = presentFields.maxOfOrNull { field -> field.alignment }?.coerceAtLeast(4) ?: 4
        val fieldOffsets = IntArray(fieldCount)

        var objectCursor = 4
        presentFields.forEach { field ->
            objectCursor = align(objectCursor, field.alignment)
            fieldOffsets[field.index] = objectCursor
            objectCursor += field.size
        }
        val objectSize = align(objectCursor, maxAlignment)

        val vtableSize = 4 + (fieldCount * 2)
        val vtableStart = 4
        val tableStart = align(vtableStart + vtableSize, maxAlignment)

        val offsetFieldStarts: MutableMap<Int, Int> = linkedMapOf()
        var tailCursor = align(tableStart + objectSize, 4)
        presentFields.forEach { field ->
            if (field is FlatBufferField.OffsetField) {
                tailCursor = align(tailCursor, field.alignment)
                offsetFieldStarts[field.index] = tailCursor
                tailCursor += field.objectBytes.size
            }
        }

        val encoded = ByteArray(tailCursor)
        writeIntLittleEndian(encoded, 0, tableStart)
        writeShortLittleEndian(encoded, vtableStart, vtableSize)
        writeShortLittleEndian(encoded, vtableStart + 2, objectSize)
        repeat(fieldCount) { fieldIndex ->
            writeShortLittleEndian(encoded, vtableStart + 4 + (fieldIndex * 2), fieldOffsets[fieldIndex])
        }
        writeIntLittleEndian(encoded, tableStart, tableStart - vtableStart)

        presentFields.forEach { field ->
            val fieldPosition = tableStart + fieldOffsets[field.index]
            when (field) {
                is FlatBufferField.ByteField -> encoded[fieldPosition] = field.value
                is FlatBufferField.IntField -> writeIntLittleEndian(encoded, fieldPosition, field.value)
                is FlatBufferField.LongField -> writeLongLittleEndian(encoded, fieldPosition, field.value)
                is FlatBufferField.OffsetField -> {
                    val objectStart = offsetFieldStarts.getValue(field.index)
                    writeIntLittleEndian(encoded, fieldPosition, objectStart - fieldPosition)
                    field.objectBytes.copyInto(encoded, destinationOffset = objectStart)
                }
            }
        }

        return encoded
    }

    private fun encodeStringObject(value: ByteArray): ByteArray {
        val paddedSize = align(4 + value.size + 1, 4)
        val encoded = ByteArray(paddedSize)
        writeIntLittleEndian(encoded, 0, value.size)
        value.copyInto(encoded, destinationOffset = 4)
        encoded[4 + value.size] = 0
        return encoded
    }

    private fun encodeByteVectorObject(value: ByteArray): ByteArray {
        val paddedSize = align(4 + value.size, 4)
        val encoded = ByteArray(paddedSize)
        writeIntLittleEndian(encoded, 0, value.size)
        value.copyInto(encoded, destinationOffset = 4)
        return encoded
    }

    private fun validateFieldIndex(fieldIndex: Int): Unit {
        if (fieldIndex !in 0 until fieldCount) {
            throw IllegalStateException("FlatBuffer field index $fieldIndex is outside 0 until $fieldCount")
        }
    }
}

internal class FlatBufferTable private constructor(
    private val bytes: ByteArray,
    private val tableStart: Int,
) {
    private val vtableStart: Int
    private val vtableSize: Int
    private val objectSize: Int

    init {
        ensureRange(tableStart, 4, "FlatBuffer table header")
        val vtableDistance = readIntLittleEndian(bytes, tableStart)
        if (vtableDistance <= 0) {
            throw IllegalStateException("FlatBuffer table has an invalid vtable offset")
        }
        vtableStart = tableStart - vtableDistance
        ensureRange(vtableStart, 4, "FlatBuffer vtable header")
        vtableSize = readUnsignedShortLittleEndian(bytes, vtableStart)
        objectSize = readUnsignedShortLittleEndian(bytes, vtableStart + 2)
        if (vtableSize < 4) {
            throw IllegalStateException("FlatBuffer vtable is too small")
        }
        ensureRange(vtableStart, vtableSize, "FlatBuffer vtable")
        ensureRange(tableStart, objectSize, "FlatBuffer table")
    }

    internal fun readByte(fieldIndex: Int, defaultValue: Byte = 0): Byte {
        val fieldOffset = fieldOffset(fieldIndex)
        if (fieldOffset == 0) {
            return defaultValue
        }
        val fieldPosition = tableStart + fieldOffset
        ensureRange(fieldPosition, 1, "FlatBuffer byte field")
        return bytes[fieldPosition]
    }

    internal fun readInt(fieldIndex: Int, defaultValue: Int = 0): Int {
        val fieldOffset = fieldOffset(fieldIndex)
        if (fieldOffset == 0) {
            return defaultValue
        }
        val fieldPosition = tableStart + fieldOffset
        ensureRange(fieldPosition, 4, "FlatBuffer int field")
        return readIntLittleEndian(bytes, fieldPosition)
    }

    internal fun readLong(fieldIndex: Int, defaultValue: Long = 0): Long {
        val fieldOffset = fieldOffset(fieldIndex)
        if (fieldOffset == 0) {
            return defaultValue
        }
        val fieldPosition = tableStart + fieldOffset
        ensureRange(fieldPosition, 8, "FlatBuffer long field")
        return readLongLittleEndian(bytes, fieldPosition)
    }

    internal fun readString(fieldIndex: Int): String? {
        val targetPosition = targetPosition(fieldIndex) ?: return null
        val length = readIntAt(targetPosition, "FlatBuffer string length")
        if (length < 0) {
            throw IllegalStateException("FlatBuffer string length is negative")
        }
        ensureRange(targetPosition + 4, length + 1, "FlatBuffer string payload")
        val bytesStart = targetPosition + 4
        val bytesEnd = bytesStart + length
        return bytes.copyOfRange(bytesStart, bytesEnd).decodeToString()
    }

    internal fun readByteVector(fieldIndex: Int): ByteArray? {
        val targetPosition = targetPosition(fieldIndex) ?: return null
        val length = readIntAt(targetPosition, "FlatBuffer vector length")
        if (length < 0) {
            throw IllegalStateException("FlatBuffer vector length is negative")
        }
        ensureRange(targetPosition + 4, length, "FlatBuffer vector payload")
        val bytesStart = targetPosition + 4
        val bytesEnd = bytesStart + length
        return bytes.copyOfRange(bytesStart, bytesEnd)
    }

    private fun fieldOffset(fieldIndex: Int): Int {
        if (fieldIndex < 0) {
            throw IllegalStateException("FlatBuffer field index must not be negative")
        }
        val entryPosition = vtableStart + 4 + (fieldIndex * 2)
        if (entryPosition + 2 > vtableStart + vtableSize) {
            return 0
        }
        return readUnsignedShortLittleEndian(bytes, entryPosition)
    }

    private fun targetPosition(fieldIndex: Int): Int? {
        val fieldOffset = fieldOffset(fieldIndex)
        if (fieldOffset == 0) {
            return null
        }
        val fieldPosition = tableStart + fieldOffset
        val relativeOffset = readIntAt(fieldPosition, "FlatBuffer offset field")
        if (relativeOffset <= 0) {
            throw IllegalStateException("FlatBuffer offset field is invalid")
        }
        val targetPosition = fieldPosition + relativeOffset
        ensureRange(targetPosition, 4, "FlatBuffer offset target")
        return targetPosition
    }

    private fun readIntAt(offset: Int, label: String): Int {
        ensureRange(offset, 4, label)
        return readIntLittleEndian(bytes, offset)
    }

    private fun ensureRange(offset: Int, size: Int, label: String): Unit {
        if (offset < 0 || size < 0 || offset + size > bytes.size) {
            throw IllegalStateException("$label exceeds the available FlatBuffer bytes")
        }
    }

    internal companion object {
        internal fun fromRoot(bytes: ByteArray): FlatBufferTable {
            if (bytes.size < 4) {
                throw IllegalStateException("FlatBuffer root offset is missing")
            }
            val tableStart = readIntLittleEndian(bytes, 0)
            if (tableStart < 4 || tableStart >= bytes.size) {
                throw IllegalStateException("FlatBuffer root table offset is invalid")
            }
            return FlatBufferTable(bytes = bytes, tableStart = tableStart)
        }
    }
}
