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
 */
internal class WriteBuffer {

    private var numFields = 0
    private val scalarFields = mutableMapOf<Int, ByteArray>()
    private val vectorFields = mutableMapOf<Int, ByteArray>()

    // ── Table building ────────────────────────────────────────────────────────

    /** Initialise state for one table with [numFields] field slots (0-based indices). */
    fun startTable(numFields: Int) {
        this.numFields = numFields
        scalarFields.clear()
        vectorFields.clear()
    }

    fun addUByte(fieldIndex: Int, value: UByte) {
        scalarFields[fieldIndex] = byteArrayOf(value.toByte())
    }

    fun addByte(fieldIndex: Int, value: Byte) {
        scalarFields[fieldIndex] = byteArrayOf(value)
    }

    fun addUShort(fieldIndex: Int, value: UShort) {
        scalarFields[fieldIndex] = leUShort(value)
    }

    fun addUInt(fieldIndex: Int, value: UInt) {
        scalarFields[fieldIndex] = leUInt(value)
    }

    fun addULong(fieldIndex: Int, value: ULong) {
        scalarFields[fieldIndex] = leULong(value)
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
            when {
                scalarFields.containsKey(fi) -> {
                    val bytes = scalarFields[fi]!!
                    val align = bytes.size.coerceIn(1, 8)
                    absPos = alignUp(absPos, align)
                    fieldOffsets[fi] = absPos - tableStart
                    absPos += bytes.size
                }
                vectorFields.containsKey(fi) -> {
                    absPos = alignUp(absPos, 4) // uoffset_t is uint32
                    fieldOffsets[fi] = absPos - tableStart
                    absPos += 4
                }
            // else: absent — fieldOffsets[fi] stays 0
            }
        }
        val tableBodyEnd = absPos

        // Place vectors after the table body (each 4-aligned: length + data).
        val vectorPositions = mutableMapOf<Int, Int>()
        var vecPos = tableBodyEnd
        for (fi in vectorFields.keys.sorted()) {
            val data = vectorFields[fi]!!
            vecPos = alignUp(vecPos, 4)
            vectorPositions[fi] = vecPos
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

        // Scalar fields.
        for ((fi, bytes) in scalarFields) {
            bytes.copyInto(buf, tableStart + fieldOffsets[fi])
        }

        // Vector offset fields + vector data.
        for ((fi, data) in vectorFields) {
            val fieldAbsPos = tableStart + fieldOffsets[fi]
            val vecAbsPos = vectorPositions[fi]!!
            writeUInt(buf, fieldAbsPos, (vecAbsPos - fieldAbsPos).toUInt())
            writeUInt(buf, vecAbsPos, data.size.toUInt())
            data.copyInto(buf, vecAbsPos + 4)
        }

        return buf
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun alignUp(pos: Int, align: Int): Int =
        if (pos % align == 0) pos else pos + (align - pos % align)

    private fun leUShort(v: UShort): ByteArray =
        byteArrayOf((v.toInt() and 0xFF).toByte(), ((v.toInt() ushr 8) and 0xFF).toByte())

    private fun leUInt(v: UInt): ByteArray {
        val i = v.toInt()
        return byteArrayOf(
            (i and 0xFF).toByte(),
            ((i ushr 8) and 0xFF).toByte(),
            ((i ushr 16) and 0xFF).toByte(),
            ((i ushr 24) and 0xFF).toByte(),
        )
    }

    private fun leULong(v: ULong): ByteArray {
        val b = ByteArray(8)
        var x = v
        for (i in 0 until 8) {
            b[i] = (x and 0xFFUL).toByte()
            x = x shr 8
        }
        return b
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
}
