package ch.trancee.meshlink.wire

/**
 * Minimal FlatBuffers-compatible binary reader for MeshLink wire messages.
 *
 * Format consumed (produced by [WriteBuffer]):
 * ```
 * [root_offset: uint32 LE]           // offset from byte 0 to the table object
 * [vtable: vtable_size + obj_size + field_offsets (uint16 each)]
 * [optional padding to 4-byte-align the table]
 * [table: soffset (int32 LE) + inline field values]
 * [vectors: length-prefixed byte arrays]
 * ```
 *
 * All integers are little-endian. Field indices are 0-based. Missing optional fields return their
 * declared default values.
 */
class ReadBuffer(private val bb: ByteArray) {

    private val tableOffset: Int
    private val vtableOffset: Int

    init {
        if (bb.size < 4) throw IllegalArgumentException("Buffer too short for root offset")
        tableOffset = readIntLE(0)
        if (tableOffset < 0 || tableOffset + 4 > bb.size) {
            throw IllegalArgumentException("Root offset out of bounds")
        }
        val soffset = readIntLE(tableOffset)
        vtableOffset = tableOffset + soffset
        if (vtableOffset < 0 || vtableOffset + 4 > bb.size) {
            throw IllegalArgumentException("Vtable offset out of bounds")
        }
    }

    // ── Field position resolution ─────────────────────────────────────────────

    /**
     * Returns the ABSOLUTE position of field [fieldIndex] in [bb], or 0 if absent. Absent means:
     * vtable is shorter than required, or the stored offset is 0.
     */
    private fun fieldPosition(fieldIndex: Int): Int {
        val vtableSize = readUShortLE(vtableOffset).toInt()
        val slotPos = vtableOffset + 4 + fieldIndex * 2
        if (slotPos + 2 > vtableOffset + vtableSize) return 0
        val offset = readUShortLE(slotPos).toInt()
        return if (offset == 0) 0 else tableOffset + offset
    }

    // ── Scalar accessors ──────────────────────────────────────────────────────

    fun getUByte(fieldIndex: Int, default: UByte = 0u): UByte {
        val pos = fieldPosition(fieldIndex)
        return if (pos == 0) default else bb[pos].toUByte()
    }

    fun getByte(fieldIndex: Int, default: Byte = 0): Byte {
        val pos = fieldPosition(fieldIndex)
        return if (pos == 0) default else bb[pos]
    }

    fun getUShort(fieldIndex: Int, default: UShort = 0u): UShort {
        val pos = fieldPosition(fieldIndex)
        return if (pos == 0) default else readUShortLE(pos)
    }

    fun getUInt(fieldIndex: Int, default: UInt = 0u): UInt {
        val pos = fieldPosition(fieldIndex)
        return if (pos == 0) default else readUIntLE(pos)
    }

    fun getULong(fieldIndex: Int, default: ULong = 0u): ULong {
        val pos = fieldPosition(fieldIndex)
        return if (pos == 0) default else readULongLE(pos)
    }

    // ── Vector accessor ───────────────────────────────────────────────────────

    /**
     * Returns the byte vector at [fieldIndex], or `null` if the field is absent. An empty vector
     * (length 0) returns an empty [ByteArray], not null.
     */
    fun getByteArray(fieldIndex: Int): ByteArray? {
        val pos = fieldPosition(fieldIndex)
        if (pos == 0) return null
        // Field stores a uoffset_t (uint32 LE) relative to the field position.
        val relOffset = readUIntLE(pos).toInt()
        val vecStart = pos + relOffset
        if (vecStart + 4 > bb.size) throw IllegalArgumentException("Vector start out of bounds")
        val length = readUIntLE(vecStart).toInt()
        if (vecStart + 4 + length > bb.size) {
            throw IllegalArgumentException("Vector data out of bounds")
        }
        return bb.copyOfRange(vecStart + 4, vecStart + 4 + length)
    }

    // ── Little-endian primitives ──────────────────────────────────────────────

    private fun readIntLE(pos: Int): Int =
        (bb[pos].toInt() and 0xFF) or
            ((bb[pos + 1].toInt() and 0xFF) shl 8) or
            ((bb[pos + 2].toInt() and 0xFF) shl 16) or
            ((bb[pos + 3].toInt() and 0xFF) shl 24)

    private fun readUShortLE(pos: Int): UShort =
        ((bb[pos].toInt() and 0xFF) or ((bb[pos + 1].toInt() and 0xFF) shl 8)).toUShort()

    private fun readUIntLE(pos: Int): UInt = readIntLE(pos).toUInt()

    private fun readULongLE(pos: Int): ULong {
        var result = 0UL
        for (i in 0 until 8) {
            result = result or ((bb[pos + i].toULong() and 0xFFUL) shl (i * 8))
        }
        return result
    }
}
