package io.meshlink.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WireCodecTest {

    private val testMessageId = ByteArray(16) { it.toByte() } // 0x00..0x0F

    @Test
    fun chunkMessageEncodesToGoldenBytes() {
        val encoded = WireCodec.encodeChunk(
            messageId = testMessageId,
            sequenceNumber = 1u,
            totalChunks = 3u,
            payload = "hello".encodeToByteArray()
        )

        // type(1) + messageId(16) + seqNum(2 LE) + totalChunks(2 LE) + payload(5)
        val expected = byteArrayOf(
            0x03,                                                           // type: chunk
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,               // messageId[0..7]
            0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,               // messageId[8..15]
            0x01, 0x00,                                                     // seqNum = 1 (LE)
            0x03, 0x00,                                                     // totalChunks = 3 (LE)
            0x68, 0x65, 0x6C, 0x6C, 0x6F                                   // "hello"
        )

        assertContentEquals(expected, encoded)
    }

    @Test
    fun chunkMessageDecodesFromGoldenBytes() {
        val bytes = byteArrayOf(
            0x03,
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
            0x01, 0x00,
            0x03, 0x00,
            0x68, 0x65, 0x6C, 0x6C, 0x6F
        )

        val decoded = WireCodec.decodeChunk(bytes)

        assertContentEquals(testMessageId, decoded.messageId)
        assertEquals(1u.toUShort(), decoded.sequenceNumber)
        assertEquals(3u.toUShort(), decoded.totalChunks)
        assertContentEquals("hello".encodeToByteArray(), decoded.payload)
    }

    @Test
    fun chunkAckEncodesToGoldenBytes() {
        val encoded = WireCodec.encodeChunkAck(
            messageId = testMessageId,
            ackSequence = 5u,
            sackBitmask = 0x1Fu
        )

        // type(1) + messageId(16) + ackSeq(2 LE) + sackBitmask(8 LE)
        val expected = byteArrayOf(
            0x04,                                                           // type: chunk_ack
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,               // messageId[0..7]
            0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,               // messageId[8..15]
            0x05, 0x00,                                                     // ackSeq = 5 (LE)
            0x1F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00                 // sackBitmask = 0x1F (LE)
        )

        assertContentEquals(expected, encoded)
    }

    @Test
    fun chunkAckDecodesFromGoldenBytes() {
        val bytes = byteArrayOf(
            0x04,
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
            0x05, 0x00,
            0x1F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )

        val decoded = WireCodec.decodeChunkAck(bytes)

        assertContentEquals(testMessageId, decoded.messageId)
        assertEquals(5u.toUShort(), decoded.ackSequence)
        assertEquals(0x1Fu.toULong(), decoded.sackBitmask)
    }

    @Test
    fun chunkEncodeDecodeRoundTrips() {
        val payload = ByteArray(200) { (it % 256).toByte() }
        val encoded = WireCodec.encodeChunk(testMessageId, 42u, 100u, payload)
        val decoded = WireCodec.decodeChunk(encoded)

        assertContentEquals(testMessageId, decoded.messageId)
        assertEquals(42u.toUShort(), decoded.sequenceNumber)
        assertEquals(100u.toUShort(), decoded.totalChunks)
        assertContentEquals(payload, decoded.payload)
    }

    @Test
    fun chunkAckEncodeDecodeRoundTrips() {
        val encoded = WireCodec.encodeChunkAck(testMessageId, 1000u, ULong.MAX_VALUE)
        val decoded = WireCodec.decodeChunkAck(encoded)

        assertContentEquals(testMessageId, decoded.messageId)
        assertEquals(1000u.toUShort(), decoded.ackSequence)
        assertEquals(ULong.MAX_VALUE, decoded.sackBitmask)
    }

    // --- Routed message (0x05) ---

    private val originId = ByteArray(16) { (0xA0 + it).toByte() }
    private val destinationId = ByteArray(16) { (0xD0.toByte() + it).toByte() }

    @Test
    fun routedMessageEncodesWithVisitedListGoldenBytes() {
        val visitedHash = ByteArray(16) { (0xF0.toByte() + it).toByte() }
        val payload = "relay".encodeToByteArray()

        val encoded = WireCodec.encodeRoutedMessage(
            messageId = testMessageId,
            origin = originId,
            destination = destinationId,
            hopLimit = 5u,
            visitedList = listOf(visitedHash),
            payload = payload,
        )

        // type(1) + messageId(16) + origin(16) + destination(16) + hopLimit(1) + replayCounter(8) + visitedCount(1) + visited(16) + payload(5) = 80
        assertEquals(80, encoded.size)
        assertEquals(0x05, encoded[0])  // type
        assertEquals(5.toByte(), encoded[49]) // hopLimit
        assertEquals(1.toByte(), encoded[58]) // visitedCount (after 8-byte counter)
    }

    @Test
    fun routedMessageRoundTrips() {
        val visited1 = ByteArray(16) { (0xF0.toByte() + it).toByte() }
        val visited2 = ByteArray(16) { (0xE0.toByte() + it).toByte() }
        val payload = ByteArray(100) { (it % 256).toByte() }

        val encoded = WireCodec.encodeRoutedMessage(
            messageId = testMessageId,
            origin = originId,
            destination = destinationId,
            hopLimit = 10u,
            visitedList = listOf(visited1, visited2),
            payload = payload,
        )

        val decoded = WireCodec.decodeRoutedMessage(encoded)

        assertContentEquals(testMessageId, decoded.messageId)
        assertContentEquals(originId, decoded.origin)
        assertContentEquals(destinationId, decoded.destination)
        assertEquals(10u.toUByte(), decoded.hopLimit)
        assertEquals(2, decoded.visitedList.size)
        assertContentEquals(visited1, decoded.visitedList[0])
        assertContentEquals(visited2, decoded.visitedList[1])
        assertContentEquals(payload, decoded.payload)
    }

    // --- Broadcast (0x00) ---

    @Test
    fun broadcastEncodeDecodeRoundTrips() {
        val payload = "hello mesh".encodeToByteArray()
        val appIdHash = ByteArray(16) { (0x10 + it).toByte() }

        val encoded = WireCodec.encodeBroadcast(
            messageId = testMessageId,
            origin = originId,
            remainingHops = 3u,
            appIdHash = appIdHash,
            payload = payload,
        )

        // type(1) + messageId(16) + origin(16) + remainingHops(1) + appIdHash(16) + sigLen(1) + payload
        assertEquals(1 + 16 + 16 + 1 + 16 + 1 + payload.size, encoded.size)
        assertEquals(WireCodec.TYPE_BROADCAST, encoded[0])

        val decoded = WireCodec.decodeBroadcast(encoded)

        assertContentEquals(testMessageId, decoded.messageId)
        assertContentEquals(originId, decoded.origin)
        assertEquals(3u.toUByte(), decoded.remainingHops)
        assertContentEquals(appIdHash, decoded.appIdHash)
        assertContentEquals(payload, decoded.payload)
    }

    // --- DeliveryAck (0x06) ---

    @Test
    fun deliveryAckEncodeDecodeRoundTrips() {
        val encoded = WireCodec.encodeDeliveryAck(
            messageId = testMessageId,
            recipientId = destinationId,
        )

        // type(1) + messageId(16) + recipientId(16) + sigLen(1) = 34
        assertEquals(34, encoded.size)
        assertEquals(WireCodec.TYPE_DELIVERY_ACK, encoded[0])

        val decoded = WireCodec.decodeDeliveryAck(encoded)

        assertContentEquals(testMessageId, decoded.messageId)
        assertContentEquals(destinationId, decoded.recipientId)
    }

    // --- Batch 12 Cycle 1: Decode rejects truncated data ---

    @Test
    fun decodeChunkThrowsOnTruncatedData() {
        assertFailsWith<IllegalArgumentException> {
            WireCodec.decodeChunk(byteArrayOf(WireCodec.TYPE_CHUNK))
        }
        assertFailsWith<IllegalArgumentException> {
            WireCodec.decodeChunkAck(byteArrayOf(WireCodec.TYPE_CHUNK_ACK) + ByteArray(10))
        }
        assertFailsWith<IllegalArgumentException> {
            WireCodec.decodeBroadcast(byteArrayOf(WireCodec.TYPE_BROADCAST) + ByteArray(20))
        }
        assertFailsWith<IllegalArgumentException> {
            WireCodec.decodeDeliveryAck(byteArrayOf(WireCodec.TYPE_DELIVERY_ACK) + ByteArray(15))
        }
        assertFailsWith<IllegalArgumentException> {
            WireCodec.decodeRoutedMessage(byteArrayOf(WireCodec.TYPE_ROUTED_MESSAGE) + ByteArray(30))
        }
    }

    // --- Batch 12 Cycle 2: Decode rejects wrong type byte ---

    @Test
    fun decodeRejectsWrongTypeByte() {
        // Valid-length chunk data but with broadcast type byte
        val chunkData = WireCodec.encodeChunk(testMessageId, 0u, 1u, "x".encodeToByteArray())
        chunkData[0] = WireCodec.TYPE_BROADCAST // corrupt type
        assertFailsWith<IllegalArgumentException> {
            WireCodec.decodeChunk(chunkData)
        }

        // Valid-length broadcast with chunk type byte
        val broadcastData = WireCodec.encodeBroadcast(testMessageId, ByteArray(16), 3u, payload = "y".encodeToByteArray())
        broadcastData[0] = WireCodec.TYPE_CHUNK
        assertFailsWith<IllegalArgumentException> {
            WireCodec.decodeBroadcast(broadcastData)
        }
    }

    // --- Route Update (0x02) ---

    @Test
    fun routeUpdateEncodeDecodeRoundTrips() {
        val senderId = ByteArray(16) { (0xBB.toByte() + it).toByte() }
        val dest1 = ByteArray(16) { (0xC0.toByte() + it).toByte() }
        val dest2 = ByteArray(16) { (0xD0.toByte() + it).toByte() }
        val entries = listOf(
            RouteUpdateEntry(destination = dest1, cost = 1.5, sequenceNumber = 10u, hopCount = 1u),
            RouteUpdateEntry(destination = dest2, cost = 3.0, sequenceNumber = 20u, hopCount = 2u),
        )

        val encoded = WireCodec.encodeRouteUpdate(senderId, entries)

        assertEquals(WireCodec.TYPE_ROUTE_UPDATE, encoded[0])
        // type(1) + sender(16) + entryCount(1) + 2 entries × 29 = 76
        assertEquals(76, encoded.size)

        val decoded = WireCodec.decodeRouteUpdate(encoded)

        assertContentEquals(senderId, decoded.senderId)
        assertEquals(2, decoded.entries.size)
        assertContentEquals(dest1, decoded.entries[0].destination)
        assertEquals(1.5, decoded.entries[0].cost, 0.001)
        assertEquals(10u, decoded.entries[0].sequenceNumber)
        assertEquals(1u.toUByte(), decoded.entries[0].hopCount)
        assertContentEquals(dest2, decoded.entries[1].destination)
        assertEquals(3.0, decoded.entries[1].cost, 0.001)
        assertEquals(20u, decoded.entries[1].sequenceNumber)
        assertEquals(2u.toUByte(), decoded.entries[1].hopCount)
    }

    @Test
    fun routeUpdateEncodesToGoldenBytes() {
        val senderId = ByteArray(16) { 0xAA.toByte() }
        val dest = ByteArray(16) { 0xDD.toByte() }
        val entries = listOf(
            RouteUpdateEntry(destination = dest, cost = 2.0, sequenceNumber = 1u, hopCount = 1u),
        )

        val encoded = WireCodec.encodeRouteUpdate(senderId, entries)

        // type(1) + sender(16) + entryCount(1) + 1 entry × 29 = 47
        assertEquals(47, encoded.size)
        assertEquals(0x02, encoded[0]) // TYPE_ROUTE_UPDATE
        // sender starts at offset 1
        assertEquals(0xAA.toByte(), encoded[1])
        // entryCount at offset 17
        assertEquals(1.toByte(), encoded[17])
        // entry destination starts at offset 18
        assertEquals(0xDD.toByte(), encoded[18])
    }

    @Test
    fun routeUpdateEmptyEntriesRoundTrips() {
        val senderId = ByteArray(16) { 0x11 }
        val encoded = WireCodec.encodeRouteUpdate(senderId, emptyList())
        val decoded = WireCodec.decodeRouteUpdate(encoded)

        assertContentEquals(senderId, decoded.senderId)
        assertEquals(0, decoded.entries.size)
        // type(1) + sender(16) + entryCount(1) = 18
        assertEquals(18, encoded.size)
    }

    @Test
    fun decodeRouteUpdateRejectsTruncatedData() {
        assertFailsWith<IllegalArgumentException> {
            WireCodec.decodeRouteUpdate(byteArrayOf(WireCodec.TYPE_ROUTE_UPDATE) + ByteArray(10))
        }
    }
}
