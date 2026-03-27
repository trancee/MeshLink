package io.meshlink.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KeepaliveTest {

    @Test
    fun encodeProduces6Bytes() {
        val encoded = WireCodec.encodeKeepalive(timestampSeconds = 0u)
        assertEquals(6, encoded.size)
    }

    @Test
    fun encodeHasCorrectTypeAndFlags() {
        val encoded = WireCodec.encodeKeepalive(timestampSeconds = 0u)
        assertEquals(WireCodec.TYPE_KEEPALIVE, encoded[0])
        assertEquals(0.toByte(), encoded[1]) // flags reserved = 0
    }

    @Test
    fun roundTripPreservesTimestamp() {
        val ts = 1_700_000_000u
        val encoded = WireCodec.encodeKeepalive(timestampSeconds = ts)
        val decoded = WireCodec.decodeKeepalive(encoded)
        assertEquals(ts, decoded.timestampSeconds)
        assertEquals(0u.toUByte(), decoded.flags)
    }

    @Test
    fun roundTripMaxTimestamp() {
        val ts = UInt.MAX_VALUE
        val encoded = WireCodec.encodeKeepalive(timestampSeconds = ts)
        val decoded = WireCodec.decodeKeepalive(encoded)
        assertEquals(ts, decoded.timestampSeconds)
    }

    @Test
    fun roundTripZeroTimestamp() {
        val encoded = WireCodec.encodeKeepalive(timestampSeconds = 0u)
        val decoded = WireCodec.decodeKeepalive(encoded)
        assertEquals(0u, decoded.timestampSeconds)
    }

    @Test
    fun timestampIsBigEndian() {
        // 0x65_8D_88_00 = 1_703_840_768
        val ts = 0x658D8800u
        val encoded = WireCodec.encodeKeepalive(timestampSeconds = ts)
        // Bytes 2..5 should be big-endian
        assertEquals(0x65.toByte(), encoded[2])
        assertEquals(0x8D.toByte(), encoded[3])
        assertEquals(0x88.toByte(), encoded[4])
        assertEquals(0x00.toByte(), encoded[5])
    }

    @Test
    fun goldenVector() {
        val ts = 0x01020304u
        val expected = byteArrayOf(
            0x08,       // TYPE_KEEPALIVE
            0x00,       // flags
            0x01, 0x02, 0x03, 0x04  // timestamp big-endian
        )
        val encoded = WireCodec.encodeKeepalive(timestampSeconds = ts)
        assertContentEquals(expected, encoded)

        val decoded = WireCodec.decodeKeepalive(expected)
        assertEquals(0u.toUByte(), decoded.flags)
        assertEquals(ts, decoded.timestampSeconds)
    }

    @Test
    fun decodeTooShortThrows() {
        assertFailsWith<IllegalArgumentException> {
            WireCodec.decodeKeepalive(byteArrayOf(0x08, 0x00, 0x01))
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
        val bad = byteArrayOf(0x03, 0x00, 0x00, 0x00, 0x00, 0x00) // TYPE_CHUNK
        assertFailsWith<IllegalArgumentException> {
            WireCodec.decodeKeepalive(bad)
        }
    }

    @Test
    fun decodeExtraBytesAreIgnored() {
        val extended = byteArrayOf(0x08, 0x00, 0x01, 0x02, 0x03, 0x04, 0xFF.toByte())
        val decoded = WireCodec.decodeKeepalive(extended)
        assertEquals(0x01020304u, decoded.timestampSeconds)
    }

    @Test
    fun flagsFieldPreserved() {
        val encoded = WireCodec.encodeKeepalive(timestampSeconds = 42u, flags = 0xABu)
        val decoded = WireCodec.decodeKeepalive(encoded)
        assertEquals(0xABu.toUByte(), decoded.flags)
        assertEquals(42u, decoded.timestampSeconds)
    }
}
