package ch.trancee.meshlink.wire

private const val ALIGNMENT_MASK_DELTA: Int = 1
private const val BYTE_MASK: Int = 0xFF
private const val BITS_PER_BYTE: Int = 8
private const val BYTE_SIZE_BYTES: Int = 1
private const val SHORT_SIZE_BYTES: Int = 2
private const val INT_SIZE_BYTES: Int = 4
private const val LONG_SIZE_BYTES: Int = 8
private const val BYTE_ALIGNMENT: Int = BYTE_SIZE_BYTES
private const val INT_ALIGNMENT: Int = INT_SIZE_BYTES
private const val LONG_ALIGNMENT: Int = LONG_SIZE_BYTES
private const val MIN_TABLE_ALIGNMENT: Int = INT_ALIGNMENT
private const val VTABLE_HEADER_SIZE_BYTES: Int = SHORT_SIZE_BYTES * 2
private const val VTABLE_FIELD_ENTRY_SIZE_BYTES: Int = SHORT_SIZE_BYTES
private const val VTABLE_START_OFFSET_BYTES: Int = INT_SIZE_BYTES
private const val STRING_TERMINATOR_SIZE_BYTES: Int = BYTE_SIZE_BYTES
private const val STRING_TERMINATOR_BYTE: Byte = 0

private fun align(value: Int, alignment: Int): Int {
    val mask = alignment - ALIGNMENT_MASK_DELTA
    return (value + mask) and mask.inv()
}

private fun readLittleEndian(source: ByteArray, offset: Int, byteCount: Int): Long {
    var value = 0L
    repeat(byteCount) { index ->
        val byteOffset = offset + index
        val shiftBits = index * BITS_PER_BYTE
        value = value or ((source[byteOffset].toLong() and BYTE_MASK.toLong()) shl shiftBits)
    }
    return value
}

private fun readUnsignedShortLittleEndian(source: ByteArray, offset: Int): Int {
    return readLittleEndian(source = source, offset = offset, byteCount = SHORT_SIZE_BYTES).toInt()
}

private fun readIntLittleEndian(source: ByteArray, offset: Int): Int {
    return readLittleEndian(source = source, offset = offset, byteCount = INT_SIZE_BYTES).toInt()
}

private fun readLongLittleEndian(source: ByteArray, offset: Int): Long {
    return readLittleEndian(source = source, offset = offset, byteCount = LONG_SIZE_BYTES)
}

private fun writeLittleEndian(target: ByteArray, offset: Int, value: Long, byteCount: Int): Unit {
    repeat(byteCount) { index ->
        val byteOffset = offset + index
        val shiftBits = index * BITS_PER_BYTE
        target[byteOffset] = ((value shr shiftBits) and BYTE_MASK.toLong()).toByte()
    }
}

private fun writeShortLittleEndian(target: ByteArray, offset: Int, value: Int): Unit {
    writeLittleEndian(
        target = target,
        offset = offset,
        value = value.toLong(),
        byteCount = SHORT_SIZE_BYTES,
    )
}

private fun writeIntLittleEndian(target: ByteArray, offset: Int, value: Int): Unit {
    writeLittleEndian(
        target = target,
        offset = offset,
        value = value.toLong(),
        byteCount = INT_SIZE_BYTES,
    )
}

private fun writeLongLittleEndian(target: ByteArray, offset: Int, value: Long): Unit {
    writeLittleEndian(target = target, offset = offset, value = value, byteCount = LONG_SIZE_BYTES)
}

private sealed class FlatBufferField(
    internal val index: Int,
    internal val size: Int,
    internal val alignment: Int,
) {
    internal class ByteField internal constructor(index: Int, internal val value: Byte) :
        FlatBufferField(index = index, size = BYTE_SIZE_BYTES, alignment = BYTE_ALIGNMENT)

    internal class IntField internal constructor(index: Int, internal val value: Int) :
        FlatBufferField(index = index, size = INT_SIZE_BYTES, alignment = INT_ALIGNMENT)

    internal class LongField internal constructor(index: Int, internal val value: Long) :
        FlatBufferField(index = index, size = LONG_SIZE_BYTES, alignment = LONG_ALIGNMENT)

    internal class OffsetField
    internal constructor(index: Int, internal val objectBytes: ByteArray) :
        FlatBufferField(index = index, size = INT_SIZE_BYTES, alignment = INT_ALIGNMENT)
}

internal class FlatBufferTableBuilder internal constructor(private val fieldCount: Int) {
    private val fields: MutableMap<Int, FlatBufferField> = linkedMapOf()

    internal fun addByte(
        fieldIndex: Int,
        value: Byte,
        defaultValue: Byte = 0,
    ): FlatBufferTableBuilder {
        validateFieldIndex(fieldIndex)
        if (value != defaultValue) {
            fields[fieldIndex] = FlatBufferField.ByteField(fieldIndex, value)
        }
        return this
    }

    internal fun addInt(
        fieldIndex: Int,
        value: Int,
        defaultValue: Int = 0,
    ): FlatBufferTableBuilder {
        validateFieldIndex(fieldIndex)
        if (value != defaultValue) {
            fields[fieldIndex] = FlatBufferField.IntField(fieldIndex, value)
        }
        return this
    }

    internal fun addLong(
        fieldIndex: Int,
        value: Long,
        defaultValue: Long = 0,
    ): FlatBufferTableBuilder {
        validateFieldIndex(fieldIndex)
        if (value != defaultValue) {
            fields[fieldIndex] = FlatBufferField.LongField(fieldIndex, value)
        }
        return this
    }

    internal fun addString(fieldIndex: Int, value: String): FlatBufferTableBuilder {
        validateFieldIndex(fieldIndex)
        fields[fieldIndex] =
            FlatBufferField.OffsetField(fieldIndex, encodeStringObject(value.encodeToByteArray()))
        return this
    }

    internal fun addByteVector(fieldIndex: Int, value: ByteArray): FlatBufferTableBuilder {
        validateFieldIndex(fieldIndex)
        fields[fieldIndex] = FlatBufferField.OffsetField(fieldIndex, encodeByteVectorObject(value))
        return this
    }

    internal fun finish(): ByteArray {
        val presentFields = fields.values.sortedBy { field -> field.index }
        val maxAlignment =
            presentFields
                .maxOfOrNull { field -> field.alignment }
                ?.coerceAtLeast(MIN_TABLE_ALIGNMENT) ?: MIN_TABLE_ALIGNMENT
        val fieldOffsets = IntArray(fieldCount)

        var objectCursor = INT_SIZE_BYTES
        presentFields.forEach { field ->
            objectCursor = align(objectCursor, field.alignment)
            fieldOffsets[field.index] = objectCursor
            objectCursor += field.size
        }
        val objectSize = align(objectCursor, maxAlignment)

        val vtableSize = VTABLE_HEADER_SIZE_BYTES + (fieldCount * VTABLE_FIELD_ENTRY_SIZE_BYTES)
        val vtableStart = VTABLE_START_OFFSET_BYTES
        val tableStart = align(vtableStart + vtableSize, maxAlignment)

        val offsetFieldStarts: MutableMap<Int, Int> = linkedMapOf()
        var tailCursor = align(tableStart + objectSize, INT_ALIGNMENT)
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
        writeShortLittleEndian(encoded, vtableStart + SHORT_SIZE_BYTES, objectSize)
        repeat(fieldCount) { fieldIndex ->
            val entryOffset =
                vtableStart +
                    VTABLE_HEADER_SIZE_BYTES +
                    (fieldIndex * VTABLE_FIELD_ENTRY_SIZE_BYTES)
            writeShortLittleEndian(encoded, entryOffset, fieldOffsets[fieldIndex])
        }
        writeIntLittleEndian(encoded, tableStart, tableStart - vtableStart)

        presentFields.forEach { field ->
            val fieldPosition = tableStart + fieldOffsets[field.index]
            when (field) {
                is FlatBufferField.ByteField -> encoded[fieldPosition] = field.value
                is FlatBufferField.IntField ->
                    writeIntLittleEndian(encoded, fieldPosition, field.value)
                is FlatBufferField.LongField ->
                    writeLongLittleEndian(encoded, fieldPosition, field.value)
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
        val payloadStart = INT_SIZE_BYTES
        val paddedSize =
            align(payloadStart + value.size + STRING_TERMINATOR_SIZE_BYTES, INT_ALIGNMENT)
        val encoded = ByteArray(paddedSize)
        writeIntLittleEndian(encoded, 0, value.size)
        value.copyInto(encoded, destinationOffset = payloadStart)
        encoded[payloadStart + value.size] = STRING_TERMINATOR_BYTE
        return encoded
    }

    private fun encodeByteVectorObject(value: ByteArray): ByteArray {
        val payloadStart = INT_SIZE_BYTES
        val paddedSize = align(payloadStart + value.size, INT_ALIGNMENT)
        val encoded = ByteArray(paddedSize)
        writeIntLittleEndian(encoded, 0, value.size)
        value.copyInto(encoded, destinationOffset = payloadStart)
        return encoded
    }

    private fun validateFieldIndex(fieldIndex: Int): Unit {
        check(fieldIndex in 0 until fieldCount) {
            "FlatBuffer field index $fieldIndex is outside 0 until $fieldCount"
        }
    }
}

internal class FlatBufferTable
private constructor(private val bytes: ByteArray, private val tableStart: Int) {
    private val vtableStart: Int
    private val vtableSize: Int
    private val objectSize: Int

    init {
        ensureRange(tableStart, INT_SIZE_BYTES, "FlatBuffer table header")
        val vtableDistance = readIntLittleEndian(bytes, tableStart)
        check(vtableDistance > 0) { "FlatBuffer table has an invalid vtable offset" }

        vtableStart = tableStart - vtableDistance
        ensureRange(vtableStart, VTABLE_HEADER_SIZE_BYTES, "FlatBuffer vtable header")
        vtableSize = readUnsignedShortLittleEndian(bytes, vtableStart)
        objectSize = readUnsignedShortLittleEndian(bytes, vtableStart + SHORT_SIZE_BYTES)
        check(vtableSize >= VTABLE_HEADER_SIZE_BYTES) { "FlatBuffer vtable is too small" }

        ensureRange(vtableStart, vtableSize, "FlatBuffer vtable")
        ensureRange(tableStart, objectSize, "FlatBuffer table")
    }

    internal fun readByte(fieldIndex: Int, defaultValue: Byte = 0): Byte {
        val fieldOffset = fieldOffset(fieldIndex)
        if (fieldOffset == 0) {
            return defaultValue
        }
        val fieldPosition = tableStart + fieldOffset
        ensureRange(fieldPosition, BYTE_SIZE_BYTES, "FlatBuffer byte field")
        return bytes[fieldPosition]
    }

    internal fun readInt(fieldIndex: Int, defaultValue: Int = 0): Int {
        val fieldOffset = fieldOffset(fieldIndex)
        if (fieldOffset == 0) {
            return defaultValue
        }
        val fieldPosition = tableStart + fieldOffset
        ensureRange(fieldPosition, INT_SIZE_BYTES, "FlatBuffer int field")
        return readIntLittleEndian(bytes, fieldPosition)
    }

    internal fun readLong(fieldIndex: Int, defaultValue: Long = 0): Long {
        val fieldOffset = fieldOffset(fieldIndex)
        if (fieldOffset == 0) {
            return defaultValue
        }
        val fieldPosition = tableStart + fieldOffset
        ensureRange(fieldPosition, LONG_SIZE_BYTES, "FlatBuffer long field")
        return readLongLittleEndian(bytes, fieldPosition)
    }

    internal fun readString(fieldIndex: Int): String? {
        val targetPosition = targetPosition(fieldIndex) ?: return null
        val length = readIntAt(targetPosition, "FlatBuffer string length")
        check(length >= 0) { "FlatBuffer string length is negative" }

        val payloadStart = targetPosition + INT_SIZE_BYTES
        ensureRange(
            payloadStart,
            length + STRING_TERMINATOR_SIZE_BYTES,
            "FlatBuffer string payload",
        )
        val bytesEnd = payloadStart + length
        return bytes.copyOfRange(payloadStart, bytesEnd).decodeToString()
    }

    internal fun readByteVector(fieldIndex: Int): ByteArray? {
        val targetPosition = targetPosition(fieldIndex) ?: return null
        val length = readIntAt(targetPosition, "FlatBuffer vector length")
        check(length >= 0) { "FlatBuffer vector length is negative" }

        val payloadStart = targetPosition + INT_SIZE_BYTES
        ensureRange(payloadStart, length, "FlatBuffer vector payload")
        val bytesEnd = payloadStart + length
        return bytes.copyOfRange(payloadStart, bytesEnd)
    }

    private fun fieldOffset(fieldIndex: Int): Int {
        check(fieldIndex >= 0) { "FlatBuffer field index must not be negative" }

        val entryPosition =
            vtableStart + VTABLE_HEADER_SIZE_BYTES + (fieldIndex * VTABLE_FIELD_ENTRY_SIZE_BYTES)
        if (entryPosition + VTABLE_FIELD_ENTRY_SIZE_BYTES > vtableStart + vtableSize) {
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
        check(relativeOffset > 0) { "FlatBuffer offset field is invalid" }

        val targetPosition = fieldPosition + relativeOffset
        ensureRange(targetPosition, INT_SIZE_BYTES, "FlatBuffer offset target")
        return targetPosition
    }

    private fun readIntAt(offset: Int, label: String): Int {
        ensureRange(offset, INT_SIZE_BYTES, label)
        return readIntLittleEndian(bytes, offset)
    }

    private fun ensureRange(offset: Int, size: Int, label: String): Unit {
        check(offset >= 0 && size >= 0 && offset + size <= bytes.size) {
            "$label exceeds the available FlatBuffer bytes"
        }
    }

    internal companion object {
        internal fun fromRoot(bytes: ByteArray): FlatBufferTable {
            check(bytes.size >= INT_SIZE_BYTES) { "FlatBuffer root offset is missing" }

            val tableStart = readIntLittleEndian(bytes, 0)
            check(tableStart in INT_SIZE_BYTES until bytes.size) {
                "FlatBuffer root table offset is invalid"
            }
            return FlatBufferTable(bytes = bytes, tableStart = tableStart)
        }
    }
}
