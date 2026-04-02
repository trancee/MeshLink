package io.meshlink.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WireCodecTest {

    private val testMessageId = ByteArray(12) { it.toByte() } // 0x00..0x0B

    @Test
    fun chunkMessageEncodesToGoldenBytes() {
        val encoded = WireCodec.encodeChunk(
            messageId = testMessageId,
            sequenceNumber = 1u,
            totalChunks = 3u,
            payload = "hello".encodeToByteArray()
        )

        // type(1) + messageId(12) + seqNum(2 LE) + totalChunks(2 LE) + payload(5)
        val expected = byteArrayOf(
            0x05,                                                           // type: chunk
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,               // messageId[0..7]
            0x08, 0x09, 0x0A, 0x0B,                                        // messageId[8..11]
            0x01, 0x00,                                                     // seqNum = 1 (LE)
            0x03, 0x00,                                                     // totalChunks = 3 (LE)
            0x68, 0x65, 0x6C, 0x6C, 0x6F                                   // "hello"
        )

        assertContentEquals(expected, encoded)
    }

    @Test
    fun chunkMessageDecodesFromGoldenBytes() {
        val bytes = byteArrayOf(
            0x05,
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0A, 0x0B,
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
            sackBitmask = 0x1Fu,
            sackBitmaskHigh = 0uL
        )

        // type(1) + messageId(12) + ackSeq(2 LE) + sackBitmask(8 LE) + sackBitmaskHigh(8 LE) + ext(2) = 33
        val expected = byteArrayOf(
            0x06,                                                           // type: chunk_ack
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,               // messageId[0..7]
            0x08, 0x09, 0x0A, 0x0B,                                        // messageId[8..11]
            0x05, 0x00,                                                     // ackSeq = 5 (LE)
            0x1F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,                // sackBitmask = 0x1F (LE)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,                // sackBitmaskHigh = 0 (LE)
            0x00, 0x00                                                      // empty TLV extensions
        )

        assertContentEquals(expected, encoded)
    }

    @Test
    fun chunkAckDecodesFromGoldenBytes() {
        val bytes = byteArrayOf(
            0x06,
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0A, 0x0B,
            0x05, 0x00,
            0x1F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )

        val decoded = WireCodec.decodeChunkAck(bytes)

        assertContentEquals(testMessageId, decoded.messageId)
        assertEquals(5u.toUShort(), decoded.ackSequence)
        assertEquals(0x1Fu.toULong(), decoded.sackBitmask)
        assertEquals(0uL, decoded.sackBitmaskHigh)
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
        val encoded = WireCodec.encodeChunkAck(testMessageId, 1000u, ULong.MAX_VALUE, 0x1234567890ABCDEFuL)
        val decoded = WireCodec.decodeChunkAck(encoded)

        assertContentEquals(testMessageId, decoded.messageId)
        assertEquals(1000u.toUShort(), decoded.ackSequence)
        assertEquals(ULong.MAX_VALUE, decoded.sackBitmask)
        assertEquals(0x1234567890ABCDEFuL, decoded.sackBitmaskHigh)
    }

    // --- Routed message (0x0a) ---

    private val originId = ByteArray(8) { (0xA0 + it).toByte() }
    private val destinationId = ByteArray(8) { (0xD0.toByte() + it).toByte() }

    @Test
    fun routedMessageEncodesWithVisitedListGoldenBytes() {
        val visitedHash = ByteArray(8) { (0xF0.toByte() + it).toByte() }
        val payload = "relay".encodeToByteArray()

        val encoded = WireCodec.encodeRoutedMessage(
            messageId = testMessageId,
            origin = originId,
            destination = destinationId,
            hopLimit = 5u,
            visitedList = listOf(visitedHash),
            payload = payload,
        )

        // type(1) + messageId(12) + origin(8) + destination(8) + hopLimit(1) + replayCounter(8) + visitedCount(1) + visited(8) + payload(5) = 52
        assertEquals(52, encoded.size)
        assertEquals(0x0a, encoded[0])  // type
        assertEquals(5.toByte(), encoded[29]) // hopLimit
        assertEquals(1.toByte(), encoded[38]) // visitedCount (after 8-byte counter)
    }

    @Test
    fun routedMessageRoundTrips() {
        val visited1 = ByteArray(8) { (0xF0.toByte() + it).toByte() }
        val visited2 = ByteArray(8) { (0xE0.toByte() + it).toByte() }
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

    // --- Broadcast (0x09) ---

    @Test
    fun broadcastEncodeDecodeRoundTrips() {
        val payload = "hello mesh".encodeToByteArray()
        val appIdHash = ByteArray(8) { (0x10 + it).toByte() }

        val encoded = WireCodec.encodeBroadcast(
            messageId = testMessageId,
            origin = originId,
            remainingHops = 3u,
            appIdHash = appIdHash,
            payload = payload,
        )

        // type(1) + messageId(12) + origin(8) + remainingHops(1) + appIdHash(8) + sigLen(1) + payload
        assertEquals(1 + 12 + 8 + 1 + 8 + 1 + payload.size, encoded.size)
        assertEquals(WireCodec.TYPE_BROADCAST, encoded[0])

        val decoded = WireCodec.decodeBroadcast(encoded)

        assertContentEquals(testMessageId, decoded.messageId)
        assertContentEquals(originId, decoded.origin)
        assertEquals(3u.toUByte(), decoded.remainingHops)
        assertContentEquals(appIdHash, decoded.appIdHash)
        assertContentEquals(payload, decoded.payload)
    }

    // --- DeliveryAck (0x0b) ---

    @Test
    fun deliveryAckEncodeDecodeRoundTrips() {
        val encoded = WireCodec.encodeDeliveryAck(
            messageId = testMessageId,
            recipientId = destinationId,
        )

        // type(1) + messageId(12) + recipientId(8) + sigLen(1) + ext(2) = 24
        assertEquals(24, encoded.size)
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
        val broadcastData = WireCodec.encodeBroadcast(testMessageId, ByteArray(8), 3u, payload = "y".encodeToByteArray())
        broadcastData[0] = WireCodec.TYPE_CHUNK
        assertFailsWith<IllegalArgumentException> {
            WireCodec.decodeBroadcast(broadcastData)
        }
    }

    // --- Visited List Validation ---

    @Test
    fun decodeRoutedMessageRejectsTruncatedVisitedList() {
        // Build a routed message with visitedCount=5 but only enough data for 1 entry
        val header = ByteArray(39) // ROUTED_HEADER_SIZE = 39
        header[0] = WireCodec.TYPE_ROUTED_MESSAGE
        header[38] = 5 // visitedCount = 5 (claims 5×8=40 bytes of visited data)
        // Only provide 8 bytes of visited data (1 entry, not 5)
        val truncated = header + ByteArray(8) + "payload".encodeToByteArray()

        val ex = assertFailsWith<IllegalArgumentException> {
            WireCodec.decodeRoutedMessage(truncated)
        }
        assertTrue(ex.message!!.contains("truncated"), "Error should mention truncation: ${ex.message}")
    }

    @Test
    fun encodeRoutedMessageRejectsVisitedListOver255() {
        val oversized = (0..255).map { ByteArray(8) { it.toByte() } } // 256 entries
        assertFailsWith<IllegalArgumentException> {
            WireCodec.encodeRoutedMessage(
                messageId = testMessageId,
                origin = ByteArray(8),
                destination = ByteArray(8),
                hopLimit = 10u,
                visitedList = oversized,
                payload = "x".encodeToByteArray()
            )
        }
    }

    // --- Wire Protocol Hardening ---

    @Test
    fun decodeBroadcastRejectsTruncatedSignature() {
        // Craft broadcast with flags=0x01 (has signature) but only 10 bytes remaining
        val header = ByteArray(31) // BROADCAST_HEADER_SIZE = 31
        header[0] = WireCodec.TYPE_BROADCAST
        header[30] = 0x01 // FLAG_HAS_SIGNATURE, but no signature bytes follow
        val truncated = header + ByteArray(10) // only 10 bytes, need 64+32=96

        assertFailsWith<IllegalArgumentException> {
            WireCodec.decodeBroadcast(truncated)
        }
    }

    @Test
    fun decodeChunkRejectsSequenceExceedingTotal() {
        // Craft chunk with sequenceNumber=10, totalChunks=3
        val encoded = WireCodec.encodeChunk(
            messageId = testMessageId,
            sequenceNumber = 10u,
            totalChunks = 3u,
            payload = "data".encodeToByteArray(),
        )
        assertFailsWith<IllegalArgumentException> {
            WireCodec.decodeChunk(encoded)
        }
    }

    @Test
    fun decodeDeliveryAckRejectsTruncatedSignature() {
        // Craft delivery ACK with flags=0x01 (has signature) but insufficient data
        val header = ByteArray(22) // DELIVERY_ACK_HEADER_SIZE = 22
        header[0] = WireCodec.TYPE_DELIVERY_ACK
        header[21] = 0x01 // FLAG_HAS_SIGNATURE, but no signature bytes follow
        val truncated = header + ByteArray(10) // only 10 bytes, need 64+32=96

        assertFailsWith<IllegalArgumentException> {
            WireCodec.decodeDeliveryAck(truncated)
        }
    }

    // --- Handshake (0x00) ---

    @Test
    fun handshakeEncodesToGoldenBytes() {
        val noisePayload = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
        val encoded = WireCodec.encodeHandshake(
            step = 0u,
            noiseMessage = noisePayload,
        )

        // type(1) + step(1) + noiseMessage(4) = 6
        val expected = byteArrayOf(
            0x00,                   // type: handshake
            0x00,                   // step = 0
            0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()  // noise payload
        )

        assertContentEquals(expected, encoded)
    }

    @Test
    fun handshakeDecodesFromGoldenBytes() {
        val bytes = byteArrayOf(
            0x00,                   // type: handshake
            0x02,                   // step = 2
            0xDE.toByte(), 0xAD.toByte()  // noise payload
        )

        val decoded = WireCodec.decodeHandshake(bytes)

        assertEquals(2u.toUByte(), decoded.step)
        assertContentEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte()), decoded.noiseMessage)
    }

    @Test
    fun handshakeRoundTrips() {
        val noiseMessage = ByteArray(128) { (it % 256).toByte() }
        val encoded = WireCodec.encodeHandshake(step = 1u, noiseMessage = noiseMessage)
        val decoded = WireCodec.decodeHandshake(encoded)

        assertEquals(1u.toUByte(), decoded.step)
        assertContentEquals(noiseMessage, decoded.noiseMessage)
    }

    @Test
    fun handshakeDecodeRejectsTruncated() {
        // Only type byte, no step
        assertFailsWith<IllegalArgumentException> {
            WireCodec.decodeHandshake(byteArrayOf(0x00))
        }
    }

    @Test
    fun handshakeDecodeRejectsWrongType() {
        assertFailsWith<IllegalArgumentException> {
            WireCodec.decodeHandshake(byteArrayOf(0x06, 0x00))
        }
    }

    @Test
    fun handshakeDecodeRejectsInvalidStep() {
        // step = 3 is invalid (only 0, 1, 2 are valid Noise XX steps)
        assertFailsWith<IllegalArgumentException> {
            WireCodec.decodeHandshake(byteArrayOf(0x00, 0x03, 0xAA.toByte()))
        }
    }

    @Test
    fun handshakeWithEmptyNoiseMessage() {
        val encoded = WireCodec.encodeHandshake(step = 0u, noiseMessage = ByteArray(0))
        val decoded = WireCodec.decodeHandshake(encoded)

        assertEquals(0u.toUByte(), decoded.step)
        assertEquals(0, decoded.noiseMessage.size)
    }

    // --- ResumeRequest tests ---

    @Test
    fun resumeRequestEncodesToGoldenBytes() {
        val encoded = WireCodec.encodeResumeRequest(
            messageId = testMessageId,
            bytesReceived = 0x04030201u,
        )

        // type(1) + messageId(12) + bytesReceived(4 LE) + ext(2) = 19
        val expected = byteArrayOf(
            0x08,                                                           // type: resume_request
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,               // messageId[0..7]
            0x08, 0x09, 0x0A, 0x0B,                                        // messageId[8..11]
            0x01, 0x02, 0x03, 0x04,                                         // bytesReceived = 0x04030201 (LE)
            0x00, 0x00                                                      // empty TLV extensions
        )

        assertContentEquals(expected, encoded)
    }

    @Test
    fun resumeRequestRoundTrip() {
        val messageId = ByteArray(12) { (0xA0 + it).toByte() }
        val bytesReceived = 123456u

        val encoded = WireCodec.encodeResumeRequest(messageId, bytesReceived)
        val decoded = WireCodec.decodeResumeRequest(encoded)

        assertContentEquals(messageId, decoded.messageId)
        assertEquals(bytesReceived, decoded.bytesReceived)
    }

    @Test
    fun resumeRequestRejectsTooShort() {
        val tooShort = ByteArray(16) { 0x08 }
        assertFailsWith<IllegalArgumentException> {
            WireCodec.decodeResumeRequest(tooShort)
        }
    }

    @Test
    fun resumeRequestTypeByte() {
        val encoded = WireCodec.encodeResumeRequest(
            messageId = testMessageId,
            bytesReceived = 0u,
        )
        assertEquals(0x08, encoded[0])
    }
}
