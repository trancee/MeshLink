package ch.trancee.meshlink.wire

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Tests for [InboundValidator]. Covers:
 * - Valid frame for each of the 12 message types → [Valid]
 * - Structural rejection: buffer too short, unknown type, malformed FlatBuffers
 * - Field-size rejection: every fixed-size field in every type
 * - Optional-field absent paths (Broadcast/DeliveryAck without signature)
 * - visitedList divisibility check (RoutedMessage)
 * - Fuzz: 100 seeded random byte arrays — no exceptions, all return ValidationResult
 */
class InboundValidatorTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun validate(msg: WireMessage): ValidationResult =
        InboundValidator.validate(WireCodec.encode(msg))

    private fun assertValid(msg: WireMessage): Valid {
        val result = validate(msg)
        assertIs<Valid>(result, "Expected Valid but got $result")
        return result
    }

    private fun assertRejected(result: ValidationResult): Rejected {
        assertIs<Rejected>(result, "Expected Rejected but got $result")
        return result
    }

    // ── 0x00 Handshake — no fixed-size constraints ────────────────────────────

    @Test
    fun handshakeValidFrame() {
        val msg = Handshake(step = 1u, noiseMessage = ByteArray(32) { it.toByte() })
        val result = assertValid(msg)
        assertIs<Handshake>(result.message)
    }

    // ── 0x01 Keepalive — no byte-array fields ─────────────────────────────────

    @Test
    fun keepaliveValidFrame() {
        val msg =
            Keepalive(flags = 0u, timestampMillis = 1_700_000_000_000UL, proposedChunkSize = 1024u)
        val result = assertValid(msg)
        assertIs<Keepalive>(result.message)
    }

    // ── 0x02 RotationAnnouncement ─────────────────────────────────────────────

    @Test
    fun rotationAnnouncementValidFrame() {
        val msg =
            RotationAnnouncementMsg(
                oldX25519Key = ByteArray(32) { 1 },
                newX25519Key = ByteArray(32) { 2 },
                oldEd25519Key = ByteArray(32) { 3 },
                newEd25519Key = ByteArray(32) { 4 },
                rotationNonce = 1UL,
                timestampMillis = 1_700_000_000_000UL,
                signature = ByteArray(64) { 5 },
            )
        assertValid(msg)
    }

    @Test
    fun rotationAnnouncementWrongOldX25519Key() {
        val msg =
            RotationAnnouncementMsg(
                oldX25519Key = ByteArray(31), // wrong — one byte short
                newX25519Key = ByteArray(32),
                oldEd25519Key = ByteArray(32),
                newEd25519Key = ByteArray(32),
                rotationNonce = 1UL,
                timestampMillis = 0UL,
                signature = ByteArray(64),
            )
        val r = assertRejected(validate(msg))
        assertIs<InvalidFieldSize>(r)
        assertEquals("oldX25519Key", r.field)
        assertEquals(32, r.expected)
        assertEquals(31, r.actual)
    }

    @Test
    fun rotationAnnouncementWrongNewX25519Key() {
        val msg =
            RotationAnnouncementMsg(
                oldX25519Key = ByteArray(32), // correct
                newX25519Key = ByteArray(31), // wrong
                oldEd25519Key = ByteArray(32),
                newEd25519Key = ByteArray(32),
                rotationNonce = 1UL,
                timestampMillis = 0UL,
                signature = ByteArray(64),
            )
        val r = assertRejected(validate(msg))
        assertIs<InvalidFieldSize>(r)
        assertEquals("newX25519Key", r.field)
    }

    @Test
    fun rotationAnnouncementWrongOldEd25519Key() {
        val msg =
            RotationAnnouncementMsg(
                oldX25519Key = ByteArray(32),
                newX25519Key = ByteArray(32), // correct
                oldEd25519Key = ByteArray(31), // wrong
                newEd25519Key = ByteArray(32),
                rotationNonce = 1UL,
                timestampMillis = 0UL,
                signature = ByteArray(64),
            )
        val r = assertRejected(validate(msg))
        assertIs<InvalidFieldSize>(r)
        assertEquals("oldEd25519Key", r.field)
    }

    @Test
    fun rotationAnnouncementWrongNewEd25519Key() {
        val msg =
            RotationAnnouncementMsg(
                oldX25519Key = ByteArray(32),
                newX25519Key = ByteArray(32),
                oldEd25519Key = ByteArray(32), // correct
                newEd25519Key = ByteArray(31), // wrong
                rotationNonce = 1UL,
                timestampMillis = 0UL,
                signature = ByteArray(64),
            )
        val r = assertRejected(validate(msg))
        assertIs<InvalidFieldSize>(r)
        assertEquals("newEd25519Key", r.field)
    }

    @Test
    fun rotationAnnouncementWrongSignature() {
        val msg =
            RotationAnnouncementMsg(
                oldX25519Key = ByteArray(32),
                newX25519Key = ByteArray(32),
                oldEd25519Key = ByteArray(32),
                newEd25519Key = ByteArray(32), // all correct
                rotationNonce = 1UL,
                timestampMillis = 0UL,
                signature = ByteArray(63), // one byte short — exactly 63 bytes
            )
        val r = assertRejected(validate(msg))
        assertIs<InvalidFieldSize>(r)
        assertEquals("signature", r.field)
        assertEquals(64, r.expected)
        assertEquals(63, r.actual)
    }

    // ── 0x03 Hello ────────────────────────────────────────────────────────────

    @Test
    fun helloValidFrame() {
        val msg =
            Hello(sender = ByteArray(12) { it.toByte() }, seqNo = 42u, routeDigest = 0xDEADBEEFu)
        assertValid(msg)
    }

    @Test
    fun helloWrongSenderSize() {
        // sender must be exactly 12 bytes — test with 11 bytes (one short)
        val msg = Hello(sender = ByteArray(11), seqNo = 1u, routeDigest = 0u)
        val r = assertRejected(validate(msg))
        assertIs<InvalidFieldSize>(r)
        assertEquals("sender", r.field)
        assertEquals(12, r.expected)
        assertEquals(11, r.actual)
    }

    // ── 0x04 Update ───────────────────────────────────────────────────────────

    @Test
    fun updateValidFrame() {
        val msg =
            Update(
                destination = ByteArray(12),
                metric = 100u,
                seqNo = 1u,
                ed25519PublicKey = ByteArray(32),
                x25519PublicKey = ByteArray(32),
            )
        assertValid(msg)
    }

    @Test
    fun updateWrongDestination() {
        val msg =
            Update(
                destination = ByteArray(11), // wrong
                metric = 0u,
                seqNo = 0u,
                ed25519PublicKey = ByteArray(32),
                x25519PublicKey = ByteArray(32),
            )
        val r = assertRejected(validate(msg))
        assertIs<InvalidFieldSize>(r)
        assertEquals("destination", r.field)
    }

    @Test
    fun updateWrongEd25519Key() {
        val msg =
            Update(
                destination = ByteArray(12), // correct
                metric = 0u,
                seqNo = 0u,
                ed25519PublicKey = ByteArray(31), // wrong
                x25519PublicKey = ByteArray(32),
            )
        val r = assertRejected(validate(msg))
        assertIs<InvalidFieldSize>(r)
        assertEquals("ed25519PublicKey", r.field)
    }

    @Test
    fun updateWrongX25519Key() {
        val msg =
            Update(
                destination = ByteArray(12),
                metric = 0u,
                seqNo = 0u,
                ed25519PublicKey = ByteArray(32), // correct
                x25519PublicKey = ByteArray(31), // wrong
            )
        val r = assertRejected(validate(msg))
        assertIs<InvalidFieldSize>(r)
        assertEquals("x25519PublicKey", r.field)
    }

    // ── 0x05 Chunk ────────────────────────────────────────────────────────────

    @Test
    fun chunkValidFrame() {
        val msg =
            Chunk(
                messageId = ByteArray(16),
                seqNum = 0u,
                totalChunks = 3u,
                payload = byteArrayOf(0x42),
            )
        assertValid(msg)
    }

    @Test
    fun chunkWrongMessageId() {
        // messageId exactly 15 bytes — one short
        val msg =
            Chunk(messageId = ByteArray(15), seqNum = 0u, totalChunks = 1u, payload = ByteArray(0))
        val r = assertRejected(validate(msg))
        assertIs<InvalidFieldSize>(r)
        assertEquals("messageId", r.field)
        assertEquals(16, r.expected)
        assertEquals(15, r.actual)
    }

    // ── 0x06 ChunkAck ─────────────────────────────────────────────────────────

    @Test
    fun chunkAckValidFrame() {
        val msg = ChunkAck(messageId = ByteArray(16), ackSequence = 3u, sackBitmask = 0b111UL)
        assertValid(msg)
    }

    @Test
    fun chunkAckWrongMessageId() {
        val msg = ChunkAck(messageId = ByteArray(15), ackSequence = 0u, sackBitmask = 0UL)
        val r = assertRejected(validate(msg))
        assertIs<InvalidFieldSize>(r)
        assertEquals("messageId", r.field)
    }

    // ── 0x07 Nack ─────────────────────────────────────────────────────────────

    @Test
    fun nackValidFrame() {
        val msg = Nack(messageId = ByteArray(16), reason = 2u)
        assertValid(msg)
    }

    @Test
    fun nackWrongMessageId() {
        val msg = Nack(messageId = ByteArray(15), reason = 0u)
        val r = assertRejected(validate(msg))
        assertIs<InvalidFieldSize>(r)
        assertEquals("messageId", r.field)
    }

    // ── 0x08 ResumeRequest ────────────────────────────────────────────────────

    @Test
    fun resumeRequestValidFrame() {
        val msg = ResumeRequest(messageId = ByteArray(16), bytesReceived = 4096u)
        assertValid(msg)
    }

    @Test
    fun resumeRequestWrongMessageId() {
        val msg = ResumeRequest(messageId = ByteArray(15), bytesReceived = 0u)
        val r = assertRejected(validate(msg))
        assertIs<InvalidFieldSize>(r)
        assertEquals("messageId", r.field)
    }

    // ── 0x09 Broadcast ───────────────────────────────────────────────────────

    @Test
    fun broadcastValidFrameWithSignature() {
        val msg =
            Broadcast(
                messageId = ByteArray(16),
                origin = ByteArray(12),
                remainingHops = 3u,
                appIdHash = 0x1234u,
                flags = 1u,
                priority = 0,
                signature = ByteArray(64),
                signerKey = ByteArray(32),
                payload = byteArrayOf(0xAB.toByte()),
            )
        assertValid(msg)
    }

    @Test
    fun broadcastValidFrameWithoutSignature() {
        // Optional fields absent — covers the null path in checkSize for fields 6 and 7.
        val msg =
            Broadcast(
                messageId = ByteArray(16),
                origin = ByteArray(12),
                remainingHops = 2u,
                appIdHash = 0u,
                flags = 0u,
                priority = 0,
                signature = null,
                signerKey = null,
                payload = byteArrayOf(0x01),
            )
        assertValid(msg)
    }

    @Test
    fun broadcastWrongMessageId() {
        val msg =
            Broadcast(
                messageId = ByteArray(15), // wrong
                origin = ByteArray(12),
                remainingHops = 1u,
                appIdHash = 0u,
                flags = 0u,
                priority = 0,
                signature = null,
                signerKey = null,
                payload = ByteArray(1),
            )
        val r = assertRejected(validate(msg))
        assertIs<InvalidFieldSize>(r)
        assertEquals("messageId", r.field)
    }

    @Test
    fun broadcastWrongOrigin() {
        val msg =
            Broadcast(
                messageId = ByteArray(16), // correct
                origin = ByteArray(11), // wrong
                remainingHops = 1u,
                appIdHash = 0u,
                flags = 0u,
                priority = 0,
                signature = null,
                signerKey = null,
                payload = ByteArray(1),
            )
        val r = assertRejected(validate(msg))
        assertIs<InvalidFieldSize>(r)
        assertEquals("origin", r.field)
    }

    @Test
    fun broadcastWrongSignature() {
        val msg =
            Broadcast(
                messageId = ByteArray(16),
                origin = ByteArray(12), // correct
                remainingHops = 1u,
                appIdHash = 0u,
                flags = 1u,
                priority = 0,
                signature = ByteArray(63), // wrong — exactly 63 bytes
                signerKey = ByteArray(32),
                payload = ByteArray(1),
            )
        val r = assertRejected(validate(msg))
        assertIs<InvalidFieldSize>(r)
        assertEquals("signature", r.field)
        assertEquals(64, r.expected)
        assertEquals(63, r.actual)
    }

    @Test
    fun broadcastWrongSignerKey() {
        val msg =
            Broadcast(
                messageId = ByteArray(16),
                origin = ByteArray(12),
                remainingHops = 1u,
                appIdHash = 0u,
                flags = 1u,
                priority = 0,
                signature = ByteArray(64), // correct
                signerKey = ByteArray(31), // wrong
                payload = ByteArray(1),
            )
        val r = assertRejected(validate(msg))
        assertIs<InvalidFieldSize>(r)
        assertEquals("signerKey", r.field)
    }

    // ── 0x0A RoutedMessage ────────────────────────────────────────────────────

    @Test
    fun routedMessageValidFrame() {
        val msg =
            RoutedMessage(
                messageId = ByteArray(16),
                origin = ByteArray(12),
                destination = ByteArray(12),
                hopLimit = 5u,
                visitedList = listOf(ByteArray(12), ByteArray(12)),
                priority = 0,
                originationTime = 1_700_000_000_000UL,
                payload = ByteArray(32),
            )
        assertValid(msg)
    }

    @Test
    fun routedMessageWrongMessageId() {
        val msg =
            RoutedMessage(
                messageId = ByteArray(15), // wrong
                origin = ByteArray(12),
                destination = ByteArray(12),
                hopLimit = 1u,
                visitedList = emptyList(),
                priority = 0,
                originationTime = 0UL,
                payload = ByteArray(0),
            )
        val r = assertRejected(validate(msg))
        assertIs<InvalidFieldSize>(r)
        assertEquals("messageId", r.field)
    }

    @Test
    fun routedMessageWrongOrigin() {
        val msg =
            RoutedMessage(
                messageId = ByteArray(16), // correct
                origin = ByteArray(11), // wrong
                destination = ByteArray(12),
                hopLimit = 1u,
                visitedList = emptyList(),
                priority = 0,
                originationTime = 0UL,
                payload = ByteArray(0),
            )
        val r = assertRejected(validate(msg))
        assertIs<InvalidFieldSize>(r)
        assertEquals("origin", r.field)
    }

    @Test
    fun routedMessageWrongDestination() {
        val msg =
            RoutedMessage(
                messageId = ByteArray(16),
                origin = ByteArray(12), // correct
                destination = ByteArray(11), // wrong
                hopLimit = 1u,
                visitedList = emptyList(),
                priority = 0,
                originationTime = 0UL,
                payload = ByteArray(0),
            )
        val r = assertRejected(validate(msg))
        assertIs<InvalidFieldSize>(r)
        assertEquals("destination", r.field)
    }

    @Test
    fun routedMessageVisitedListNotDivisibleBy12() {
        // Construct a frame with visitedList = 13 bytes (not divisible by 12).
        // Must be crafted via WriteBuffer since the encoder always flattens List<ByteArray>.
        val wb = WriteBuffer()
        wb.startTable(8)
        wb.addByteVector(0, ByteArray(16)) // messageId
        wb.addByteVector(1, ByteArray(12)) // origin
        wb.addByteVector(2, ByteArray(12)) // destination
        wb.addUByte(3, 1u) // hopLimit
        wb.addByteVector(4, ByteArray(13)) // visitedList — 13 bytes, not divisible by 12
        wb.addByte(5, 0) // priority
        wb.addULong(6, 0UL) // originationTime
        wb.addByteVector(7, ByteArray(0)) // payload
        val payload = wb.finish()
        val frame = byteArrayOf(MessageType.ROUTED_MESSAGE.code.toByte()) + payload
        val r = assertRejected(InboundValidator.validate(frame))
        assertIs<InvalidFieldSize>(r)
        assertEquals("visitedList", r.field)
        assertEquals(13, r.actual)
    }

    @Test
    fun routedMessageAbsentVisitedList() {
        // visitedList field absent — covers the null path in checkVisitedList.
        val wb = WriteBuffer()
        wb.startTable(8)
        wb.addByteVector(0, ByteArray(16)) // messageId
        wb.addByteVector(1, ByteArray(12)) // origin
        wb.addByteVector(2, ByteArray(12)) // destination
        wb.addUByte(3, 1u) // hopLimit
        // field 4 (visitedList) intentionally absent
        wb.addByte(5, 0) // priority
        wb.addULong(6, 0UL) // originationTime
        wb.addByteVector(7, ByteArray(0)) // payload
        val payload = wb.finish()
        val frame = byteArrayOf(MessageType.ROUTED_MESSAGE.code.toByte()) + payload
        // Absent visitedList is valid: decoded as empty list.
        val result = InboundValidator.validate(frame)
        assertIs<Valid>(result)
    }

    // ── 0x0B DeliveryAck ──────────────────────────────────────────────────────

    @Test
    fun deliveryAckValidFrameWithSignature() {
        val msg =
            DeliveryAck(
                messageId = ByteArray(16),
                recipientId = ByteArray(12),
                flags = 1u,
                signature = ByteArray(64),
            )
        assertValid(msg)
    }

    @Test
    fun deliveryAckValidFrameWithoutSignature() {
        // Optional signature absent — covers the null path in checkSize for field 3.
        val msg =
            DeliveryAck(
                messageId = ByteArray(16),
                recipientId = ByteArray(12),
                flags = 0u,
                signature = null,
            )
        assertValid(msg)
    }

    @Test
    fun deliveryAckWrongMessageId() {
        val msg =
            DeliveryAck(
                messageId = ByteArray(15),
                recipientId = ByteArray(12),
                flags = 0u,
                signature = null,
            )
        val r = assertRejected(validate(msg))
        assertIs<InvalidFieldSize>(r)
        assertEquals("messageId", r.field)
    }

    @Test
    fun deliveryAckWrongRecipientId() {
        val msg =
            DeliveryAck(
                messageId = ByteArray(16), // correct
                recipientId = ByteArray(11), // wrong
                flags = 0u,
                signature = null,
            )
        val r = assertRejected(validate(msg))
        assertIs<InvalidFieldSize>(r)
        assertEquals("recipientId", r.field)
    }

    // ── Structural error cases ────────────────────────────────────────────────

    @Test
    fun emptyBufferIsBufferTooShort() {
        val r = assertRejected(InboundValidator.validate(ByteArray(0)))
        assertIs<BufferTooShort>(r)
        assertEquals(RejectionReason.BUFFER_TOO_SHORT, r.reason)
    }

    @Test
    fun threeByteBufferIsBufferTooShort() {
        val r = assertRejected(InboundValidator.validate(ByteArray(3)))
        assertIs<BufferTooShort>(r)
    }

    @Test
    fun fourByteBufferIsBufferTooShort() {
        // Exactly 4 bytes — one short of the minimum 5.
        val r = assertRejected(InboundValidator.validate(ByteArray(4)))
        assertIs<BufferTooShort>(r)
    }

    @Test
    fun unknownTypeByte() {
        // 0xFF is not a defined MessageType code.
        val frame = byteArrayOf(0xFF.toByte(), 0, 0, 0, 0)
        val r = assertRejected(InboundValidator.validate(frame))
        assertIs<UnknownType>(r)
        assertEquals(0xFF.toByte(), r.typeByte)
        assertEquals(RejectionReason.UNKNOWN_MESSAGE_TYPE, r.reason)
    }

    @Test
    fun validTypeByteTruncatedFlatBuffers() {
        // Type byte 0x05 (CHUNK) followed by a FlatBuffer whose root offset (999) is way out of
        // bounds.
        // ReadBuffer init throws → MalformedStructure.
        val frame =
            byteArrayOf(
                0x05.toByte(), // CHUNK
                0xE7.toByte(),
                0x03,
                0x00,
                0x00, // root_offset = 999, buffer only 4 bytes
            )
        val r = assertRejected(InboundValidator.validate(frame))
        assertIs<MalformedStructure>(r)
        assertEquals(RejectionReason.MALFORMED_FLATBUFFER, r.reason)
        assertNotNull(r.detail)
    }

    // ── Fuzz test ─────────────────────────────────────────────────────────────

    @Test
    fun fuzzRandomByteArraysNeverThrow() {
        // 100 random byte arrays (seeded for reproducibility) must never throw —
        // every input must return a ValidationResult (Valid or Rejected).
        val rng = Random(42)
        for (i in 0 until 100) {
            val size = rng.nextInt(0, 256)
            val bytes = ByteArray(size) { rng.nextInt().toByte() }
            // Must not throw — the result type tells us everything we need.
            val result = InboundValidator.validate(bytes)
            assertNotNull(result)
        }
    }
}
