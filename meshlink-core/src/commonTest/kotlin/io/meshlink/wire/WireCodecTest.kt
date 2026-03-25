package io.meshlink.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

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

        // type(1) + messageId(16) + origin(16) + destination(16) + hopLimit(1) + visitedCount(1) + visited(16) + payload(5) = 72
        assertEquals(72, encoded.size)
        assertEquals(0x05, encoded[0])  // type
        assertEquals(5.toByte(), encoded[49]) // hopLimit
        assertEquals(1.toByte(), encoded[50]) // visitedCount
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

        val encoded = WireCodec.encodeBroadcast(
            messageId = testMessageId,
            origin = originId,
            remainingHops = 3u,
            payload = payload,
        )

        // type(1) + messageId(16) + origin(16) + remainingHops(1) + payload
        assertEquals(1 + 16 + 16 + 1 + payload.size, encoded.size)
        assertEquals(WireCodec.TYPE_BROADCAST, encoded[0])

        val decoded = WireCodec.decodeBroadcast(encoded)

        assertContentEquals(testMessageId, decoded.messageId)
        assertContentEquals(originId, decoded.origin)
        assertEquals(3u.toUByte(), decoded.remainingHops)
        assertContentEquals(payload, decoded.payload)
    }

    // --- DeliveryAck (0x06) ---

    @Test
    fun deliveryAckEncodeDecodeRoundTrips() {
        val encoded = WireCodec.encodeDeliveryAck(
            messageId = testMessageId,
            recipientId = destinationId,
        )

        // type(1) + messageId(16) + recipientId(16) = 33
        assertEquals(33, encoded.size)
        assertEquals(WireCodec.TYPE_DELIVERY_ACK, encoded[0])

        val decoded = WireCodec.decodeDeliveryAck(encoded)

        assertContentEquals(testMessageId, decoded.messageId)
        assertContentEquals(destinationId, decoded.recipientId)
    }
}
