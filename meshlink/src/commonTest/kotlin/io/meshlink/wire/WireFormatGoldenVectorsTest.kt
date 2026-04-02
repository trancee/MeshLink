package io.meshlink.wire

import io.meshlink.util.hexToBytes
import io.meshlink.util.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Golden byte-level test vectors for the MeshLink wire format.
 *
 * Each test constructs a message with KNOWN fixed values, encodes it,
 * asserts the EXACT byte sequence (golden vector), then decodes the
 * golden vector and asserts all field values match.
 *
 * These vectors ensure binary compatibility across implementations.
 * All multi-byte integers are little-endian.
 */
class WireFormatGoldenVectorsTest {

    // Shared: messageId = 0x01..0x0C (12 bytes)
    private val messageId = ByteArray(12) { (it + 1).toByte() }

    // ────────────────────────────────────────────────────────────────────
    // Chunk (0x05)
    //   messageId  = 0x01..0x0C
    //   seqNumber  = 0x0005  (LE: 05 00)
    //   totalChunks = 0x000A (LE: 0a 00)
    //   payload    = "Hello"
    //
    // Golden hex:
    //   050102030405060708090a0b0c05000a0048656c6c6f
    // ────────────────────────────────────────────────────────────────────

    private val chunkGoldenHex =
        "05" +                                  // type: chunk
        "0102030405060708090a0b0c" +    // messageId
        "0500" +                                // sequenceNumber = 5 (LE)
        "0a00" +                                // totalChunks = 10 (LE)
        "48656c6c6f"                             // payload "Hello"

    @Test
    fun chunkGoldenVectorEncodes() {
        val encoded = WireCodec.encodeChunk(
            messageId = messageId,
            sequenceNumber = 0x0005u,
            totalChunks = 0x000Au,
            payload = "Hello".encodeToByteArray(),
        )
        assertEquals(chunkGoldenHex, encoded.toHex())
    }

    @Test
    fun chunkGoldenVectorDecodes() {
        val decoded = WireCodec.decodeChunk(hexToBytes(chunkGoldenHex))

        assertContentEquals(messageId, decoded.messageId)
        assertEquals(5u.toUShort(), decoded.sequenceNumber)
        assertEquals(10u.toUShort(), decoded.totalChunks)
        assertContentEquals("Hello".encodeToByteArray(), decoded.payload)
    }

    // ────────────────────────────────────────────────────────────────────
    // ChunkAck (0x06)
    //   messageId       = 0x01..0x10
    //   ackSequence     = 0x0003  (LE: 03 00)
    //   sackBitmask     = 0xFF00FF00FF00FF00  (LE: 00 ff 00 ff 00 ff 00 ff)
    //   sackBitmaskHigh = 0xAA55AA55AA55AA55  (LE: 55 aa 55 aa 55 aa 55 aa)
    //
    // Layout: type(1) + msgId(12) + ackSeq(2 LE) + sackBitmask(8 LE)
    //         + sackBitmaskHigh(8 LE) = 31 bytes
    //
    // Golden hex:
    //   060102030405060708090a0b0c030000ff00ff00ff00ff55aa55aa55aa55aa
    // ────────────────────────────────────────────────────────────────────

    private val chunkAckGoldenHex =
        "06" +                                  // type: chunk_ack
        "0102030405060708090a0b0c" +    // messageId
        "0300" +                                // ackSequence = 3 (LE)
        "00ff00ff00ff00ff" +                     // sackBitmask = 0xFF00FF00FF00FF00 (LE)
        "55aa55aa55aa55aa" +                     // sackBitmaskHigh = 0xAA55AA55AA55AA55 (LE)
        "0000"                                  // empty TLV extensions

    @Test
    fun chunkAckGoldenVectorEncodes() {
        val encoded = WireCodec.encodeChunkAck(
            messageId = messageId,
            ackSequence = 0x0003u,
            sackBitmask = 0xFF00FF00FF00FF00uL,
            sackBitmaskHigh = 0xAA55AA55AA55AA55uL
        )
        assertEquals(chunkAckGoldenHex, encoded.toHex())
    }

    @Test
    fun chunkAckGoldenVectorDecodes() {
        val decoded = WireCodec.decodeChunkAck(hexToBytes(chunkAckGoldenHex))

        assertContentEquals(messageId, decoded.messageId)
        assertEquals(3u.toUShort(), decoded.ackSequence)
        assertEquals(0xFF00FF00FF00FF00uL, decoded.sackBitmask)
        assertEquals(0xAA55AA55AA55AA55uL, decoded.sackBitmaskHigh)
    }

    // ────────────────────────────────────────────────────────────────────
    // RoutedMessage (0x0a)
    //   messageId    = 0x01..0x0C
    //   origin       = 0x11 × 8
    //   destination   = 0xAA × 8
    //   hopLimit     = 5
    //   replayCounter = 0
    //   visitedList  = [] (empty)
    //   payload      = "routed data"
    //
    // Layout: type(1) + msgId(12) + origin(8) + dest(8) + hop(1)
    //         + replay(8 LE) + visitedCount(1) + payload(11) = 50 bytes
    //
    // Golden hex:
    //   0a0102030405060708090a0b0c
    //   1111111111111111
    //   aaaaaaaaaaaaaaaa
    //   05 0000000000000000 00
    //   726f757465642064617461
    // ────────────────────────────────────────────────────────────────────

    private val routedMessageGoldenHex =
        "0a" +                                  // type: routed_message
        "0102030405060708090a0b0c" +    // messageId
        "1111111111111111" +                    // origin (8 × 0x11)
        "aaaaaaaaaaaaaaaa" +                    // destination (8 × 0xAA)
        "05" +                                  // hopLimit = 5
        "0000000000000000" +                    // replayCounter = 0 (LE)
        "00" +                                  // visitedCount = 0
        "726f757465642064617461"                 // payload "routed data"

    @Test
    fun routedMessageGoldenVectorEncodes() {
        val encoded = WireCodec.encodeRoutedMessage(
            messageId = messageId,
            origin = ByteArray(8) { 0x11 },
            destination = ByteArray(8) { 0xAA.toByte() },
            hopLimit = 5u,
            visitedList = emptyList(),
            payload = "routed data".encodeToByteArray(),
        )
        assertEquals(routedMessageGoldenHex, encoded.toHex())
        assertEquals(50, encoded.size)
    }

    @Test
    fun routedMessageGoldenVectorDecodes() {
        val decoded = WireCodec.decodeRoutedMessage(hexToBytes(routedMessageGoldenHex))

        assertContentEquals(messageId, decoded.messageId)
        assertContentEquals(ByteArray(8) { 0x11 }, decoded.origin)
        assertContentEquals(ByteArray(8) { 0xAA.toByte() }, decoded.destination)
        assertEquals(5u.toUByte(), decoded.hopLimit)
        assertEquals(0uL, decoded.replayCounter)
        assertEquals(0, decoded.visitedList.size)
        assertContentEquals("routed data".encodeToByteArray(), decoded.payload)
    }

    // ────────────────────────────────────────────────────────────────────
    // RoutedMessage with visited list (0x0a)
    //   Same base fields, but with replayCounter = 0x0000000100000002
    //   and visitedList = [0xEE × 8]
    //
    // replayCounter LE: 02 00 00 00 01 00 00 00
    // ────────────────────────────────────────────────────────────────────

    private val routedWithVisitedGoldenHex =
        "0a" +                                  // type
        "0102030405060708090a0b0c" +    // messageId
        "1111111111111111" +                    // origin
        "aaaaaaaaaaaaaaaa" +                    // destination
        "05" +                                  // hopLimit = 5
        "0200000001000000" +                    // replayCounter = 0x0000000100000002 (LE)
        "01" +                                  // visitedCount = 1
        "eeeeeeeeeeeeeeee" +                    // visited[0] (8 × 0xEE)
        "726f757465642064617461"                 // payload "routed data"

    @Test
    fun routedMessageWithVisitedListGoldenVectorEncodes() {
        val encoded = WireCodec.encodeRoutedMessage(
            messageId = messageId,
            origin = ByteArray(8) { 0x11 },
            destination = ByteArray(8) { 0xAA.toByte() },
            hopLimit = 5u,
            visitedList = listOf(ByteArray(8) { 0xEE.toByte() }),
            payload = "routed data".encodeToByteArray(),
            replayCounter = 0x0000000100000002uL,
        )
        assertEquals(routedWithVisitedGoldenHex, encoded.toHex())
        assertEquals(58, encoded.size) // 50 + 8 visited entry
    }

    @Test
    fun routedMessageWithVisitedListGoldenVectorDecodes() {
        val decoded = WireCodec.decodeRoutedMessage(hexToBytes(routedWithVisitedGoldenHex))

        assertContentEquals(messageId, decoded.messageId)
        assertEquals(5u.toUByte(), decoded.hopLimit)
        assertEquals(0x0000000100000002uL, decoded.replayCounter)
        assertEquals(1, decoded.visitedList.size)
        assertContentEquals(ByteArray(8) { 0xEE.toByte() }, decoded.visitedList[0])
        assertContentEquals("routed data".encodeToByteArray(), decoded.payload)
    }

    // ────────────────────────────────────────────────────────────────────
    // Broadcast (0x09)
    //   messageId     = 0x01..0x0C
    //   origin        = 0xBB × 8
    //   remainingHops = 3
    //   appIdHash     = 0xCC × 8
    //   signature     = (none, sigLen = 0)
    //   payload       = "broadcast msg"
    //
    // Layout: type(1) + msgId(12) + origin(8) + hops(1) + appIdHash(8)
    //         + sigLen(1) + payload(13) = 44 bytes
    //
    // Golden hex:
    //   090102030405060708090a0b0c
    //   bbbbbbbbbbbbbbbb
    //   03
    //   cccccccccccccccc
    //   00
    //   62726f616463617374206d7367
    // ────────────────────────────────────────────────────────────────────

    private val broadcastGoldenHex =
        "09" +                                  // type: broadcast
        "0102030405060708090a0b0c" +    // messageId
        "bbbbbbbbbbbbbbbb" +                    // origin (8 × 0xBB)
        "03" +                                  // remainingHops = 3
        "cccccccccccccccc" +                        // appIdHash (8 × 0xCC)
        "00" +                                  // sigLen = 0 (unsigned)
        "62726f616463617374206d7367"             // payload "broadcast msg"

    @Test
    fun broadcastGoldenVectorEncodes() {
        val encoded = WireCodec.encodeBroadcast(
            messageId = messageId,
            origin = ByteArray(8) { 0xBB.toByte() },
            remainingHops = 3u,
            appIdHash = ByteArray(8) { 0xCC.toByte() },
            payload = "broadcast msg".encodeToByteArray(),
        )
        assertEquals(broadcastGoldenHex, encoded.toHex())
        assertEquals(44, encoded.size)
    }

    @Test
    fun broadcastGoldenVectorDecodes() {
        val decoded = WireCodec.decodeBroadcast(hexToBytes(broadcastGoldenHex))

        assertContentEquals(messageId, decoded.messageId)
        assertContentEquals(ByteArray(8) { 0xBB.toByte() }, decoded.origin)
        assertEquals(3u.toUByte(), decoded.remainingHops)
        assertContentEquals(ByteArray(8) { 0xCC.toByte() }, decoded.appIdHash)
        assertEquals(0, decoded.signature.size)
        assertEquals(0, decoded.signerPublicKey.size)
        assertContentEquals("broadcast msg".encodeToByteArray(), decoded.payload)
    }

    // ────────────────────────────────────────────────────────────────────
    // DeliveryAck (0x0b)
    //   messageId   = 0x01..0x0C
    //   recipientId = 0xDD × 8
    //   signature   = (none, sigLen = 0)
    //
    // Layout: type(1) + msgId(12) + recipientId(8) + sigLen(1) = 22 bytes
    //
    // Golden hex:
    //   0b0102030405060708090a0b0c
    //   dddddddddddddddd
    //   00
    // ────────────────────────────────────────────────────────────────────

    private val deliveryAckGoldenHex =
        "0b" +                                  // type: delivery_ack
        "0102030405060708090a0b0c" +    // messageId
        "dddddddddddddddd" +                   // recipientId (8 × 0xDD)
        "00" +                                  // sigLen = 0 (unsigned)
        "0000"                                  // empty TLV extensions

    @Test
    fun deliveryAckGoldenVectorEncodes() {
        val encoded = WireCodec.encodeDeliveryAck(
            messageId = messageId,
            recipientId = ByteArray(8) { 0xDD.toByte() },
        )
        assertEquals(deliveryAckGoldenHex, encoded.toHex())
        assertEquals(24, encoded.size)
    }

    @Test
    fun deliveryAckGoldenVectorDecodes() {
        val decoded = WireCodec.decodeDeliveryAck(hexToBytes(deliveryAckGoldenHex))

        assertContentEquals(messageId, decoded.messageId)
        assertContentEquals(ByteArray(8) { 0xDD.toByte() }, decoded.recipientId)
        assertEquals(0, decoded.signature.size)
        assertEquals(0, decoded.signerPublicKey.size)
    }

    // ────────────────────────────────────────────────────────────────────
    // ResumeRequest (0x08)
    //   messageId     = 0x01..0x0C
    //   bytesReceived = 0x12345678  (LE: 78 56 34 12)
    //
    // Layout: type(1) + msgId(12) + bytesReceived(4 LE) = 17 bytes
    //
    // Golden hex:
    //   080102030405060708090a0b0c78563412
    // ────────────────────────────────────────────────────────────────────

    private val resumeRequestGoldenHex =
        "08" +                                  // type: resume_request
        "0102030405060708090a0b0c" +    // messageId
        "78563412" +                            // bytesReceived = 0x12345678 (LE)
        "0000"                                  // empty TLV extensions

    @Test
    fun resumeRequestGoldenVectorEncodes() {
        val encoded = WireCodec.encodeResumeRequest(
            messageId = messageId,
            bytesReceived = 0x12345678u,
        )
        assertEquals(resumeRequestGoldenHex, encoded.toHex())
        assertEquals(19, encoded.size)
    }

    @Test
    fun resumeRequestGoldenVectorDecodes() {
        val decoded = WireCodec.decodeResumeRequest(hexToBytes(resumeRequestGoldenHex))

        assertContentEquals(messageId, decoded.messageId)
        assertEquals(0x12345678u, decoded.bytesReceived)
    }

    // ────────────────────────────────────────────────────────────────────
    // Keepalive (0x01)
    //   flags          = 0x00
    //   timestampMillis = 0  (LE ULong)
    //
    //   01 00 0000000000000000
    // ────────────────────────────────────────────────────────────────────

    private val keepaliveZeroGoldenHex =
        "01" +                  // type
        "00" +                  // flags
        "0000000000000000" +    // timestampMillis = 0 (LE ULong)
        "0000"                  // empty TLV extensions

    // Non-zero: timestampMillis = 1_700_000_000_000  (0x18BCFE56800)
    //   LE bytes: 00 68 e5 cf 8b 01 00 00
    //
    //   01 00 0068e5cf8b010000
    private val keepaliveNonZeroGoldenHex =
        "01" +                  // type
        "00" +                  // flags
        "0068e5cf8b010000" +    // timestampMillis = 1_700_000_000_000 (LE ULong)
        "0000"                  // empty TLV extensions

    @Test
    fun keepaliveZeroGoldenVectorEncodes() {
        val encoded = WireCodec.encodeKeepalive(timestampMillis = 0uL)
        assertEquals(keepaliveZeroGoldenHex, encoded.toHex())
        assertEquals(12, encoded.size)
    }

    @Test
    fun keepaliveZeroGoldenVectorDecodes() {
        val decoded = WireCodec.decodeKeepalive(hexToBytes(keepaliveZeroGoldenHex))
        assertEquals(0u.toUByte(), decoded.flags)
        assertEquals(0uL, decoded.timestampMillis)
    }

    @Test
    fun keepaliveNonZeroGoldenVectorEncodes() {
        val encoded = WireCodec.encodeKeepalive(timestampMillis = 1_700_000_000_000uL)
        assertEquals(keepaliveNonZeroGoldenHex, encoded.toHex())
        assertEquals(12, encoded.size)
    }

    @Test
    fun keepaliveNonZeroGoldenVectorDecodes() {
        val decoded = WireCodec.decodeKeepalive(hexToBytes(keepaliveNonZeroGoldenHex))
        assertEquals(0u.toUByte(), decoded.flags)
        assertEquals(1_700_000_000_000uL, decoded.timestampMillis)
    }

    // ────────────────────────────────────────────────────────────────────
    // RouteRequest (0x03)
    //   origin       = 0xA0..0xA7
    //   destination  = 0xB0..0xB7
    //   requestId    = 42 (LE: 2a 00 00 00)
    //   hopCount     = 2
    //   hopLimit     = 10
    //
    // Layout: type(1) + origin(8) + dest(8) + requestId(4 LE) + hopCount(1)
    //         + hopLimit(1) = 23 bytes
    // ────────────────────────────────────────────────────────────────────

    private val routeRequestGoldenHex =
        "03" +                  // type: route_request
        "a0a1a2a3a4a5a6a7" +  // origin
        "b0b1b2b3b4b5b6b7" +  // destination
        "2a000000" +            // requestId = 42 (LE)
        "02" +                  // hopCount = 2
        "0a" +                  // hopLimit = 10
        "0000"                  // empty TLV extensions

    @Test
    fun routeRequestGoldenVectorEncodes() {
        val encoded = WireCodec.encodeRouteRequest(
            origin = ByteArray(8) { (0xA0 + it).toByte() },
            destination = ByteArray(8) { (0xB0 + it).toByte() },
            requestId = 42u,
            hopCount = 2u,
            hopLimit = 10u,
        )
        assertEquals(routeRequestGoldenHex, encoded.toHex())
        assertEquals(25, encoded.size)
    }

    @Test
    fun routeRequestGoldenVectorDecodes() {
        val decoded = WireCodec.decodeRouteRequest(hexToBytes(routeRequestGoldenHex))
        assertContentEquals(ByteArray(8) { (0xA0 + it).toByte() }, decoded.origin)
        assertContentEquals(ByteArray(8) { (0xB0 + it).toByte() }, decoded.destination)
        assertEquals(42u, decoded.requestId)
        assertEquals(2u.toUByte(), decoded.hopCount)
        assertEquals(10u.toUByte(), decoded.hopLimit)
    }

    // ────────────────────────────────────────────────────────────────────
    // RouteReply (0x04)
    //   origin       = 0xA0..0xA7
    //   destination  = 0xB0..0xB7
    //   requestId    = 42 (LE: 2a 00 00 00)
    //   hopCount     = 3
    //
    // Layout: type(1) + origin(8) + dest(8) + requestId(4 LE) + hopCount(1)
    //         = 22 bytes
    // ────────────────────────────────────────────────────────────────────

    private val routeReplyGoldenHex =
        "04" +                  // type: route_reply
        "a0a1a2a3a4a5a6a7" +  // origin
        "b0b1b2b3b4b5b6b7" +  // destination
        "2a000000" +            // requestId = 42 (LE)
        "03" +                  // hopCount = 3
        "0000"                  // empty TLV extensions

    @Test
    fun routeReplyGoldenVectorEncodes() {
        val encoded = WireCodec.encodeRouteReply(
            origin = ByteArray(8) { (0xA0 + it).toByte() },
            destination = ByteArray(8) { (0xB0 + it).toByte() },
            requestId = 42u,
            hopCount = 3u,
        )
        assertEquals(routeReplyGoldenHex, encoded.toHex())
        assertEquals(24, encoded.size)
    }

    @Test
    fun routeReplyGoldenVectorDecodes() {
        val decoded = WireCodec.decodeRouteReply(hexToBytes(routeReplyGoldenHex))
        assertContentEquals(ByteArray(8) { (0xA0 + it).toByte() }, decoded.origin)
        assertContentEquals(ByteArray(8) { (0xB0 + it).toByte() }, decoded.destination)
        assertEquals(42u, decoded.requestId)
        assertEquals(3u.toUByte(), decoded.hopCount)
    }

    // ────────────────────────────────────────────────────────────────────
    // Cross-check: encode then decode round-trip yields identical fields
    // for every message type with the golden input values.
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun allGoldenVectorsRoundTrip() {
        // Chunk
        val chunkBytes = hexToBytes(chunkGoldenHex)
        val reEncoded = WireCodec.encodeChunk(
            messageId = WireCodec.decodeChunk(chunkBytes).messageId,
            sequenceNumber = WireCodec.decodeChunk(chunkBytes).sequenceNumber,
            totalChunks = WireCodec.decodeChunk(chunkBytes).totalChunks,
            payload = WireCodec.decodeChunk(chunkBytes).payload,
        )
        assertEquals(chunkGoldenHex, reEncoded.toHex(), "Chunk round-trip mismatch")

        // ChunkAck
        val ackBytes = hexToBytes(chunkAckGoldenHex)
        val ack = WireCodec.decodeChunkAck(ackBytes)
        val reAck = WireCodec.encodeChunkAck(ack.messageId, ack.ackSequence, ack.sackBitmask, ack.sackBitmaskHigh)
        assertEquals(chunkAckGoldenHex, reAck.toHex(), "ChunkAck round-trip mismatch")

        // Broadcast
        val bcast = WireCodec.decodeBroadcast(hexToBytes(broadcastGoldenHex))
        val reBcast = WireCodec.encodeBroadcast(
            bcast.messageId, bcast.origin, bcast.remainingHops,
            bcast.appIdHash, bcast.payload, bcast.signature, bcast.signerPublicKey,
        )
        assertEquals(broadcastGoldenHex, reBcast.toHex(), "Broadcast round-trip mismatch")

        // DeliveryAck
        val dack = WireCodec.decodeDeliveryAck(hexToBytes(deliveryAckGoldenHex))
        val reDack = WireCodec.encodeDeliveryAck(
            dack.messageId, dack.recipientId, dack.signature, dack.signerPublicKey,
        )
        assertEquals(deliveryAckGoldenHex, reDack.toHex(), "DeliveryAck round-trip mismatch")

        // ResumeRequest
        val rr = WireCodec.decodeResumeRequest(hexToBytes(resumeRequestGoldenHex))
        val reRr = WireCodec.encodeResumeRequest(rr.messageId, rr.bytesReceived)
        assertEquals(resumeRequestGoldenHex, reRr.toHex(), "ResumeRequest round-trip mismatch")

        // Keepalive
        val ka = WireCodec.decodeKeepalive(hexToBytes(keepaliveNonZeroGoldenHex))
        val reKa = WireCodec.encodeKeepalive(ka.timestampMillis, ka.flags)
        assertEquals(keepaliveNonZeroGoldenHex, reKa.toHex(), "Keepalive round-trip mismatch")

        // RouteRequest
        val rreq = WireCodec.decodeRouteRequest(hexToBytes(routeRequestGoldenHex))
        val reRreq = WireCodec.encodeRouteRequest(rreq.origin, rreq.destination, rreq.requestId, rreq.hopCount, rreq.hopLimit)
        assertEquals(routeRequestGoldenHex, reRreq.toHex(), "RouteRequest round-trip mismatch")

        // RouteReply
        val rrep = WireCodec.decodeRouteReply(hexToBytes(routeReplyGoldenHex))
        val reRrep = WireCodec.encodeRouteReply(rrep.origin, rrep.destination, rrep.requestId, rrep.hopCount)
        assertEquals(routeReplyGoldenHex, reRrep.toHex(), "RouteReply round-trip mismatch")
    }
}
