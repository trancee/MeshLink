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
        // Arrange
        val payload = byteArrayOf(0x01, 0x02, 0x03)

        // Act
        val encoded = L2capFrameCodec.encode(FrameType.DATA, payload)
        val decoded = L2capFrameCodec.decode(encoded)

        // Assert
        assertEquals(FrameType.DATA, decoded.type)
        assertContentEquals(payload, decoded.payload)
    }

    @Test
    fun ackFrameRoundTrip() {
        // Arrange
        val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte())

        // Act
        val encoded = L2capFrameCodec.encode(FrameType.ACK, payload)
        val decoded = L2capFrameCodec.decode(encoded)

        // Assert
        assertEquals(FrameType.ACK, decoded.type)
        assertContentEquals(payload, decoded.payload)
    }

    @Test
    fun closeFrameWithEmptyPayload() {
        // Arrange
        val payload = ByteArray(0)

        // Act
        val encoded = L2capFrameCodec.encode(FrameType.CLOSE, payload)
        val decoded = L2capFrameCodec.decode(encoded)

        // Assert
        assertEquals(FrameType.CLOSE, decoded.type)
        assertTrue(decoded.payload.isEmpty())
    }

    // ── FrameType.fromByte coverage ───────────────────────────────────────────

    @Test
    fun fromByteReturnsDataFor0x00() {
        // Act
        val result = FrameType.fromByte(0x00)

        // Assert
        assertEquals(FrameType.DATA, result)
    }

    @Test
    fun fromByteReturnsAckFor0x01() {
        // Act
        val result = FrameType.fromByte(0x01)

        // Assert
        assertEquals(FrameType.ACK, result)
    }

    @Test
    fun fromByteReturnsCloseFor0x02() {
        // Act
        val result = FrameType.fromByte(0x02)

        // Assert
        assertEquals(FrameType.CLOSE, result)
    }

    @Test
    fun fromByteReturnsNullForUnknownByte() {
        // Act & Assert
        assertNull(FrameType.fromByte(0x03))
        assertNull(FrameType.fromByte(0xFF.toByte()))
    }

    // ── Encode validation ─────────────────────────────────────────────────────

    @Test
    fun encodeProducesCorrectHeaderBytes() {
        // Arrange
        val payload = byteArrayOf(0x11, 0x22, 0x33)

        // Act
        val frame = L2capFrameCodec.encode(FrameType.DATA, payload)

        // Assert
        assertEquals(FrameType.DATA.code, frame[0])
        assertEquals(3.toByte(), frame[1]) // little-endian low byte of length
        assertEquals(0.toByte(), frame[2]) // little-endian high byte of length
        assertContentEquals(payload, frame.copyOfRange(3, frame.size))
    }

    @Test
    fun encodeWithMaxSizePayload() {
        // Arrange
        val payload = ByteArray(65535) { 0x42 }

        // Act
        val frame = L2capFrameCodec.encode(FrameType.DATA, payload)

        // Assert
        assertEquals(3 + 65535, frame.size)
        assertEquals(0xFF.toByte(), frame[1]) // 65535 = 0xFFFF little-endian
        assertEquals(0xFF.toByte(), frame[2])
    }

    @Test
    fun encodeRejectsOversizedPayload() {
        // Arrange
        val oversizedPayload = ByteArray(65536)

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            L2capFrameCodec.encode(FrameType.DATA, oversizedPayload)
        }
    }

    // ── Decode error paths ────────────────────────────────────────────────────

    @Test
    fun decodeRejectsEmptyFrame() {
        // Act & Assert
        assertFailsWith<IllegalArgumentException> { L2capFrameCodec.decode(ByteArray(0)) }
    }

    @Test
    fun decodeRejectsOneByteFrame() {
        // Act & Assert
        assertFailsWith<IllegalArgumentException> { L2capFrameCodec.decode(ByteArray(1)) }
    }

    @Test
    fun decodeRejectsTwoByteFrame() {
        // Act & Assert
        assertFailsWith<IllegalArgumentException> { L2capFrameCodec.decode(ByteArray(2)) }
    }

    @Test
    fun decodeRejectsUnknownFrameType() {
        // Arrange — type=0xFF, length=0 — valid structure but unknown type
        val frame = byteArrayOf(0xFF.toByte(), 0x00, 0x00)

        // Act & Assert
        assertFailsWith<IllegalArgumentException> { L2capFrameCodec.decode(frame) }
    }

    @Test
    fun decodeRejectsTruncatedPayload() {
        // Arrange — header declares length=100 but only 5 bytes of payload follow
        val frame = ByteArray(3 + 5)
        frame[0] = FrameType.DATA.code
        frame[1] = 100.toByte()
        frame[2] = 0x00

        // Act & Assert
        assertFailsWith<IllegalArgumentException> { L2capFrameCodec.decode(frame) }
    }

    // ── L2capFrame equals/hashCode ─────────────────────────────────────────────

    @Test
    fun l2capFrameEqualsIdentity() {
        // Arrange
        val frame = L2capFrame(FrameType.DATA, byteArrayOf(0x01, 0x02))

        // Act
        val result = frame.equals(frame)

        // Assert
        assertTrue(result)
    }

    @Test
    fun l2capFrameEqualsSameContent() {
        // Arrange
        val frame = L2capFrame(FrameType.DATA, byteArrayOf(0x01, 0x02))
        val frame2 = L2capFrame(FrameType.DATA, byteArrayOf(0x01, 0x02))

        // Act
        val result = frame.equals(frame2)

        // Assert
        assertTrue(result)
        assertEquals(frame.hashCode(), frame2.hashCode())
    }

    @Test
    fun l2capFrameNotEqualsWrongType() {
        // Arrange
        val frame = L2capFrame(FrameType.DATA, byteArrayOf(0x01, 0x02))

        // Act
        val result = frame.equals("not a frame")

        // Assert
        assertFalse(result)
    }

    @Test
    fun l2capFrameNotEqualsDifferentFrameType() {
        // Arrange
        val frame = L2capFrame(FrameType.DATA, byteArrayOf(0x01, 0x02))
        val other = L2capFrame(FrameType.ACK, byteArrayOf(0x01, 0x02))

        // Act
        val result = frame.equals(other)

        // Assert
        assertFalse(result)
    }

    @Test
    fun l2capFrameNotEqualsDifferentPayload() {
        // Arrange
        val frame = L2capFrame(FrameType.DATA, byteArrayOf(0x01, 0x02))
        val other = L2capFrame(FrameType.DATA, byteArrayOf(0x03, 0x04))

        // Act
        val result = frame.equals(other)

        // Assert
        assertFalse(result)
    }

    // ── AdvertisementEvent equals/hashCode ────────────────────────────────────

    @Test
    fun advertisementEventEqualsIdentity() {
        // Arrange
        val ae = AdvertisementEvent(byteArrayOf(0x01), byteArrayOf(0x02, 0x03), -70)

        // Act
        val result = ae.equals(ae)

        // Assert
        assertTrue(result)
    }

    @Test
    fun advertisementEventEqualsSameContent() {
        // Arrange
        val ae = AdvertisementEvent(byteArrayOf(0x01), byteArrayOf(0x02, 0x03), -70)
        val ae2 = AdvertisementEvent(byteArrayOf(0x01), byteArrayOf(0x02, 0x03), -70)

        // Act
        val result = ae.equals(ae2)

        // Assert
        assertTrue(result)
        assertEquals(ae.hashCode(), ae2.hashCode())
    }

    @Test
    fun advertisementEventNotEqualsWrongType() {
        // Arrange
        val ae = AdvertisementEvent(byteArrayOf(0x01), byteArrayOf(0x02, 0x03), -70)

        // Act
        val result = ae.equals("x")

        // Assert
        assertFalse(result)
    }

    @Test
    fun advertisementEventNotEqualsDifferentPeerId() {
        // Arrange
        val ae = AdvertisementEvent(byteArrayOf(0x01), byteArrayOf(0x02, 0x03), -70)
        val other = AdvertisementEvent(byteArrayOf(0xFF.toByte()), byteArrayOf(0x02, 0x03), -70)

        // Act
        val result = ae.equals(other)

        // Assert
        assertFalse(result)
    }

    @Test
    fun advertisementEventNotEqualsDifferentServiceData() {
        // Arrange
        val peerId = byteArrayOf(0x01)
        val ae = AdvertisementEvent(peerId, byteArrayOf(0x02, 0x03), -70)
        val other = AdvertisementEvent(peerId, byteArrayOf(0xFF.toByte()), -70)

        // Act
        val result = ae.equals(other)

        // Assert
        assertFalse(result)
    }

    @Test
    fun advertisementEventNotEqualsDifferentRssi() {
        // Arrange
        val peerId = byteArrayOf(0x01)
        val serviceData = byteArrayOf(0x02, 0x03)
        val ae = AdvertisementEvent(peerId, serviceData, -70)
        val other = AdvertisementEvent(peerId, serviceData, -99)

        // Act
        val result = ae.equals(other)

        // Assert
        assertFalse(result)
    }

    // ── PeerLostEvent equals/hashCode ─────────────────────────────────────────

    @Test
    fun peerLostEventEqualsIdentity() {
        // Arrange
        val ple = PeerLostEvent(byteArrayOf(0x01), PeerLostReason.CONNECTION_LOST)

        // Act
        val result = ple.equals(ple)

        // Assert
        assertTrue(result)
    }

    @Test
    fun peerLostEventEqualsSameContent() {
        // Arrange
        val ple = PeerLostEvent(byteArrayOf(0x01), PeerLostReason.CONNECTION_LOST)
        val ple2 = PeerLostEvent(byteArrayOf(0x01), PeerLostReason.CONNECTION_LOST)

        // Act
        val result = ple.equals(ple2)

        // Assert
        assertTrue(result)
        assertEquals(ple.hashCode(), ple2.hashCode())
    }

    @Test
    fun peerLostEventNotEqualsWrongType() {
        // Arrange
        val ple = PeerLostEvent(byteArrayOf(0x01), PeerLostReason.CONNECTION_LOST)

        // Act
        val result = ple.equals(42)

        // Assert
        assertFalse(result)
    }

    @Test
    fun peerLostEventNotEqualsDifferentPeerId() {
        // Arrange
        val ple = PeerLostEvent(byteArrayOf(0x01), PeerLostReason.CONNECTION_LOST)
        val other = PeerLostEvent(byteArrayOf(0xFF.toByte()), PeerLostReason.CONNECTION_LOST)

        // Act
        val result = ple.equals(other)

        // Assert
        assertFalse(result)
    }

    @Test
    fun peerLostEventNotEqualsDifferentReason() {
        // Arrange
        val peerId = byteArrayOf(0x01)
        val ple = PeerLostEvent(peerId, PeerLostReason.CONNECTION_LOST)
        val other = PeerLostEvent(peerId, PeerLostReason.TIMEOUT)

        // Act
        val result = ple.equals(other)

        // Assert
        assertFalse(result)
    }

    // ── IncomingData equals/hashCode ──────────────────────────────────────────

    @Test
    fun incomingDataEqualsIdentity() {
        // Arrange
        val id = IncomingData(byteArrayOf(0x01), byteArrayOf(0x02, 0x03))

        // Act
        val result = id.equals(id)

        // Assert
        assertTrue(result)
    }

    @Test
    fun incomingDataEqualsSameContent() {
        // Arrange
        val id = IncomingData(byteArrayOf(0x01), byteArrayOf(0x02, 0x03))
        val id2 = IncomingData(byteArrayOf(0x01), byteArrayOf(0x02, 0x03))

        // Act
        val result = id.equals(id2)

        // Assert
        assertTrue(result)
        assertEquals(id.hashCode(), id2.hashCode())
    }

    @Test
    fun incomingDataNotEqualsWrongType() {
        // Arrange
        val id = IncomingData(byteArrayOf(0x01), byteArrayOf(0x02, 0x03))

        // Act
        val result = id.equals("x")

        // Assert
        assertFalse(result)
    }

    @Test
    fun incomingDataNotEqualsDifferentPeerId() {
        // Arrange
        val id = IncomingData(byteArrayOf(0x01), byteArrayOf(0x02, 0x03))
        val other = IncomingData(byteArrayOf(0xFF.toByte()), byteArrayOf(0x02, 0x03))

        // Act
        val result = id.equals(other)

        // Assert
        assertFalse(result)
    }

    @Test
    fun incomingDataNotEqualsDifferentData() {
        // Arrange
        val peerId = byteArrayOf(0x01)
        val id = IncomingData(peerId, byteArrayOf(0x02, 0x03))
        val other = IncomingData(peerId, byteArrayOf(0xFF.toByte()))

        // Act
        val result = id.equals(other)

        // Assert
        assertFalse(result)
    }

    // ── SendResult equals/hashCode ────────────────────────────────────────────

    @Test
    fun sendResultFailureEqualsIdentity() {
        // Arrange
        val f = SendResult.Failure("write error")

        // Act
        val result = f.equals(f)

        // Assert
        assertTrue(result)
    }

    @Test
    fun sendResultFailureEqualsSameReason() {
        // Arrange
        val f = SendResult.Failure("write error")
        val f2 = SendResult.Failure("write error")

        // Act
        val result = f.equals(f2)

        // Assert
        assertTrue(result)
        assertEquals(f.hashCode(), f2.hashCode())
    }

    @Test
    fun sendResultFailureNotEqualsWrongType() {
        // Arrange
        val f = SendResult.Failure("write error")

        // Act
        val result = f.equals("write error")

        // Assert
        assertFalse(result)
    }

    @Test
    fun sendResultFailureNotEqualsDifferentReason() {
        // Arrange
        val f = SendResult.Failure("write error")
        val other = SendResult.Failure("other error")

        // Act
        val result = f.equals(other)

        // Assert
        assertFalse(result)
    }

    @Test
    fun sendResultSuccessEqualsSelf() {
        // Act
        val result = SendResult.Success.equals(SendResult.Success)

        // Assert
        assertTrue(result)
    }

    @Test
    fun sendResultSuccessNotEqualsFailure() {
        // Act
        val result = SendResult.Success.equals(SendResult.Failure("x"))

        // Assert
        assertFalse(result)
    }

    @Test
    fun sendResultSuccessHashCodeConsistent() {
        // Act & Assert
        assertEquals(SendResult.Success.hashCode(), SendResult.Success.hashCode())
    }

    // ── assertIs smoke test for SendResult sealed hierarchy ──────────────────

    @Test
    fun sendResultSealedHierarchyInstances() {
        // Assert
        assertIs<SendResult>(SendResult.Success)
        assertIs<SendResult>(SendResult.Failure("err"))
    }
}
