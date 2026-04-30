package ch.trancee.meshlink.wire

/**
 * Minimal FlatBuffers-compatible binary builder for MeshLink wire messages.
 *
 * Usage pattern (one table per [WriteBuffer] instance):
 * ```kotlin
 * val buffer = WriteBuffer()
 * buffer.startTable(numFields = 3)
 * buffer.addUByte(0, flagsValue)
 * buffer.addULong(1, timestampValue)
 * buffer.addUShort(2, chunkSizeValue)
 * val bytes: ByteArray = buffer.finish()
 * ```
 *
 * Fields not added are marked absent (vtable offset 0); [ReadBuffer] returns their defaults.
 * Byte-vector fields (addByteVector) store a 4-byte relative offset; the referenced vector is
 * placed after the inline table body and is length-prefixed (uint32 LE).
 *
 * The produced buffer layout:
 * ```
 * [root_offset: uint32 LE]
 * [vtable: vtable_size + obj_size + field_offsets (uint16 each)]
 * [padding to 4-align the table start]
 * [table: soffset (int32 LE) + inline scalar/offset fields (with natural alignment)]
 * [vectors: length-prefix (uint32 LE) + data, each 4-aligned]
 * ```
 *
 * **Performance:** Scalar fields are stored as (value, size, alignment) tuples and written directly
 * into the output buffer — no intermediate ByteArray allocation per field.
 */
internal class WriteBuffer {

    private var numFields = 0

    // Scalar fields stored as raw Long values + size/alignment metadata.
    // Avoids allocating a ByteArray per scalar field.
    private val scalarValues = LongArray(MAX_FIELDS)
    private val scalarSizes = IntArray(MAX_FIELDS) // 0 = absent
    private val vectorFields = arrayOfNulls<ByteArray>(MAX_FIELDS)

    // ── Table building ────────────────────────────────────────────────────────

    /** Initialise state for one table with [numFields] field slots (0-based indices). */
    fun startTable(numFields: Int) {
        this.numFields = numFields
        scalarSizes.fill(0, 0, numFields)
        for (i in 0 until numFields) vectorFields[i] = null
    }

    fun addUByte(fieldIndex: Int, value: UByte) {
        scalarValues[fieldIndex] = value.toLong()
        scalarSizes[fieldIndex] = 1
    }

    fun addByte(fieldIndex: Int, value: Byte) {
        scalarValues[fieldIndex] = value.toLong()
        scalarSizes[fieldIndex] = 1
    }

    fun addUShort(fieldIndex: Int, value: UShort) {
        scalarValues[fieldIndex] = value.toLong()
        scalarSizes[fieldIndex] = 2
    }

    fun addUInt(fieldIndex: Int, value: UInt) {
        scalarValues[fieldIndex] = value.toLong()
        scalarSizes[fieldIndex] = 4
    }

    fun addULong(fieldIndex: Int, value: ULong) {
        scalarValues[fieldIndex] = value.toLong()
        scalarSizes[fieldIndex] = 8
    }

    /** Stores [data] as a length-prefixed vector; the field holds a relative uoffset_t. */
    fun addByteVector(fieldIndex: Int, data: ByteArray) {
        vectorFields[fieldIndex] = data
    }

    // ── Serialisation ─────────────────────────────────────────────────────────

    /**
     * Produces the complete FlatBuffers-compatible buffer for the current table. May be called only
     * once per [startTable] invocation.
     */
    fun finish(): ByteArray {
        // vtable: 2-byte vtable_size + 2-byte obj_size + 2 bytes per field slot
        val vtableSize = 4 + 2 * numFields
        // Table must start at a 4-byte-aligned absolute offset (soffset is int32).
        val tableStart = alignUp(4 + vtableSize, 4)

        // Compute field offsets (relative to table start = position of soffset).
        // soffset occupies the first 4 bytes of the table; fields follow.
        val fieldOffsets = IntArray(numFields) { 0 }
        var absPos = tableStart + 4 // first byte after soffset

        for (fi in 0 until numFields) {
            val size = scalarSizes[fi]
            when {
                size > 0 -> {
                    val align = size.coerceIn(1, 8)
                    absPos = alignUp(absPos, align)
                    fieldOffsets[fi] = absPos - tableStart
                    absPos += size
                }
                vectorFields[fi] != null -> {
                    absPos = alignUp(absPos, 4) // uoffset_t is uint32
                    fieldOffsets[fi] = absPos - tableStart
                    absPos += 4
                }
            // else: absent — fieldOffsets[fi] stays 0
            }
        }
        val tableBodyEnd = absPos

        // Place vectors after the table body (each 4-aligned: length + data).
        data class VecPos(val fieldIndex: Int, val position: Int)

        val vecPositions = mutableListOf<VecPos>()
        var vecPos = tableBodyEnd
        for (fi in 0 until numFields) {
            val data = vectorFields[fi] ?: continue
            vecPos = alignUp(vecPos, 4)
            vecPositions.add(VecPos(fi, vecPos))
            vecPos += 4 + data.size
        }

        // Allocate and fill the buffer (zero-initialised → gaps/padding are 0x00).
        val buf = ByteArray(vecPos)

        // Root offset (points to table start).
        writeUInt(buf, 0, tableStart.toUInt())

        // Vtable at position 4.
        val vtablePos = 4
        writeUShort(buf, vtablePos, vtableSize.toUShort())
        writeUShort(buf, vtablePos + 2, (tableBodyEnd - tableStart).toUShort())
        for (fi in 0 until numFields) {
            writeUShort(buf, vtablePos + 4 + fi * 2, fieldOffsets[fi].toUShort())
        }

        // soffset at table start (int32 LE): points from table to vtable (negative).
        writeInt(buf, tableStart, vtablePos - tableStart)

        // Scalar fields — write directly from Long values, no intermediate arrays.
        for (fi in 0 until numFields) {
            val size = scalarSizes[fi]
            if (size == 0) continue
            val pos = tableStart + fieldOffsets[fi]
            writeScalar(buf, pos, scalarValues[fi], size)
        }

        // Vector offset fields + vector data.
        for (vp in vecPositions) {
            val fieldAbsPos = tableStart + fieldOffsets[vp.fieldIndex]
            writeUInt(buf, fieldAbsPos, (vp.position - fieldAbsPos).toUInt())
            val data = vectorFields[vp.fieldIndex]!!
            writeUInt(buf, vp.position, data.size.toUInt())
            data.copyInto(buf, vp.position + 4)
        }

        return buf
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun alignUp(pos: Int, align: Int): Int =
        if (pos % align == 0) pos else pos + (align - pos % align)

    /** Writes [size] bytes of [value] in little-endian at [pos] in [buf]. */
    private fun writeScalar(buf: ByteArray, pos: Int, value: Long, size: Int) {
        var v = value
        for (i in 0 until size) {
            buf[pos + i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
    }

    private fun writeUShort(buf: ByteArray, pos: Int, v: UShort) {
        buf[pos] = (v.toInt() and 0xFF).toByte()
        buf[pos + 1] = ((v.toInt() ushr 8) and 0xFF).toByte()
    }

    private fun writeUInt(buf: ByteArray, pos: Int, v: UInt) {
        val i = v.toInt()
        buf[pos] = (i and 0xFF).toByte()
        buf[pos + 1] = ((i ushr 8) and 0xFF).toByte()
        buf[pos + 2] = ((i ushr 16) and 0xFF).toByte()
        buf[pos + 3] = ((i ushr 24) and 0xFF).toByte()
    }

    private fun writeInt(buf: ByteArray, pos: Int, v: Int) = writeUInt(buf, pos, v.toUInt())

    private companion object {
        /** Maximum supported fields per table. Keeps pre-allocated arrays small. */
        const val MAX_FIELDS = 16
    }
}
