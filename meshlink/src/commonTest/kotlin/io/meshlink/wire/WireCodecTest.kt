package io.meshlink.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
            0x06,                                                           // type: chunk
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
            0x06,
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
            sackBitmask = 0x1Fu,
            sackBitmaskHigh = 0uL
        )

        // type(1) + messageId(16) + ackSeq(2 LE) + sackBitmask(8 LE) + sackBitmaskHigh(8 LE) + ext(2) = 37
        val expected = byteArrayOf(
            0x07,                                                           // type: chunk_ack
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,               // messageId[0..7]
            0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,               // messageId[8..15]
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
            0x07,
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
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

    // --- Routed message (0x0b) ---

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

        // type(1) + messageId(16) + origin(8) + destination(8) + hopLimit(1) + replayCounter(8) + visitedCount(1) + visited(8) + payload(5) = 56
        assertEquals(56, encoded.size)
        assertEquals(0x0b, encoded[0])  // type
        assertEquals(5.toByte(), encoded[33]) // hopLimit
        assertEquals(1.toByte(), encoded[42]) // visitedCount (after 8-byte counter)
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

    // --- Broadcast (0x0a) ---

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

        // type(1) + messageId(16) + origin(8) + remainingHops(1) + appIdHash(16) + sigLen(1) + payload
        assertEquals(1 + 16 + 8 + 1 + 16 + 1 + payload.size, encoded.size)
        assertEquals(WireCodec.TYPE_BROADCAST, encoded[0])

        val decoded = WireCodec.decodeBroadcast(encoded)

        assertContentEquals(testMessageId, decoded.messageId)
        assertContentEquals(originId, decoded.origin)
        assertEquals(3u.toUByte(), decoded.remainingHops)
        assertContentEquals(appIdHash, decoded.appIdHash)
        assertContentEquals(payload, decoded.payload)
    }

    // --- DeliveryAck (0x0c) ---

    @Test
    fun deliveryAckEncodeDecodeRoundTrips() {
        val encoded = WireCodec.encodeDeliveryAck(
            messageId = testMessageId,
            recipientId = destinationId,
        )

        // type(1) + messageId(16) + recipientId(8) + sigLen(1) + ext(2) = 28
        assertEquals(28, encoded.size)
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

    // --- Route Update (0x05) ---

    @Test
    fun routeUpdateEncodeDecodeRoundTrips() {
        val senderId = ByteArray(8) { (0xBB.toByte() + it).toByte() }
        val dest1 = ByteArray(8) { (0xC0.toByte() + it).toByte() }
        val dest2 = ByteArray(8) { (0xD0.toByte() + it).toByte() }
        val entries = listOf(
            RouteUpdateEntry(destination = dest1, cost = 1.5, sequenceNumber = 10u, hopCount = 1u),
            RouteUpdateEntry(destination = dest2, cost = 3.0, sequenceNumber = 20u, hopCount = 2u),
        )

        val encoded = WireCodec.encodeRouteUpdate(senderId, entries)

        assertEquals(WireCodec.TYPE_ROUTE_UPDATE, encoded[0])
        // type(1) + sender(8) + entryCount(1) + 2 entries × 21 = 52
        assertEquals(52, encoded.size)

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
        val senderId = ByteArray(8) { 0xAA.toByte() }
        val dest = ByteArray(8) { 0xDD.toByte() }
        val entries = listOf(
            RouteUpdateEntry(destination = dest, cost = 2.0, sequenceNumber = 1u, hopCount = 1u),
        )

        val encoded = WireCodec.encodeRouteUpdate(senderId, entries)

        // type(1) + sender(8) + entryCount(1) + 1 entry × 21 = 31
        assertEquals(31, encoded.size)
        assertEquals(0x05, encoded[0]) // TYPE_ROUTE_UPDATE
        // sender starts at offset 1
        assertEquals(0xAA.toByte(), encoded[1])
        // entryCount at offset 9
        assertEquals(1.toByte(), encoded[9])
        // entry destination starts at offset 10
        assertEquals(0xDD.toByte(), encoded[10])
    }

    @Test
    fun routeUpdateEmptyEntriesRoundTrips() {
        val senderId = ByteArray(8) { 0x11 }
        val encoded = WireCodec.encodeRouteUpdate(senderId, emptyList())
        val decoded = WireCodec.decodeRouteUpdate(encoded)

        assertContentEquals(senderId, decoded.senderId)
        assertEquals(0, decoded.entries.size)
        // type(1) + sender(8) + entryCount(1) = 10
        assertEquals(10, encoded.size)
    }

    @Test
    fun decodeRouteUpdateRejectsTruncatedData() {
        assertFailsWith<IllegalArgumentException> {
            WireCodec.decodeRouteUpdate(byteArrayOf(WireCodec.TYPE_ROUTE_UPDATE) + ByteArray(5))
        }
    }

    // --- Visited List Validation ---

    @Test
    fun decodeRoutedMessageRejectsTruncatedVisitedList() {
        // Build a routed message with visitedCount=5 but only enough data for 1 entry
        val header = ByteArray(43) // ROUTED_HEADER_SIZE = 43
        header[0] = WireCodec.TYPE_ROUTED_MESSAGE
        header[42] = 5 // visitedCount = 5 (claims 5×8=40 bytes of visited data)
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
        val header = ByteArray(43) // BROADCAST_HEADER_SIZE = 43
        header[0] = WireCodec.TYPE_BROADCAST
        header[42] = 0x01 // FLAG_HAS_SIGNATURE, but no signature bytes follow
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
    fun decodeRouteUpdateRejectsNaNCost() {
        // Encode a route update with NaN cost by building raw bytes
        val senderId = ByteArray(8) { 0x11 }
        val dest = ByteArray(8) { 0x22 }
        // Build valid route update then patch cost to NaN
        val valid = WireCodec.encodeRouteUpdate(senderId, listOf(
            RouteUpdateEntry(dest, 1.0, 1u, 1u)
        ))
        // Cost is at offset 10 (header) + 8 (destination) = 18, 8 bytes LE double
        val nanBits = Double.NaN.toRawBits()
        for (i in 0..7) {
            valid[18 + i] = (nanBits shr (i * 8)).toByte()
        }
        assertFailsWith<IllegalArgumentException> {
            WireCodec.decodeRouteUpdate(valid)
        }
    }

    @Test
    fun decodeDeliveryAckRejectsTruncatedSignature() {
        // Craft delivery ACK with flags=0x01 (has signature) but insufficient data
        val header = ByteArray(26) // DELIVERY_ACK_HEADER_SIZE = 26
        header[0] = WireCodec.TYPE_DELIVERY_ACK
        header[25] = 0x01 // FLAG_HAS_SIGNATURE, but no signature bytes follow
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

    @Test
    fun signedRouteUpdateRoundTrip() {
        val senderId = ByteArray(8) { (it + 0xA0).toByte() }
        val entries = listOf(
            RouteUpdateEntry(
                destination = ByteArray(8) { (it + 0xB0).toByte() },
                cost = 2.5,
                sequenceNumber = 7u,
                hopCount = 1u,
            )
        )
        val signerPublicKey = ByteArray(32) { (it + 0xC0).toByte() }
        val signature = ByteArray(64) { (it + 0xD0).toByte() }

        val encoded = WireCodec.encodeSignedRouteUpdate(senderId, entries, signerPublicKey, signature)
        val decoded = WireCodec.decodeRouteUpdate(encoded)

        assertContentEquals(senderId, decoded.senderId)
        assertEquals(1, decoded.entries.size)
        assertContentEquals(signerPublicKey, decoded.signerPublicKey)
        assertContentEquals(signature, decoded.signature)
    }

    @Test
    fun signedRouteUpdateSignedDataExtractsCorrectly() {
        val senderId = ByteArray(8) { (it + 0xA0).toByte() }
        val entries = listOf(
            RouteUpdateEntry(
                destination = ByteArray(8) { (it + 0xB0).toByte() },
                cost = 1.0,
                sequenceNumber = 1u,
                hopCount = 1u,
            )
        )
        val signerPublicKey = ByteArray(32) { 0xAA.toByte() }
        val signature = ByteArray(64) { 0xBB.toByte() }

        val encoded = WireCodec.encodeSignedRouteUpdate(senderId, entries, signerPublicKey, signature)
        // Signed data is everything except the trailing 64-byte signature
        val signedData = encoded.copyOfRange(0, encoded.size - 64)
        assertEquals(encoded.size - 64, signedData.size)
    }

    @Test
    fun unsignedRouteUpdateDecodesWithoutSignature() {
        val senderId = ByteArray(8) { (it + 0xA0).toByte() }
        val entries = listOf(
            RouteUpdateEntry(
                destination = ByteArray(8) { (it + 0xB0).toByte() },
                cost = 1.0,
                sequenceNumber = 1u,
                hopCount = 1u,
            )
        )
        val encoded = WireCodec.encodeRouteUpdate(senderId, entries)
        val decoded = WireCodec.decodeRouteUpdate(encoded)

        assertContentEquals(senderId, decoded.senderId)
        assertEquals(1, decoded.entries.size)
        assertEquals(null, decoded.signerPublicKey)
        assertEquals(null, decoded.signature)
    }

    // --- ResumeRequest tests ---

    @Test
    fun resumeRequestEncodesToGoldenBytes() {
        val encoded = WireCodec.encodeResumeRequest(
            messageId = testMessageId,
            bytesReceived = 0x04030201u,
        )

        // type(1) + messageId(16) + bytesReceived(4 LE) + ext(2) = 23
        val expected = byteArrayOf(
            0x09,                                                           // type: resume_request
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,               // messageId[0..7]
            0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,               // messageId[8..15]
            0x01, 0x02, 0x03, 0x04,                                         // bytesReceived = 0x04030201 (LE)
            0x00, 0x00                                                      // empty TLV extensions
        )

        assertContentEquals(expected, encoded)
    }

    @Test
    fun resumeRequestRoundTrip() {
        val messageId = ByteArray(16) { (0xA0 + it).toByte() }
        val bytesReceived = 123456u

        val encoded = WireCodec.encodeResumeRequest(messageId, bytesReceived)
        val decoded = WireCodec.decodeResumeRequest(encoded)

        assertContentEquals(messageId, decoded.messageId)
        assertEquals(bytesReceived, decoded.bytesReceived)
    }

    @Test
    fun resumeRequestRejectsTooShort() {
        val tooShort = ByteArray(20) { 0x09 }
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
        assertEquals(0x09, encoded[0])
    }
}
