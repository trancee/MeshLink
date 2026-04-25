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
        val writeBuffer = WriteBuffer()
        writeBuffer.startTable(1)
        writeBuffer.addUByte(0, 0xABu)
        val readBuffer = ReadBuffer(writeBuffer.finish())
        assertEquals(0xABu.toUByte(), readBuffer.getUByte(0))
    }

    @Test
    fun writeAndReadByte() {
        val writeBuffer = WriteBuffer()
        writeBuffer.startTable(1)
        writeBuffer.addByte(0, -42)
        val readBuffer = ReadBuffer(writeBuffer.finish())
        assertEquals((-42).toByte(), readBuffer.getByte(0))
    }

    @Test
    fun writeAndReadUShort() {
        val writeBuffer = WriteBuffer()
        writeBuffer.startTable(1)
        writeBuffer.addUShort(0, 0xCAFEu)
        val readBuffer = ReadBuffer(writeBuffer.finish())
        assertEquals(0xCAFEu.toUShort(), readBuffer.getUShort(0))
    }

    @Test
    fun writeAndReadUInt() {
        val writeBuffer = WriteBuffer()
        writeBuffer.startTable(1)
        writeBuffer.addUInt(0, 0xDEADBEEFu)
        val readBuffer = ReadBuffer(writeBuffer.finish())
        assertEquals(0xDEADBEEFu, readBuffer.getUInt(0))
    }

    @Test
    fun writeAndReadULong() {
        val writeBuffer = WriteBuffer()
        writeBuffer.startTable(1)
        writeBuffer.addULong(0, 0xFEDCBA9876543210UL)
        val readBuffer = ReadBuffer(writeBuffer.finish())
        assertEquals(0xFEDCBA9876543210UL, readBuffer.getULong(0))
    }

    @Test
    fun writeAndReadByteVector() {
        val data = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val writeBuffer = WriteBuffer()
        writeBuffer.startTable(1)
        writeBuffer.addByteVector(0, data)
        val readBuffer = ReadBuffer(writeBuffer.finish())
        assertContentEquals(data, readBuffer.getByteArray(0))
    }

    @Test
    fun writeAndReadEmptyByteVector() {
        val writeBuffer = WriteBuffer()
        writeBuffer.startTable(1)
        writeBuffer.addByteVector(0, ByteArray(0))
        val readBuffer = ReadBuffer(writeBuffer.finish())
        val result = readBuffer.getByteArray(0)
        assertNotNull(result)
        assertEquals(0, result.size)
    }

    /** Absent field returns the declared default. */
    @Test
    fun absentFieldReturnsDefault() {
        val writeBuffer = WriteBuffer()
        writeBuffer.startTable(2)
        writeBuffer.addUByte(0, 5u) // field 1 absent
        val readBuffer = ReadBuffer(writeBuffer.finish())
        assertEquals(5u.toUByte(), readBuffer.getUByte(0))
        assertEquals(0u.toUByte(), readBuffer.getUByte(1)) // default UByte
        assertNull(readBuffer.getByteArray(1)) // absent vector = null
    }

    /** All scalar absent-field defaults: getByte, getUShort, getUInt, getULong. */
    @Test
    fun absentScalarFieldDefaultsForAllTypes() {
        // Only one field declared (field 0) — reading other indices returns defaults.
        val writeBuffer = WriteBuffer()
        writeBuffer.startTable(1)
        writeBuffer.addUByte(0, 0u)
        val readBuffer = ReadBuffer(writeBuffer.finish())
        // All of these are beyond the vtable → absent → default values
        assertEquals(0.toByte(), readBuffer.getByte(1))
        assertEquals(0u.toUShort(), readBuffer.getUShort(2))
        assertEquals(0u, readBuffer.getUInt(3))
        assertEquals(0UL, readBuffer.getULong(4))
    }

    /** Multi-field table with mixed scalars and a vector. */
    @Test
    fun multiFieldTableRoundTrip() {
        val vec = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val writeBuffer = WriteBuffer()
        writeBuffer.startTable(4)
        writeBuffer.addUByte(0, 7u)
        writeBuffer.addULong(1, 123456789UL)
        writeBuffer.addUShort(2, 1000u)
        writeBuffer.addByteVector(3, vec)
        val readBuffer = ReadBuffer(writeBuffer.finish())
        assertEquals(7u.toUByte(), readBuffer.getUByte(0))
        assertEquals(123456789UL, readBuffer.getULong(1))
        assertEquals(1000u.toUShort(), readBuffer.getUShort(2))
        assertContentEquals(vec, readBuffer.getByteArray(3))
    }

    /** Max-value scalars survive round-trip without sign-extension errors. */
    @Test
    fun maxValueScalars() {
        val writeBuffer = WriteBuffer()
        writeBuffer.startTable(4)
        writeBuffer.addUByte(0, UByte.MAX_VALUE)
        writeBuffer.addUShort(1, UShort.MAX_VALUE)
        writeBuffer.addUInt(2, UInt.MAX_VALUE)
        writeBuffer.addULong(3, ULong.MAX_VALUE)
        val readBuffer = ReadBuffer(writeBuffer.finish())
        assertEquals(UByte.MAX_VALUE, readBuffer.getUByte(0))
        assertEquals(UShort.MAX_VALUE, readBuffer.getUShort(1))
        assertEquals(UInt.MAX_VALUE, readBuffer.getUInt(2))
        assertEquals(ULong.MAX_VALUE, readBuffer.getULong(3))
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
        val writeBuffer = WriteBuffer()
        writeBuffer.startTable(1)
        writeBuffer.addUByte(0, 99u)
        val readBuffer = ReadBuffer(writeBuffer.finish())
        assertEquals(0u.toUByte(), readBuffer.getUByte(5)) // beyond vtable — returns default
        assertNull(readBuffer.getByteArray(5))
    }

    // ── ReadBuffer init — boundary error branches ─────────────────────────────

    /** Root offset reads as negative int32 → "Root offset out of bounds". */
    @Test
    fun readBufferNegativeRootOffsetThrows() {
        // 0xFF bytes → root_offset = readIntLE(0) = -1 → < 0 → throws
        val buf = ByteArray(8) { 0xFF.toByte() }
        assertFailsWith<IllegalArgumentException> { ReadBuffer(buf) }
    }

    /** Root offset is non-negative but points past the writeBuffer end → throws. */
    @Test
    fun readBufferRootOffsetPastEndThrows() {
        // root_offset = 8, writeBuffer size = 8 → tableOffset + 4 = 12 > 8 → throws
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

    /** Vtable offset is non-negative but points past the writeBuffer end → throws. */
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
     * Field present but its relative offset points the vector start beyond the writeBuffer end.
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
        val readBuffer = ReadBuffer(buf)
        assertFailsWith<IllegalArgumentException> { readBuffer.getByteArray(0) }
    }

    /**
     * Field present, vector length prefix is in bounds, but the data extends past the writeBuffer
     * end. [ReadBuffer.getByteArray] must throw "Vector data out of bounds".
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
        val readBuffer = ReadBuffer(buf)
        assertFailsWith<IllegalArgumentException> { readBuffer.getByteArray(0) }
    }
}
