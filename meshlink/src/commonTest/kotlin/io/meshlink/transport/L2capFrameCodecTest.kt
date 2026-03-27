package io.meshlink.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class L2capFrameCodecTest {

    // --- encode / decode round-trip ---

    @Test
    fun roundTripDataFrame() {
        val payload = byteArrayOf(0x0A, 0x0B, 0x0C)
        val wire = L2capFrameCodec.encode(L2capFrameCodec.TYPE_DATA, payload)
        val decoded = L2capFrameCodec.decode(wire)

        assertEquals(L2capFrameCodec.TYPE_DATA, decoded.type)
        assertContentEquals(payload, decoded.payload)
    }

    @Test
    fun roundTripAckFrame() {
        val payload = byteArrayOf(0x01)
        val wire = L2capFrameCodec.encode(L2capFrameCodec.TYPE_ACK, payload)
        val decoded = L2capFrameCodec.decode(wire)

        assertEquals(L2capFrameCodec.TYPE_ACK, decoded.type)
        assertContentEquals(payload, decoded.payload)
    }

    @Test
    fun roundTripCloseFrame() {
        val payload = ByteArray(0)
        val wire = L2capFrameCodec.encode(L2capFrameCodec.TYPE_CLOSE, payload)
        val decoded = L2capFrameCodec.decode(wire)

        assertEquals(L2capFrameCodec.TYPE_CLOSE, decoded.type)
        assertTrue(decoded.payload.isEmpty(), "CLOSE payload should be empty")
    }

    @Test
    fun roundTripEmptyPayload() {
        val wire = L2capFrameCodec.encode(L2capFrameCodec.TYPE_DATA, ByteArray(0))
        assertEquals(4, wire.size, "Empty payload → 4-byte header only")
        val decoded = L2capFrameCodec.decode(wire)
        assertEquals(L2capFrameCodec.TYPE_DATA, decoded.type)
        assertTrue(decoded.payload.isEmpty())
    }

    // --- wire format verification ---

    @Test
    fun encodeProducesCorrectHeader() {
        val payload = byteArrayOf(0x41, 0x42) // 2 bytes
        val wire = L2capFrameCodec.encode(L2capFrameCodec.TYPE_ACK, payload)

        assertEquals(6, wire.size, "4-byte header + 2-byte payload")
        assertEquals(L2capFrameCodec.TYPE_ACK, wire[0], "Type byte")
        assertEquals(0x02.toByte(), wire[1], "Length byte 0 (LE)")
        assertEquals(0x00.toByte(), wire[2], "Length byte 1 (LE)")
        assertEquals(0x00.toByte(), wire[3], "Length byte 2 (LE)")
        assertEquals(0x41.toByte(), wire[4], "Payload byte 0")
        assertEquals(0x42.toByte(), wire[5], "Payload byte 1")
    }

    @Test
    fun encodeLargePayloadLengthLittleEndian() {
        // 0x01_02_03 = 66_051 bytes
        val payload = ByteArray(66_051)
        val wire = L2capFrameCodec.encode(L2capFrameCodec.TYPE_DATA, payload)

        assertEquals(0x03.toByte(), wire[1], "LE byte 0")
        assertEquals(0x02.toByte(), wire[2], "LE byte 1")
        assertEquals(0x01.toByte(), wire[3], "LE byte 2")
    }

    // --- decode error handling ---

    @Test
    fun decodeTooShortThrows() {
        assertFailsWith<IllegalArgumentException> {
            L2capFrameCodec.decode(byteArrayOf(0x00, 0x01, 0x02))
        }
    }

    @Test
    fun decodeLengthMismatchThrows() {
        // Header says 5 bytes of payload, but only 1 present
        val bad = byteArrayOf(0x00, 0x05, 0x00, 0x00, 0xFF.toByte())
        assertFailsWith<IllegalArgumentException> {
            L2capFrameCodec.decode(bad)
        }
    }

    @Test
    fun decodeEmptyArrayThrows() {
        assertFailsWith<IllegalArgumentException> {
            L2capFrameCodec.decode(ByteArray(0))
        }
    }

    // --- L2capFrame equality ---

    @Test
    fun frameEqualityByContent() {
        val a = L2capFrame(0x00, byteArrayOf(1, 2, 3))
        val b = L2capFrame(0x00, byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun frameDifferentType() {
        val a = L2capFrame(0x00, byteArrayOf(1))
        val b = L2capFrame(0x01, byteArrayOf(1))
        assertTrue(a != b, "Different type → not equal")
    }

    @Test
    fun frameDifferentPayload() {
        val a = L2capFrame(0x00, byteArrayOf(1))
        val b = L2capFrame(0x00, byteArrayOf(2))
        assertTrue(a != b, "Different payload → not equal")
    }

    // --- encode validation ---

    @Test
    fun encodeMaxPayloadSucceeds() {
        // Boundary: exactly 2^24 - 1 bytes (16 777 215). We can't allocate that
        // much in a unit test, so just verify a moderately large payload round-trips.
        val payload = ByteArray(65_536) { (it % 256).toByte() }
        val decoded = L2capFrameCodec.decode(L2capFrameCodec.encode(L2capFrameCodec.TYPE_DATA, payload))
        assertContentEquals(payload, decoded.payload)
    }

    // --- frame type constants ---

    @Test
    fun frameTypeConstants() {
        assertEquals(0x00.toByte(), L2capFrameCodec.TYPE_DATA)
        assertEquals(0x01.toByte(), L2capFrameCodec.TYPE_ACK)
        assertEquals(0x02.toByte(), L2capFrameCodec.TYPE_CLOSE)
    }
}
