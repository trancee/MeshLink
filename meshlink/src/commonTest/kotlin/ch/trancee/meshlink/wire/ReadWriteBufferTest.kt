package ch.trancee.meshlink.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Unit tests for [ReadBuffer] and [WriteBuffer] — pure FlatBuffers binary round-trips. */
class ReadWriteBufferTest {

    /** Build a single-field table and read back each scalar type. */
    @Test
    fun writeAndReadUByte() {
        val wb = WriteBuffer()
        wb.startTable(1)
        wb.addUByte(0, 0xABu)
        val rb = ReadBuffer(wb.finish())
        assertEquals(0xABu.toUByte(), rb.getUByte(0))
    }

    @Test
    fun writeAndReadByte() {
        val wb = WriteBuffer()
        wb.startTable(1)
        wb.addByte(0, -42)
        val rb = ReadBuffer(wb.finish())
        assertEquals((-42).toByte(), rb.getByte(0))
    }

    @Test
    fun writeAndReadUShort() {
        val wb = WriteBuffer()
        wb.startTable(1)
        wb.addUShort(0, 0xCAFEu)
        val rb = ReadBuffer(wb.finish())
        assertEquals(0xCAFEu.toUShort(), rb.getUShort(0))
    }

    @Test
    fun writeAndReadUInt() {
        val wb = WriteBuffer()
        wb.startTable(1)
        wb.addUInt(0, 0xDEADBEEFu)
        val rb = ReadBuffer(wb.finish())
        assertEquals(0xDEADBEEFu, rb.getUInt(0))
    }

    @Test
    fun writeAndReadULong() {
        val wb = WriteBuffer()
        wb.startTable(1)
        wb.addULong(0, 0xFEDCBA9876543210UL)
        val rb = ReadBuffer(wb.finish())
        assertEquals(0xFEDCBA9876543210UL, rb.getULong(0))
    }

    @Test
    fun writeAndReadByteVector() {
        val data = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val wb = WriteBuffer()
        wb.startTable(1)
        wb.addByteVector(0, data)
        val rb = ReadBuffer(wb.finish())
        assertContentEquals(data, rb.getByteArray(0))
    }

    @Test
    fun writeAndReadEmptyByteVector() {
        val wb = WriteBuffer()
        wb.startTable(1)
        wb.addByteVector(0, ByteArray(0))
        val rb = ReadBuffer(wb.finish())
        val result = rb.getByteArray(0)
        assertNotNull(result)
        assertEquals(0, result.size)
    }

    /** Absent field returns the declared default. */
    @Test
    fun absentFieldReturnsDefault() {
        val wb = WriteBuffer()
        wb.startTable(2)
        wb.addUByte(0, 5u) // field 1 absent
        val rb = ReadBuffer(wb.finish())
        assertEquals(5u.toUByte(), rb.getUByte(0))
        assertEquals(0u.toUByte(), rb.getUByte(1)) // default UByte
        assertNull(rb.getByteArray(1)) // absent vector = null
    }

    /** All scalar absent-field defaults: getByte, getUShort, getUInt, getULong. */
    @Test
    fun absentScalarFieldDefaultsForAllTypes() {
        // Only one field declared (field 0) — reading other indices returns defaults.
        val wb = WriteBuffer()
        wb.startTable(1)
        wb.addUByte(0, 0u)
        val rb = ReadBuffer(wb.finish())
        // All of these are beyond the vtable → absent → default values
        assertEquals(0.toByte(), rb.getByte(1))
        assertEquals(0u.toUShort(), rb.getUShort(2))
        assertEquals(0u, rb.getUInt(3))
        assertEquals(0UL, rb.getULong(4))
    }

    /** Multi-field table with mixed scalars and a vector. */
    @Test
    fun multiFieldTableRoundTrip() {
        val vec = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val wb = WriteBuffer()
        wb.startTable(4)
        wb.addUByte(0, 7u)
        wb.addULong(1, 123456789UL)
        wb.addUShort(2, 1000u)
        wb.addByteVector(3, vec)
        val rb = ReadBuffer(wb.finish())
        assertEquals(7u.toUByte(), rb.getUByte(0))
        assertEquals(123456789UL, rb.getULong(1))
        assertEquals(1000u.toUShort(), rb.getUShort(2))
        assertContentEquals(vec, rb.getByteArray(3))
    }

    /** Max-value scalars survive round-trip without sign-extension errors. */
    @Test
    fun maxValueScalars() {
        val wb = WriteBuffer()
        wb.startTable(4)
        wb.addUByte(0, UByte.MAX_VALUE)
        wb.addUShort(1, UShort.MAX_VALUE)
        wb.addUInt(2, UInt.MAX_VALUE)
        wb.addULong(3, ULong.MAX_VALUE)
        val rb = ReadBuffer(wb.finish())
        assertEquals(UByte.MAX_VALUE, rb.getUByte(0))
        assertEquals(UShort.MAX_VALUE, rb.getUShort(1))
        assertEquals(UInt.MAX_VALUE, rb.getUInt(2))
        assertEquals(ULong.MAX_VALUE, rb.getULong(3))
    }

    /** ReadBuffer throws on buffers that are too short to contain a valid root offset. */
    @Test
    fun readBufferThrowsOnTooShortBuffer() {
        assertFailsWith<IllegalArgumentException> { ReadBuffer(ByteArray(3)) }
    }

    /** Field index beyond vtable size is treated as absent (returns default). */
    @Test
    fun fieldIndexBeyondVtableSizeReturnsDefault() {
        // Build a table with only 1 field, then try to read field index 5.
        val wb = WriteBuffer()
        wb.startTable(1)
        wb.addUByte(0, 99u)
        val rb = ReadBuffer(wb.finish())
        assertEquals(0u.toUByte(), rb.getUByte(5)) // beyond vtable — returns default
        assertNull(rb.getByteArray(5))
    }

    // ── ReadBuffer init — boundary error branches ─────────────────────────────

    /** Root offset reads as negative int32 → "Root offset out of bounds". */
    @Test
    fun readBufferNegativeRootOffsetThrows() {
        // 0xFF bytes → root_offset = readIntLE(0) = -1 → < 0 → throws
        val buf = ByteArray(8) { 0xFF.toByte() }
        assertFailsWith<IllegalArgumentException> { ReadBuffer(buf) }
    }

    /** Root offset is non-negative but points past the buffer end → throws. */
    @Test
    fun readBufferRootOffsetPastEndThrows() {
        // root_offset = 8, buffer size = 8 → tableOffset + 4 = 12 > 8 → throws
        val buf = byteArrayOf(8, 0, 0, 0, 0, 0, 0, 0)
        assertFailsWith<IllegalArgumentException> { ReadBuffer(buf) }
    }

    /** Valid root offset but soffset yields negative vtable offset → throws. */
    @Test
    fun readBufferNegativeVtableOffsetThrows() {
        // root_offset = 4 → tableOffset = 4 (valid, 4+4=8 ≤ 16)
        // soffset at pos 4 = -1000 → vtableOffset = 4 + (-1000) = -996 < 0 → throws
        val buf = ByteArray(16)
        // root_offset = 4 at positions 0-3
        buf[0] = 4
        buf[1] = 0
        buf[2] = 0
        buf[3] = 0
        // soffset = -1000 at positions 4-7 (little-endian int32: 0xFFFFFC18)
        val soffset = -1000
        buf[4] = (soffset and 0xFF).toByte()
        buf[5] = ((soffset ushr 8) and 0xFF).toByte()
        buf[6] = ((soffset ushr 16) and 0xFF).toByte()
        buf[7] = ((soffset ushr 24) and 0xFF).toByte()
        assertFailsWith<IllegalArgumentException> { ReadBuffer(buf) }
    }

    /** Vtable offset is non-negative but points past the buffer end → throws. */
    @Test
    fun readBufferVtableOffsetPastEndThrows() {
        // root_offset = 4 → tableOffset = 4 (valid)
        // soffset at pos 4 = +100 → vtableOffset = 104 → 104 + 4 = 108 > 16 → throws
        val buf = ByteArray(16)
        buf[0] = 4
        buf[1] = 0
        buf[2] = 0
        buf[3] = 0
        buf[4] = 100
        buf[5] = 0
        buf[6] = 0
        buf[7] = 0 // soffset = +100
        assertFailsWith<IllegalArgumentException> { ReadBuffer(buf) }
    }

    // ── getByteArray — vector bounds error branches ───────────────────────────

    /**
     * Field present but its relative offset points the vector start beyond the buffer end.
     * [ReadBuffer.getByteArray] must throw "Vector start out of bounds".
     *
     * Buffer layout (payload, 20 bytes): [0–3] root_offset = 12 [4–11] vtable: size=8, obj_size=8,
     * field0_off=4, field1_off=0 [12–15] soffset = -8 (points back to vtable at 4) [16–19] field0
     * relative offset = 1000 → vecStart = 16+1000 = 1016 > 20 → throws
     */
    @Test
    fun getByteArrayVectorStartOutOfBoundsThrows() {
        val buf =
            byteArrayOf(
                12,
                0,
                0,
                0, // root_offset = 12
                8,
                0,
                8,
                0,
                4,
                0,
                0,
                0, // vtable: size=8, obj=8, field0=4, field1=0
                0xF8.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte(), // soffset = -8
                0xE8.toByte(),
                3,
                0,
                0, // field0 rel-offset = 1000 → vecStart far out of bounds
            )
        val rb = ReadBuffer(buf)
        assertFailsWith<IllegalArgumentException> { rb.getByteArray(0) }
    }

    /**
     * Field present, vector length prefix is in bounds, but the data extends past the buffer end.
     * [ReadBuffer.getByteArray] must throw "Vector data out of bounds".
     *
     * Buffer layout (28 bytes): [0–3] root_offset = 12 [4–11] vtable: size=8, obj_size=8,
     * field0_off=4, field1_off=0 [12–15] soffset = -8 [16–19] field0 relative offset = 4 → vecStart
     * = 20 [20–23] vector length = 1000 (length prefix in bounds: 20+4=24 ≤ 28) [24–27] partial
     * data — 1000 bytes expected but only 4 available → throws
     */
    @Test
    fun getByteArrayVectorDataOutOfBoundsThrows() {
        val buf =
            byteArrayOf(
                12,
                0,
                0,
                0, // root_offset = 12
                8,
                0,
                8,
                0,
                4,
                0,
                0,
                0, // vtable
                0xF8.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte(), // soffset = -8
                4,
                0,
                0,
                0, // field0 rel-offset = 4 → vecStart = 16+4 = 20
                0xE8.toByte(),
                3,
                0,
                0, // vector length = 1000 (length prefix at 20-23)
                0,
                0,
                0,
                0, // 4 bytes of data — far fewer than length=1000 → throws
            )
        val rb = ReadBuffer(buf)
        assertFailsWith<IllegalArgumentException> { rb.getByteArray(0) }
    }
}
