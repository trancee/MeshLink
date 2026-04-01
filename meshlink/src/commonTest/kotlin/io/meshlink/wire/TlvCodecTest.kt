package io.meshlink.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TlvCodecTest {

    @Test
    fun emptyExtensionsEncodesAsTwoZeroBytes() {
        val encoded = TlvCodec.encode(emptyList())
        assertEquals(2, encoded.size)
        assertContentEquals(byteArrayOf(0x00, 0x00), encoded)
    }

    @Test
    fun emptyExtensionsDecodesToEmptyList() {
        val data = byteArrayOf(0x00, 0x00)
        val (entries, consumed) = TlvCodec.decode(data, 0)
        assertTrue(entries.isEmpty())
        assertEquals(2, consumed)
    }

    @Test
    fun singleEntryRoundTrip() {
        val original = listOf(TlvEntry(0x01u, byteArrayOf(0x42, 0x43)))
        val encoded = TlvCodec.encode(original)

        // Expected: [extLen=5 LE][tag=0x01][len=2 LE][0x42, 0x43]
        assertEquals(2 + 3 + 2, encoded.size) // prefix + header + value

        val (decoded, consumed) = TlvCodec.decode(encoded, 0)
        assertEquals(1, decoded.size)
        assertEquals(0x01u.toUByte(), decoded[0].tag)
        assertContentEquals(byteArrayOf(0x42, 0x43), decoded[0].value)
        assertEquals(encoded.size, consumed)
    }

    @Test
    fun multipleEntriesRoundTrip() {
        val original = listOf(
            TlvEntry(0x01u, byteArrayOf(0xAA.toByte())),
            TlvEntry(0x02u, byteArrayOf(0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())),
            TlvEntry(0xFFu, ByteArray(0)) // empty value
        )
        val encoded = TlvCodec.encode(original)
        val (decoded, consumed) = TlvCodec.decode(encoded, 0)

        assertEquals(3, decoded.size)
        assertEquals(0x01u.toUByte(), decoded[0].tag)
        assertContentEquals(byteArrayOf(0xAA.toByte()), decoded[0].value)
        assertEquals(0x02u.toUByte(), decoded[1].tag)
        assertContentEquals(byteArrayOf(0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()), decoded[1].value)
        assertEquals(0xFFu.toUByte(), decoded[2].tag)
        assertEquals(0, decoded[2].value.size)
        assertEquals(encoded.size, consumed)
    }

    @Test
    fun decodeAtNonZeroOffset() {
        val prefix = byteArrayOf(0x99.toByte(), 0x88.toByte(), 0x77.toByte())
        val tlv = TlvCodec.encode(listOf(TlvEntry(0x10u, byteArrayOf(0x42))))
        val combined = prefix + tlv

        val (decoded, consumed) = TlvCodec.decode(combined, prefix.size)
        assertEquals(1, decoded.size)
        assertEquals(0x10u.toUByte(), decoded[0].tag)
        assertContentEquals(byteArrayOf(0x42), decoded[0].value)
        assertEquals(tlv.size, consumed)
    }

    @Test
    fun unknownTagsArePreserved() {
        val entries = listOf(
            TlvEntry(0x01u, byteArrayOf(0x01)),
            TlvEntry(0xFEu, byteArrayOf(0x02, 0x03)), // "unknown" application tag
            TlvEntry(0x7Fu, byteArrayOf(0x04)) // highest protocol-reserved tag
        )
        val encoded = TlvCodec.encode(entries)
        val (decoded, _) = TlvCodec.decode(encoded, 0)

        assertEquals(3, decoded.size)
        assertEquals(0xFEu.toUByte(), decoded[1].tag)
        assertContentEquals(byteArrayOf(0x02, 0x03), decoded[1].value)
    }

    @Test
    fun emptyValueEntryRoundTrips() {
        val original = listOf(TlvEntry(0x42u, ByteArray(0)))
        val encoded = TlvCodec.encode(original)
        val (decoded, _) = TlvCodec.decode(encoded, 0)

        assertEquals(1, decoded.size)
        assertEquals(0x42u.toUByte(), decoded[0].tag)
        assertEquals(0, decoded[0].value.size)
    }

    @Test
    fun largeValueRoundTrips() {
        val largeValue = ByteArray(1000) { it.toByte() }
        val original = listOf(TlvEntry(0x01u, largeValue))
        val encoded = TlvCodec.encode(original)
        val (decoded, _) = TlvCodec.decode(encoded, 0)

        assertEquals(1, decoded.size)
        assertContentEquals(largeValue, decoded[0].value)
    }

    @Test
    fun wireSizeMatchesEncodeLength() {
        val entries = listOf(
            TlvEntry(0x01u, byteArrayOf(0x01, 0x02)),
            TlvEntry(0x02u, byteArrayOf(0x03))
        )
        assertEquals(TlvCodec.encode(entries).size, TlvCodec.wireSize(entries))
    }

    @Test
    fun wireSizeEmptyIsTwo() {
        assertEquals(2, TlvCodec.wireSize(emptyList()))
    }

    @Test
    fun truncatedExtensionLengthThrows() {
        assertFailsWith<IllegalArgumentException> {
            TlvCodec.decode(byteArrayOf(0x00), 0)
        }
    }

    @Test
    fun truncatedExtensionBodyThrows() {
        // Declare 10 bytes of extensions but provide only 2
        val data = byteArrayOf(0x0A, 0x00, 0x01, 0x02)
        assertFailsWith<IllegalArgumentException> {
            TlvCodec.decode(data, 0)
        }
    }

    @Test
    fun truncatedEntryHeaderThrows() {
        // Extension length = 2 (only tag + 1 byte of length, missing second length byte)
        val data = byteArrayOf(0x02, 0x00, 0x01, 0x05)
        assertFailsWith<IllegalArgumentException> {
            TlvCodec.decode(data, 0)
        }
    }

    @Test
    fun truncatedEntryValueThrows() {
        // Extension length = 4, tag=0x01, value len=5, but only 1 byte of value
        val data = byteArrayOf(0x04, 0x00, 0x01, 0x05, 0x00, 0x42)
        assertFailsWith<IllegalArgumentException> {
            TlvCodec.decode(data, 0)
        }
    }

    @Test
    fun goldenVectorSingleEntry() {
        // tag=0x01, value=[0xDE, 0xAD]
        // Expected wire: [extLen=5 LE: 05 00][tag: 01][len=2 LE: 02 00][DE AD]
        val expected = byteArrayOf(0x05, 0x00, 0x01, 0x02, 0x00, 0xDE.toByte(), 0xAD.toByte())
        val entries = listOf(TlvEntry(0x01u, byteArrayOf(0xDE.toByte(), 0xAD.toByte())))
        assertContentEquals(expected, TlvCodec.encode(entries))
    }

    @Test
    fun goldenVectorEmpty() {
        val expected = byteArrayOf(0x00, 0x00)
        assertContentEquals(expected, TlvCodec.encode(emptyList()))
    }

    @Test
    fun tlvEntryEquality() {
        val a = TlvEntry(0x01u, byteArrayOf(0x42))
        val b = TlvEntry(0x01u, byteArrayOf(0x42))
        val c = TlvEntry(0x01u, byteArrayOf(0x43))
        val d = TlvEntry(0x02u, byteArrayOf(0x42))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != c) // different value
        assertTrue(a != d) // different tag
    }

    @Test
    fun maxUShortExtensionLength() {
        // 65535 bytes is the max extension body size (UShort max)
        val bigValue = ByteArray(65532) // 65535 - 3 (header) = 65532
        val entries = listOf(TlvEntry(0x01u, bigValue))
        val encoded = TlvCodec.encode(entries)
        val (decoded, _) = TlvCodec.decode(encoded, 0)
        assertEquals(1, decoded.size)
        assertEquals(bigValue.size, decoded[0].value.size)
    }
}
