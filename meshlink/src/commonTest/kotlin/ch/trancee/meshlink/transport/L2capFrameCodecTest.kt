package ch.trancee.meshlink.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Comprehensive test suite for [L2capFrameCodec], [L2capFrame], [FrameType], and the BLE event
 * types.
 */
class L2capFrameCodecTest {

    // ── Round-trip tests ──────────────────────────────────────────────────────

    @Test
    fun dataFrameRoundTrip() {
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val encoded = L2capFrameCodec.encode(FrameType.DATA, payload)
        val decoded = L2capFrameCodec.decode(encoded)
        assertEquals(FrameType.DATA, decoded.type)
        assertContentEquals(payload, decoded.payload)
    }

    @Test
    fun ackFrameRoundTrip() {
        val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val encoded = L2capFrameCodec.encode(FrameType.ACK, payload)
        val decoded = L2capFrameCodec.decode(encoded)
        assertEquals(FrameType.ACK, decoded.type)
        assertContentEquals(payload, decoded.payload)
    }

    @Test
    fun closeFrameWithEmptyPayload() {
        val encoded = L2capFrameCodec.encode(FrameType.CLOSE, ByteArray(0))
        val decoded = L2capFrameCodec.decode(encoded)
        assertEquals(FrameType.CLOSE, decoded.type)
        assertTrue(decoded.payload.isEmpty())
    }

    // ── FrameType.fromByte coverage ───────────────────────────────────────────

    @Test
    fun fromByteReturnsDataFor0x00() {
        assertEquals(FrameType.DATA, FrameType.fromByte(0x00))
    }

    @Test
    fun fromByteReturnsAckFor0x01() {
        assertEquals(FrameType.ACK, FrameType.fromByte(0x01))
    }

    @Test
    fun fromByteReturnsCloseFor0x02() {
        assertEquals(FrameType.CLOSE, FrameType.fromByte(0x02))
    }

    @Test
    fun fromByteReturnsNullForUnknownByte() {
        assertNull(FrameType.fromByte(0x03))
        assertNull(FrameType.fromByte(0xFF.toByte()))
    }

    // ── Encode validation ─────────────────────────────────────────────────────

    @Test
    fun encodeProducesCorrectHeaderBytes() {
        val payload = byteArrayOf(0x11, 0x22, 0x33)
        val frame = L2capFrameCodec.encode(FrameType.DATA, payload)
        assertEquals(FrameType.DATA.code, frame[0])
        assertEquals(3.toByte(), frame[1]) // little-endian low byte of length
        assertEquals(0.toByte(), frame[2]) // little-endian high byte of length
        assertContentEquals(payload, frame.copyOfRange(3, frame.size))
    }

    @Test
    fun encodeWithMaxSizePayload() {
        val payload = ByteArray(65535) { 0x42 }
        val frame = L2capFrameCodec.encode(FrameType.DATA, payload)
        assertEquals(3 + 65535, frame.size)
        // 65535 = 0xFFFF in little-endian
        assertEquals(0xFF.toByte(), frame[1])
        assertEquals(0xFF.toByte(), frame[2])
    }

    @Test
    fun encodeRejectsOversizedPayload() {
        assertFailsWith<IllegalArgumentException> {
            L2capFrameCodec.encode(FrameType.DATA, ByteArray(65536))
        }
    }

    // ── Decode error paths ────────────────────────────────────────────────────

    @Test
    fun decodeRejectsFrameShorterThan3Bytes() {
        assertFailsWith<IllegalArgumentException> { L2capFrameCodec.decode(ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { L2capFrameCodec.decode(ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> { L2capFrameCodec.decode(ByteArray(2)) }
    }

    @Test
    fun decodeRejectsUnknownFrameType() {
        // type=0xFF, length=0 — valid structure but unknown type
        val frame = byteArrayOf(0xFF.toByte(), 0x00, 0x00)
        assertFailsWith<IllegalArgumentException> { L2capFrameCodec.decode(frame) }
    }

    @Test
    fun decodeRejectsTruncatedPayload() {
        // Header declares length=100 but only 5 bytes of payload follow
        val frame = ByteArray(3 + 5)
        frame[0] = FrameType.DATA.code
        frame[1] = 100.toByte() // low byte
        frame[2] = 0x00 // high byte
        assertFailsWith<IllegalArgumentException> { L2capFrameCodec.decode(frame) }
    }

    // ── L2capFrame equals/hashCode ─────────────────────────────────────────────

    @Test
    fun l2capFrameEqualsAndHashCode() {
        val payload = byteArrayOf(0x01, 0x02)
        val frame = L2capFrame(FrameType.DATA, payload)

        // Identity check (this === other → true)
        assertTrue(frame.equals(frame))

        // Different reference, same content (field equality path)
        val frame2 = L2capFrame(FrameType.DATA, byteArrayOf(0x01, 0x02))
        assertTrue(frame.equals(frame2))
        assertEquals(frame.hashCode(), frame2.hashCode())

        // Wrong type (other !is L2capFrame → false)
        assertFalse(frame.equals("not a frame"))

        // type differs → false (short-circuits payload check)
        assertFalse(frame.equals(L2capFrame(FrameType.ACK, byteArrayOf(0x01, 0x02))))

        // payload differs → false (type matches, contentEquals fails)
        assertFalse(frame.equals(L2capFrame(FrameType.DATA, byteArrayOf(0x03, 0x04))))
    }

    // ── AdvertisementEvent equals/hashCode ────────────────────────────────────

    @Test
    fun advertisementEventEqualsAndHashCode() {
        val peerId = byteArrayOf(0x01)
        val serviceData = byteArrayOf(0x02, 0x03)
        val rssi = -70
        val ae = AdvertisementEvent(peerId, serviceData, rssi)

        // Identity check
        assertTrue(ae.equals(ae))

        // All fields equal (different references)
        val ae2 = AdvertisementEvent(byteArrayOf(0x01), byteArrayOf(0x02, 0x03), -70)
        assertTrue(ae.equals(ae2))
        assertEquals(ae.hashCode(), ae2.hashCode())

        // Wrong type
        assertFalse(ae.equals("x"))

        // peerId differs → false (short-circuits serviceData and rssi)
        assertFalse(ae.equals(AdvertisementEvent(byteArrayOf(0xFF.toByte()), serviceData, rssi)))

        // serviceData differs → false (peerId matches; short-circuits rssi)
        assertFalse(ae.equals(AdvertisementEvent(peerId, byteArrayOf(0xFF.toByte()), rssi)))

        // rssi differs → false (peerId and serviceData match)
        assertFalse(ae.equals(AdvertisementEvent(peerId, serviceData, -99)))
    }

    // ── PeerLostEvent equals/hashCode ─────────────────────────────────────────

    @Test
    fun peerLostEventEqualsAndHashCode() {
        val peerId = byteArrayOf(0x01)
        val reason = PeerLostReason.CONNECTION_LOST
        val ple = PeerLostEvent(peerId, reason)

        // Identity check
        assertTrue(ple.equals(ple))

        // All fields equal (different references)
        val ple2 = PeerLostEvent(byteArrayOf(0x01), PeerLostReason.CONNECTION_LOST)
        assertTrue(ple.equals(ple2))
        assertEquals(ple.hashCode(), ple2.hashCode())

        // Wrong type
        assertFalse(ple.equals(42))

        // peerId differs → false (short-circuits reason)
        assertFalse(ple.equals(PeerLostEvent(byteArrayOf(0xFF.toByte()), reason)))

        // reason differs → false (peerId matches)
        assertFalse(ple.equals(PeerLostEvent(peerId, PeerLostReason.TIMEOUT)))
    }

    // ── IncomingData equals/hashCode ──────────────────────────────────────────

    @Test
    fun incomingDataEqualsAndHashCode() {
        val peerId = byteArrayOf(0x01)
        val data = byteArrayOf(0x02, 0x03)
        val id = IncomingData(peerId, data)

        // Identity check
        assertTrue(id.equals(id))

        // All fields equal (different references)
        val id2 = IncomingData(byteArrayOf(0x01), byteArrayOf(0x02, 0x03))
        assertTrue(id.equals(id2))
        assertEquals(id.hashCode(), id2.hashCode())

        // Wrong type
        assertFalse(id.equals("x"))

        // peerId differs → false (short-circuits data)
        assertFalse(id.equals(IncomingData(byteArrayOf(0xFF.toByte()), data)))

        // data differs → false (peerId matches)
        assertFalse(id.equals(IncomingData(peerId, byteArrayOf(0xFF.toByte()))))
    }

    // ── SendResult equals/hashCode ────────────────────────────────────────────

    @Test
    fun sendResultFailureEqualsAndHashCode() {
        val f = SendResult.Failure("write error")

        // Identity check
        assertTrue(f.equals(f))

        // All fields equal (different reference)
        val f2 = SendResult.Failure("write error")
        assertTrue(f.equals(f2))
        assertEquals(f.hashCode(), f2.hashCode())

        // Wrong type
        assertFalse(f.equals("write error"))

        // reason differs → false
        assertFalse(f.equals(SendResult.Failure("other error")))
    }

    @Test
    fun sendResultSuccessEqualsAndHashCode() {
        // data object — singleton: equals iff same instance
        assertTrue(SendResult.Success.equals(SendResult.Success))
        assertFalse(SendResult.Success.equals(SendResult.Failure("x")))
        assertEquals(SendResult.Success.hashCode(), SendResult.Success.hashCode())
    }

    // ── assertIs smoke test for SendResult sealed hierarchy ──────────────────

    @Test
    fun sendResultSealedHierarchyInstances() {
        assertIs<SendResult>(SendResult.Success)
        assertIs<SendResult>(SendResult.Failure("err"))
    }
}
