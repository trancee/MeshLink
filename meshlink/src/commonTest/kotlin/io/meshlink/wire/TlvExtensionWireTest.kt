package io.meshlink.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests that TLV extensions round-trip through WireCodec encode/decode
 * for all 7 message types that support them.
 */
class TlvExtensionWireTest {

    private val sampleExtensions = listOf(
        TlvEntry(0x01u, byteArrayOf(0xAA.toByte(), 0xBB.toByte())),
        TlvEntry(0x80u, byteArrayOf(0xCC.toByte()))
    )

    private val peerId = ByteArray(8) { (it + 1).toByte() }
    private val messageId = ByteArray(16) { (it + 0x10).toByte() }

    // ── Keepalive ─────────────────────────────────────────────────

    @Test
    fun keepaliveWithExtensionsRoundTrips() {
        val encoded = WireCodec.encodeKeepalive(12345uL, 0u, sampleExtensions)
        val decoded = WireCodec.decodeKeepalive(encoded)
        assertEquals(12345uL, decoded.timestampMillis)
        assertEquals(2, decoded.extensions.size)
        assertEquals(0x01u.toUByte(), decoded.extensions[0].tag)
        assertContentEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), decoded.extensions[0].value)
        assertEquals(0x80u.toUByte(), decoded.extensions[1].tag)
        assertContentEquals(byteArrayOf(0xCC.toByte()), decoded.extensions[1].value)
    }

    @Test
    fun keepaliveWithoutExtensionsHasEmptyList() {
        val legacy = ByteArray(10) // old-format keepalive (no TLV trailer)
        legacy[0] = WireCodec.TYPE_KEEPALIVE
        val decoded = WireCodec.decodeKeepalive(legacy)
        assertTrue(decoded.extensions.isEmpty())
    }

    // ── ChunkAck ──────────────────────────────────────────────────

    @Test
    fun chunkAckWithExtensionsRoundTrips() {
        val encoded = WireCodec.encodeChunkAck(messageId, 5u, 0uL, 0uL, sampleExtensions)
        val decoded = WireCodec.decodeChunkAck(encoded)
        assertEquals(5u.toUShort(), decoded.ackSequence)
        assertEquals(2, decoded.extensions.size)
        assertEquals(0x01u.toUByte(), decoded.extensions[0].tag)
    }

    @Test
    fun chunkAckLegacyDecodeHasEmptyExtensions() {
        val legacy = ByteArray(35)
        legacy[0] = WireCodec.TYPE_CHUNK_ACK
        messageId.copyInto(legacy, 1)
        val decoded = WireCodec.decodeChunkAck(legacy)
        assertTrue(decoded.extensions.isEmpty())
    }

    // ── Nack ──────────────────────────────────────────────────────

    @Test
    fun nackWithExtensionsRoundTrips() {
        val encoded = WireCodec.encodeNack(messageId, NackReason.BUFFER_FULL, sampleExtensions)
        val decoded = WireCodec.decodeNack(encoded)
        assertEquals(NackReason.BUFFER_FULL, decoded.reason)
        assertEquals(2, decoded.extensions.size)
    }

    @Test
    fun nackLegacyDecodeHasEmptyExtensions() {
        val legacy = ByteArray(18)
        legacy[0] = WireCodec.TYPE_NACK
        messageId.copyInto(legacy, 1)
        val decoded = WireCodec.decodeNack(legacy)
        assertTrue(decoded.extensions.isEmpty())
    }

    // ── ResumeRequest ─────────────────────────────────────────────

    @Test
    fun resumeRequestWithExtensionsRoundTrips() {
        val encoded = WireCodec.encodeResumeRequest(messageId, 42u, sampleExtensions)
        val decoded = WireCodec.decodeResumeRequest(encoded)
        assertEquals(42u, decoded.bytesReceived)
        assertEquals(2, decoded.extensions.size)
    }

    // ── RouteRequest ──────────────────────────────────────────────

    @Test
    fun routeRequestWithExtensionsRoundTrips() {
        val dest = ByteArray(8) { (it + 0x20).toByte() }
        val encoded = WireCodec.encodeRouteRequest(
            peerId, dest, 100u, 2u, 10u, sampleExtensions
        )
        val decoded = WireCodec.decodeRouteRequest(encoded)
        assertEquals(100u, decoded.requestId)
        assertEquals(2u.toUByte(), decoded.hopCount)
        assertEquals(2, decoded.extensions.size)
        assertEquals(0x80u.toUByte(), decoded.extensions[1].tag)
    }

    @Test
    fun routeRequestLegacyDecodeHasEmptyExtensions() {
        val legacy = ByteArray(23)
        legacy[0] = WireCodec.TYPE_ROUTE_REQUEST
        val decoded = WireCodec.decodeRouteRequest(legacy)
        assertTrue(decoded.extensions.isEmpty())
    }

    // ── RouteReply ────────────────────────────────────────────────

    @Test
    fun routeReplyWithExtensionsRoundTrips() {
        val dest = ByteArray(8) { (it + 0x30).toByte() }
        val encoded = WireCodec.encodeRouteReply(
            peerId, dest, 200u, 3u, sampleExtensions
        )
        val decoded = WireCodec.decodeRouteReply(encoded)
        assertEquals(200u, decoded.requestId)
        assertEquals(3u.toUByte(), decoded.hopCount)
        assertEquals(2, decoded.extensions.size)
    }

    // ── DeliveryAck ───────────────────────────────────────────────

    @Test
    fun deliveryAckWithExtensionsRoundTrips() {
        val encoded = WireCodec.encodeDeliveryAck(
            messageId, peerId, extensions = sampleExtensions
        )
        val decoded = WireCodec.decodeDeliveryAck(encoded)
        assertContentEquals(messageId, decoded.messageId)
        assertEquals(2, decoded.extensions.size)
    }

    @Test
    fun deliveryAckWithSignatureAndExtensionsRoundTrips() {
        val sig = ByteArray(64) { 0x55 }
        val pubKey = ByteArray(32) { 0x66 }
        val encoded = WireCodec.encodeDeliveryAck(
            messageId, peerId, sig, pubKey, sampleExtensions
        )
        val decoded = WireCodec.decodeDeliveryAck(encoded)
        assertContentEquals(sig, decoded.signature)
        assertContentEquals(pubKey, decoded.signerPublicKey)
        assertEquals(2, decoded.extensions.size)
    }

    @Test
    fun deliveryAckLegacyNoSigDecodeHasEmptyExtensions() {
        val legacy = ByteArray(26)
        legacy[0] = WireCodec.TYPE_DELIVERY_ACK
        messageId.copyInto(legacy, 1)
        val decoded = WireCodec.decodeDeliveryAck(legacy)
        assertTrue(decoded.extensions.isEmpty())
    }

    // ── Unknown tags are preserved ────────────────────────────────

    @Test
    fun unknownExtensionTagPreservedAcrossRoundTrip() {
        val unknownExt = listOf(TlvEntry(0xFEu, byteArrayOf(0x01, 0x02, 0x03)))
        val encoded = WireCodec.encodeKeepalive(0uL, 0u, unknownExt)
        val decoded = WireCodec.decodeKeepalive(encoded)
        assertEquals(1, decoded.extensions.size)
        assertEquals(0xFEu.toUByte(), decoded.extensions[0].tag)
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03), decoded.extensions[0].value)
    }

    // ── Empty extension value ─────────────────────────────────────

    @Test
    fun emptyExtensionValueRoundTrips() {
        val ext = listOf(TlvEntry(0x42u, ByteArray(0)))
        val encoded = WireCodec.encodeNack(messageId, extensions = ext)
        val decoded = WireCodec.decodeNack(encoded)
        assertEquals(1, decoded.extensions.size)
        assertEquals(0x42u.toUByte(), decoded.extensions[0].tag)
        assertEquals(0, decoded.extensions[0].value.size)
    }

    // ── Golden vector with extensions ─────────────────────────────

    @Test
    fun keepaliveGoldenVectorWithExtension() {
        // Keepalive: type(01) + flags(00) + timestamp(0000000000000000 LE)
        // + extLen(0500) + tag(01) + len(0200) + value(DEAD)
        val ext = listOf(TlvEntry(0x01u, byteArrayOf(0xDE.toByte(), 0xAD.toByte())))
        val encoded = WireCodec.encodeKeepalive(0uL, 0u, ext)
        val expected = byteArrayOf(
            0x01, 0x00, // type + flags
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // timestamp
            0x05, 0x00, // extensionLength = 5
            0x01, // tag
            0x02, 0x00, // value length = 2
            0xDE.toByte(), 0xAD.toByte() // value
        )
        assertContentEquals(expected, encoded)
    }
}
