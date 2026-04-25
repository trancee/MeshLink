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
}
