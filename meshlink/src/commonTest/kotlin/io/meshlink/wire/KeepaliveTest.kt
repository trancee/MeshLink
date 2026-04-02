package io.meshlink.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KeepaliveTest {

    @Test
    fun encodeProduces10Bytes() {
        val encoded = WireCodec.encodeKeepalive(timestampMillis = 0uL)
        assertEquals(12, encoded.size)
    }

    @Test
    fun encodeHasCorrectTypeAndFlags() {
        val encoded = WireCodec.encodeKeepalive(timestampMillis = 0uL)
        assertEquals(WireCodec.TYPE_KEEPALIVE, encoded[0])
        assertEquals(0.toByte(), encoded[1]) // flags reserved = 0
    }

    @Test
    fun roundTripPreservesTimestamp() {
        val ts = 1_700_000_000_000uL
        val encoded = WireCodec.encodeKeepalive(timestampMillis = ts)
        val decoded = WireCodec.decodeKeepalive(encoded)
        assertEquals(ts, decoded.timestampMillis)
        assertEquals(0u.toUByte(), decoded.flags)
    }

    @Test
    fun roundTripMaxTimestamp() {
        val ts = ULong.MAX_VALUE
        val encoded = WireCodec.encodeKeepalive(timestampMillis = ts)
        val decoded = WireCodec.decodeKeepalive(encoded)
        assertEquals(ts, decoded.timestampMillis)
    }

    @Test
    fun roundTripZeroTimestamp() {
        val encoded = WireCodec.encodeKeepalive(timestampMillis = 0uL)
        val decoded = WireCodec.decodeKeepalive(encoded)
        assertEquals(0uL, decoded.timestampMillis)
    }

    @Test
    fun timestampIsLittleEndian() {
        // 0x00_00_01_8B_CF_E5_68_00 = 1_700_000_000_000
        val ts = 1_700_000_000_000uL
        val encoded = WireCodec.encodeKeepalive(timestampMillis = ts)
        // Bytes 2..9 should be little-endian: 00 68 e5 cf 8b 01 00 00
        assertEquals(0x00.toByte(), encoded[2])
        assertEquals(0x68.toByte(), encoded[3])
        assertEquals(0xE5.toByte(), encoded[4])
        assertEquals(0xCF.toByte(), encoded[5])
        assertEquals(0x8B.toByte(), encoded[6])
        assertEquals(0x01.toByte(), encoded[7])
        assertEquals(0x00.toByte(), encoded[8])
        assertEquals(0x00.toByte(), encoded[9])
    }

    @Test
    fun goldenVector() {
        val ts = 1000uL // 0x3E8
        val expected = byteArrayOf(
            0x01,       // TYPE_KEEPALIVE
            0x00,       // flags
            0xE8.toByte(), 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // timestamp LE ULong
            0x00, 0x00  // empty TLV extensions
        )
        val encoded = WireCodec.encodeKeepalive(timestampMillis = ts)
        assertContentEquals(expected, encoded)

        val decoded = WireCodec.decodeKeepalive(expected)
        assertEquals(0u.toUByte(), decoded.flags)
        assertEquals(ts, decoded.timestampMillis)
    }

    @Test
    fun decodeTooShortThrows() {
        assertFailsWith<IllegalArgumentException> {
            WireCodec.decodeKeepalive(byteArrayOf(0x01, 0x00, 0x01))
        }
    }

    @Test
    fun decodeEmptyThrows() {
        assertFailsWith<IllegalArgumentException> {
            WireCodec.decodeKeepalive(byteArrayOf())
        }
    }

    @Test
    fun decodeWrongTypeThrows() {
        val bad = byteArrayOf(0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00) // TYPE_CHUNK
        assertFailsWith<IllegalArgumentException> {
            WireCodec.decodeKeepalive(bad)
        }
    }

    @Test
    fun decodeExtraBytesAreIgnored() {
        val extended = byteArrayOf(
            0x01, 0x00,
            0xE8.toByte(), 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // 1000 LE ULong
            0x05, 0x00, // TLV extension length = 5
            0x01, 0x02, 0x00, 0xAA.toByte(), 0xBB.toByte() // tag=1, len=2, value=AA BB
        )
        val decoded = WireCodec.decodeKeepalive(extended)
        assertEquals(1000uL, decoded.timestampMillis)
        assertEquals(1, decoded.extensions.size)
    }

    @Test
    fun flagsFieldPreserved() {
        val encoded = WireCodec.encodeKeepalive(timestampMillis = 42_000uL, flags = 0xABu)
        val decoded = WireCodec.decodeKeepalive(encoded)
        assertEquals(0xABu.toUByte(), decoded.flags)
        assertEquals(42_000uL, decoded.timestampMillis)
    }
}
