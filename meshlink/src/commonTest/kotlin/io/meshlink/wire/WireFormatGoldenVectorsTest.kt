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

    // Shared: messageId = 0x01..0x10 (16 bytes)
    private val messageId = ByteArray(16) { (it + 1).toByte() }

    // ────────────────────────────────────────────────────────────────────
    // Chunk (0x03)
    //   messageId  = 0x01..0x10
    //   seqNumber  = 0x0005  (LE: 05 00)
    //   totalChunks = 0x000A (LE: 0a 00)
    //   payload    = "Hello"
    //
    // Golden hex:
    //   030102030405060708090a0b0c0d0e0f1005000a0048656c6c6f
    // ────────────────────────────────────────────────────────────────────

    private val chunkGoldenHex =
        "03" +                                  // type: chunk
        "0102030405060708090a0b0c0d0e0f10" +    // messageId
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
    // ChunkAck (0x04)
    //   messageId       = 0x01..0x10
    //   ackSequence     = 0x0003  (LE: 03 00)
    //   sackBitmask     = 0xFF00FF00FF00FF00  (LE: 00 ff 00 ff 00 ff 00 ff)
    //   sackBitmaskHigh = 0xAA55AA55AA55AA55  (LE: 55 aa 55 aa 55 aa 55 aa)
    //
    // Layout: type(1) + msgId(16) + ackSeq(2 LE) + sackBitmask(8 LE)
    //         + sackBitmaskHigh(8 LE) = 35 bytes
    //
    // Golden hex:
    //   040102030405060708090a0b0c0d0e0f10030000ff00ff00ff00ff55aa55aa55aa55aa
    // ────────────────────────────────────────────────────────────────────

    private val chunkAckGoldenHex =
        "04" +                                  // type: chunk_ack
        "0102030405060708090a0b0c0d0e0f10" +    // messageId
        "0300" +                                // ackSequence = 3 (LE)
        "00ff00ff00ff00ff" +                     // sackBitmask = 0xFF00FF00FF00FF00 (LE)
        "55aa55aa55aa55aa"                       // sackBitmaskHigh = 0xAA55AA55AA55AA55 (LE)

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
    // RoutedMessage (0x05)
    //   messageId    = 0x01..0x10
    //   origin       = 0x11 × 16
    //   destination   = 0xAA × 16
    //   hopLimit     = 5
    //   replayCounter = 0
    //   visitedList  = [] (empty)
    //   payload      = "routed data"
    //
    // Layout: type(1) + msgId(16) + origin(16) + dest(16) + hop(1)
    //         + replay(8 LE) + visitedCount(1) + payload(11) = 70 bytes
    //
    // Golden hex:
    //   050102030405060708090a0b0c0d0e0f10
    //   11111111111111111111111111111111
    //   aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
    //   05 0000000000000000 00
    //   726f757465642064617461
    // ────────────────────────────────────────────────────────────────────

    private val routedMessageGoldenHex =
        "05" +                                  // type: routed_message
        "0102030405060708090a0b0c0d0e0f10" +    // messageId
        "11111111111111111111111111111111" +      // origin (16 × 0x11)
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +      // destination (16 × 0xAA)
        "05" +                                  // hopLimit = 5
        "0000000000000000" +                    // replayCounter = 0 (LE)
        "00" +                                  // visitedCount = 0
        "726f757465642064617461"                 // payload "routed data"

    @Test
    fun routedMessageGoldenVectorEncodes() {
        val encoded = WireCodec.encodeRoutedMessage(
            messageId = messageId,
            origin = ByteArray(16) { 0x11 },
            destination = ByteArray(16) { 0xAA.toByte() },
            hopLimit = 5u,
            visitedList = emptyList(),
            payload = "routed data".encodeToByteArray(),
        )
        assertEquals(routedMessageGoldenHex, encoded.toHex())
        assertEquals(70, encoded.size)
    }

    @Test
    fun routedMessageGoldenVectorDecodes() {
        val decoded = WireCodec.decodeRoutedMessage(hexToBytes(routedMessageGoldenHex))

        assertContentEquals(messageId, decoded.messageId)
        assertContentEquals(ByteArray(16) { 0x11 }, decoded.origin)
        assertContentEquals(ByteArray(16) { 0xAA.toByte() }, decoded.destination)
        assertEquals(5u.toUByte(), decoded.hopLimit)
        assertEquals(0uL, decoded.replayCounter)
        assertEquals(0, decoded.visitedList.size)
        assertContentEquals("routed data".encodeToByteArray(), decoded.payload)
    }

    // ────────────────────────────────────────────────────────────────────
    // RoutedMessage with visited list (0x05)
    //   Same base fields, but with replayCounter = 0x0000000100000002
    //   and visitedList = [0xEE × 16]
    //
    // replayCounter LE: 02 00 00 00 01 00 00 00
    // ────────────────────────────────────────────────────────────────────

    private val routedWithVisitedGoldenHex =
        "05" +                                  // type
        "0102030405060708090a0b0c0d0e0f10" +    // messageId
        "11111111111111111111111111111111" +      // origin
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +      // destination
        "05" +                                  // hopLimit = 5
        "0200000001000000" +                    // replayCounter = 0x0000000100000002 (LE)
        "01" +                                  // visitedCount = 1
        "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee" +    // visited[0] (16 × 0xEE)
        "726f757465642064617461"                 // payload "routed data"

    @Test
    fun routedMessageWithVisitedListGoldenVectorEncodes() {
        val encoded = WireCodec.encodeRoutedMessage(
            messageId = messageId,
            origin = ByteArray(16) { 0x11 },
            destination = ByteArray(16) { 0xAA.toByte() },
            hopLimit = 5u,
            visitedList = listOf(ByteArray(16) { 0xEE.toByte() }),
            payload = "routed data".encodeToByteArray(),
            replayCounter = 0x0000000100000002uL,
        )
        assertEquals(routedWithVisitedGoldenHex, encoded.toHex())
        assertEquals(86, encoded.size) // 70 + 16 visited entry
    }

    @Test
    fun routedMessageWithVisitedListGoldenVectorDecodes() {
        val decoded = WireCodec.decodeRoutedMessage(hexToBytes(routedWithVisitedGoldenHex))

        assertContentEquals(messageId, decoded.messageId)
        assertEquals(5u.toUByte(), decoded.hopLimit)
        assertEquals(0x0000000100000002uL, decoded.replayCounter)
        assertEquals(1, decoded.visitedList.size)
        assertContentEquals(ByteArray(16) { 0xEE.toByte() }, decoded.visitedList[0])
        assertContentEquals("routed data".encodeToByteArray(), decoded.payload)
    }

    // ────────────────────────────────────────────────────────────────────
    // Broadcast (0x00)
    //   messageId     = 0x01..0x10
    //   origin        = 0xBB × 16
    //   remainingHops = 3
    //   appIdHash     = 0xCC × 16
    //   signature     = (none, sigLen = 0)
    //   payload       = "broadcast msg"
    //
    // Layout: type(1) + msgId(16) + origin(16) + hops(1) + appIdHash(16)
    //         + sigLen(1) + payload(13) = 64 bytes
    //
    // Golden hex:
    //   000102030405060708090a0b0c0d0e0f10
    //   bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
    //   03
    //   cccccccccccccccccccccccccccccccc
    //   00
    //   62726f616463617374206d7367
    // ────────────────────────────────────────────────────────────────────

    private val broadcastGoldenHex =
        "00" +                                  // type: broadcast
        "0102030405060708090a0b0c0d0e0f10" +    // messageId
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" +      // origin (16 × 0xBB)
        "03" +                                  // remainingHops = 3
        "cccccccccccccccccccccccccccccccc" +      // appIdHash (16 × 0xCC)
        "00" +                                  // sigLen = 0 (unsigned)
        "62726f616463617374206d7367"             // payload "broadcast msg"

    @Test
    fun broadcastGoldenVectorEncodes() {
        val encoded = WireCodec.encodeBroadcast(
            messageId = messageId,
            origin = ByteArray(16) { 0xBB.toByte() },
            remainingHops = 3u,
            appIdHash = ByteArray(16) { 0xCC.toByte() },
            payload = "broadcast msg".encodeToByteArray(),
        )
        assertEquals(broadcastGoldenHex, encoded.toHex())
        assertEquals(64, encoded.size)
    }

    @Test
    fun broadcastGoldenVectorDecodes() {
        val decoded = WireCodec.decodeBroadcast(hexToBytes(broadcastGoldenHex))

        assertContentEquals(messageId, decoded.messageId)
        assertContentEquals(ByteArray(16) { 0xBB.toByte() }, decoded.origin)
        assertEquals(3u.toUByte(), decoded.remainingHops)
        assertContentEquals(ByteArray(16) { 0xCC.toByte() }, decoded.appIdHash)
        assertEquals(0, decoded.signature.size)
        assertEquals(0, decoded.signerPublicKey.size)
        assertContentEquals("broadcast msg".encodeToByteArray(), decoded.payload)
    }

    // ────────────────────────────────────────────────────────────────────
    // DeliveryAck (0x06)
    //   messageId   = 0x01..0x10
    //   recipientId = 0xDD × 16
    //   signature   = (none, sigLen = 0)
    //
    // Layout: type(1) + msgId(16) + recipientId(16) + sigLen(1) = 34 bytes
    //
    // Golden hex:
    //   060102030405060708090a0b0c0d0e0f10
    //   dddddddddddddddddddddddddddddddd
    //   00
    // ────────────────────────────────────────────────────────────────────

    private val deliveryAckGoldenHex =
        "06" +                                  // type: delivery_ack
        "0102030405060708090a0b0c0d0e0f10" +    // messageId
        "dddddddddddddddddddddddddddddddd" +    // recipientId (16 × 0xDD)
        "00"                                    // sigLen = 0 (unsigned)

    @Test
    fun deliveryAckGoldenVectorEncodes() {
        val encoded = WireCodec.encodeDeliveryAck(
            messageId = messageId,
            recipientId = ByteArray(16) { 0xDD.toByte() },
        )
        assertEquals(deliveryAckGoldenHex, encoded.toHex())
        assertEquals(34, encoded.size)
    }

    @Test
    fun deliveryAckGoldenVectorDecodes() {
        val decoded = WireCodec.decodeDeliveryAck(hexToBytes(deliveryAckGoldenHex))

        assertContentEquals(messageId, decoded.messageId)
        assertContentEquals(ByteArray(16) { 0xDD.toByte() }, decoded.recipientId)
        assertEquals(0, decoded.signature.size)
        assertEquals(0, decoded.signerPublicKey.size)
    }

    // ────────────────────────────────────────────────────────────────────
    // RouteUpdate (0x02)
    //   senderId = 0xAA × 16
    //   2 entries:
    //     Entry 1: dest=0xBB×16, cost=1.0, seqNum=100, hopCount=2
    //     Entry 2: dest=0xCC×16, cost=2.5, seqNum=200, hopCount=3
    //
    //   cost 1.0 IEEE 754 raw bits = 0x3FF0000000000000, LE: 000000000000f03f
    //   cost 2.5 IEEE 754 raw bits = 0x4004000000000000, LE: 0000000000000440
    //   seqNum 100 LE: 64000000
    //   seqNum 200 LE: c8000000
    //
    // Layout: type(1) + sender(16) + count(1) + 2 × entry(29) = 76 bytes
    // Each entry: dest(16) + cost(8 LE double) + seqNum(4 LE) + hop(1) = 29
    // ────────────────────────────────────────────────────────────────────

    private val routeUpdateGoldenHex =
        "02" +                                  // type: route_update
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +      // senderId (16 × 0xAA)
        "02" +                                  // entryCount = 2
        // Entry 1
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" +      // destination (16 × 0xBB)
        "000000000000f03f" +                    // cost = 1.0 (LE double)
        "64000000" +                            // sequenceNumber = 100 (LE)
        "02" +                                  // hopCount = 2
        // Entry 2
        "cccccccccccccccccccccccccccccccc" +      // destination (16 × 0xCC)
        "0000000000000440" +                    // cost = 2.5 (LE double)
        "c8000000" +                            // sequenceNumber = 200 (LE)
        "03"                                    // hopCount = 3

    @Test
    fun routeUpdateGoldenVectorEncodes() {
        val encoded = WireCodec.encodeRouteUpdate(
            senderId = ByteArray(16) { 0xAA.toByte() },
            entries = listOf(
                RouteUpdateEntry(
                    destination = ByteArray(16) { 0xBB.toByte() },
                    cost = 1.0,
                    sequenceNumber = 100u,
                    hopCount = 2u,
                ),
                RouteUpdateEntry(
                    destination = ByteArray(16) { 0xCC.toByte() },
                    cost = 2.5,
                    sequenceNumber = 200u,
                    hopCount = 3u,
                ),
            ),
        )
        assertEquals(routeUpdateGoldenHex, encoded.toHex())
        assertEquals(76, encoded.size)
    }

    @Test
    fun routeUpdateGoldenVectorDecodes() {
        val decoded = WireCodec.decodeRouteUpdate(hexToBytes(routeUpdateGoldenHex))

        assertContentEquals(ByteArray(16) { 0xAA.toByte() }, decoded.senderId)
        assertEquals(2, decoded.entries.size)

        val e0 = decoded.entries[0]
        assertContentEquals(ByteArray(16) { 0xBB.toByte() }, e0.destination)
        assertEquals(1.0, e0.cost, 0.0)
        assertEquals(100u, e0.sequenceNumber)
        assertEquals(2u.toUByte(), e0.hopCount)

        val e1 = decoded.entries[1]
        assertContentEquals(ByteArray(16) { 0xCC.toByte() }, e1.destination)
        assertEquals(2.5, e1.cost, 0.0)
        assertEquals(200u, e1.sequenceNumber)
        assertEquals(3u.toUByte(), e1.hopCount)

        assertEquals(null, decoded.signerPublicKey)
        assertEquals(null, decoded.signature)
    }

    // ────────────────────────────────────────────────────────────────────
    // ResumeRequest (0x07)
    //   messageId     = 0x01..0x10
    //   bytesReceived = 0x12345678  (LE: 78 56 34 12)
    //
    // Layout: type(1) + msgId(16) + bytesReceived(4 LE) = 21 bytes
    //
    // Golden hex:
    //   070102030405060708090a0b0c0d0e0f1078563412
    // ────────────────────────────────────────────────────────────────────

    private val resumeRequestGoldenHex =
        "07" +                                  // type: resume_request
        "0102030405060708090a0b0c0d0e0f10" +    // messageId
        "78563412"                              // bytesReceived = 0x12345678 (LE)

    @Test
    fun resumeRequestGoldenVectorEncodes() {
        val encoded = WireCodec.encodeResumeRequest(
            messageId = messageId,
            bytesReceived = 0x12345678u,
        )
        assertEquals(resumeRequestGoldenHex, encoded.toHex())
        assertEquals(21, encoded.size)
    }

    @Test
    fun resumeRequestGoldenVectorDecodes() {
        val decoded = WireCodec.decodeResumeRequest(hexToBytes(resumeRequestGoldenHex))

        assertContentEquals(messageId, decoded.messageId)
        assertEquals(0x12345678u, decoded.bytesReceived)
    }

    // ────────────────────────────────────────────────────────────────────
    // Keepalive (0x08)
    //   flags          = 0x00
    //   timestampMillis = 0  (LE ULong)
    //
    //   08 00 0000000000000000
    // ────────────────────────────────────────────────────────────────────

    private val keepaliveZeroGoldenHex =
        "08" +                  // type
        "00" +                  // flags
        "0000000000000000"      // timestampMillis = 0 (LE ULong)

    // Non-zero: timestampMillis = 1_700_000_000_000  (0x18BCFE56800)
    //   LE bytes: 00 68 e5 cf 8b 01 00 00
    //
    //   08 00 0068e5cf8b010000
    private val keepaliveNonZeroGoldenHex =
        "08" +                  // type
        "00" +                  // flags
        "0068e5cf8b010000"      // timestampMillis = 1_700_000_000_000 (LE ULong)

    @Test
    fun keepaliveZeroGoldenVectorEncodes() {
        val encoded = WireCodec.encodeKeepalive(timestampMillis = 0uL)
        assertEquals(keepaliveZeroGoldenHex, encoded.toHex())
        assertEquals(10, encoded.size)
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
        assertEquals(10, encoded.size)
    }

    @Test
    fun keepaliveNonZeroGoldenVectorDecodes() {
        val decoded = WireCodec.decodeKeepalive(hexToBytes(keepaliveNonZeroGoldenHex))
        assertEquals(0u.toUByte(), decoded.flags)
        assertEquals(1_700_000_000_000uL, decoded.timestampMillis)
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
    }
}
