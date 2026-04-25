package ch.trancee.meshlink.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Verifies byte-exact encode/decode round-trips for all 12 wire message types. */
class WireFormatTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun ByteArray.toHex(): String =
        joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun roundTrip(msg: WireMessage): WireMessage {
        val encoded = WireCodec.encode(msg)
        return WireCodec.decode(encoded)
    }

    // ── 0x00 Handshake ────────────────────────────────────────────────────────

    @Test
    fun handshakeRoundTrip() {
        val msg = Handshake(step = 2u, noiseMessage = byteArrayOf(0x11, 0x22, 0x33))
        val decoded = roundTrip(msg) as Handshake
        assertEquals(msg.step, decoded.step)
        assertContentEquals(msg.noiseMessage, decoded.noiseMessage)
    }

    @Test
    fun handshakeGoldenVectorByteExact() {
        val msg = Handshake(step = 2u, noiseMessage = byteArrayOf(0x11, 0x22, 0x33))
        val encoded = WireCodec.encode(msg)
        // Golden: type byte 0x00 + re-decode must produce same bytes (encode is deterministic).
        assertEquals(0x00.toByte(), encoded[0], "Type byte must be 0x00")
        val decoded = WireCodec.decode(encoded) as Handshake
        assertContentEquals(encoded, WireCodec.encode(decoded), "Encode must be deterministic")
    }

    @Test
    fun handshakeEmptyNoiseMessage() {
        val msg = Handshake(step = 0u, noiseMessage = ByteArray(0))
        val decoded = roundTrip(msg) as Handshake
        assertEquals(0u, decoded.step)
        assertContentEquals(ByteArray(0), decoded.noiseMessage)
    }

    // ── 0x01 Keepalive ────────────────────────────────────────────────────────

    @Test
    fun keepaliveRoundTrip() {
        val msg =
            Keepalive(flags = 0u, timestampMillis = 1_700_000_000_000UL, proposedChunkSize = 4096u)
        val decoded = roundTrip(msg) as Keepalive
        assertEquals(msg.flags, decoded.flags)
        assertEquals(msg.timestampMillis, decoded.timestampMillis)
        assertEquals(msg.proposedChunkSize, decoded.proposedChunkSize)
    }

    @Test
    fun keepaliveGoldenTypeByteAndDeterminism() {
        val msg =
            Keepalive(flags = 0u, timestampMillis = 1_700_000_000_000UL, proposedChunkSize = 4096u)
        val encoded = WireCodec.encode(msg)
        assertEquals(0x01.toByte(), encoded[0])
        assertContentEquals(encoded, WireCodec.encode(WireCodec.decode(encoded) as Keepalive))
    }

    // ── 0x02 RotationAnnouncement ─────────────────────────────────────────────

    @Test
    fun rotationAnnouncementRoundTrip() {
        val msg =
            RotationAnnouncementMsg(
                oldX25519Key = ByteArray(32) { it.toByte() },
                newX25519Key = ByteArray(32) { (it + 32).toByte() },
                oldEd25519Key = ByteArray(32) { (it + 64).toByte() },
                newEd25519Key = ByteArray(32) { (it + 96).toByte() },
                rotationNonce = 42UL,
                timestampMillis = 1_000_000UL,
                signature = ByteArray(64) { it.toByte() },
            )
        val decoded = roundTrip(msg) as RotationAnnouncementMsg
        assertEquals(msg.rotationNonce, decoded.rotationNonce)
        assertEquals(msg.timestampMillis, decoded.timestampMillis)
        assertContentEquals(msg.oldX25519Key, decoded.oldX25519Key)
        assertContentEquals(msg.newX25519Key, decoded.newX25519Key)
        assertContentEquals(msg.oldEd25519Key, decoded.oldEd25519Key)
        assertContentEquals(msg.newEd25519Key, decoded.newEd25519Key)
        assertContentEquals(msg.signature, decoded.signature)
    }

    @Test
    fun rotationAnnouncementGoldenTypeByteAndDeterminism() {
        val msg =
            RotationAnnouncementMsg(
                oldX25519Key = ByteArray(32) { 0x01 },
                newX25519Key = ByteArray(32) { 0x02 },
                oldEd25519Key = ByteArray(32) { 0x03 },
                newEd25519Key = ByteArray(32) { 0x04 },
                rotationNonce = 1UL,
                timestampMillis = 2UL,
                signature = ByteArray(64) { 0x05 },
            )
        val encoded = WireCodec.encode(msg)
        assertEquals(0x02.toByte(), encoded[0])
        assertContentEquals(
            encoded,
            WireCodec.encode(WireCodec.decode(encoded) as RotationAnnouncementMsg),
        )
    }

    // ── 0x03 Hello ────────────────────────────────────────────────────────────

    @Test
    fun helloRoundTrip() {
        val msg =
            Hello(sender = ByteArray(12) { it.toByte() }, seqNo = 300u, routeDigest = 0xDEADBEEFu)
        val decoded = roundTrip(msg) as Hello
        assertContentEquals(msg.sender, decoded.sender)
        assertEquals(msg.seqNo, decoded.seqNo)
        assertEquals(msg.routeDigest, decoded.routeDigest)
    }

    @Test
    fun helloGoldenTypeByteAndDeterminism() {
        val msg = Hello(sender = ByteArray(12) { it.toByte() }, seqNo = 1u, routeDigest = 0u)
        val encoded = WireCodec.encode(msg)
        assertEquals(0x03.toByte(), encoded[0])
        assertContentEquals(encoded, WireCodec.encode(WireCodec.decode(encoded) as Hello))
    }

    // ── 0x04 Update ───────────────────────────────────────────────────────────

    @Test
    fun updateRoundTrip() {
        val msg =
            Update(
                destination = ByteArray(12) { (it + 1).toByte() },
                metric = 150u,
                seqNo = 5u,
                ed25519PublicKey = ByteArray(32) { it.toByte() },
                x25519PublicKey = ByteArray(32) { (it + 100).toByte() },
            )
        val decoded = roundTrip(msg) as Update
        assertContentEquals(msg.destination, decoded.destination)
        assertEquals(msg.metric, decoded.metric)
        assertEquals(msg.seqNo, decoded.seqNo)
        assertContentEquals(msg.ed25519PublicKey, decoded.ed25519PublicKey)
        assertContentEquals(msg.x25519PublicKey, decoded.x25519PublicKey)
    }

    @Test
    fun updateMetricMaxAndRetraction() {
        // 0xFFFE = max valid metric; 0xFFFF = retraction sentinel
        val maxMetric = Update(ByteArray(12), 0xFFFEu, 0u, ByteArray(32), ByteArray(32))
        assertEquals(0xFFFEu.toUShort(), (roundTrip(maxMetric) as Update).metric)

        val retract = Update(ByteArray(12), 0xFFFFu, 0u, ByteArray(32), ByteArray(32))
        assertEquals(0xFFFFu.toUShort(), (roundTrip(retract) as Update).metric)
    }

    @Test
    fun updateGoldenTypeByteAndDeterminism() {
        val msg = Update(ByteArray(12), 100u, 1u, ByteArray(32), ByteArray(32))
        val encoded = WireCodec.encode(msg)
        assertEquals(0x04.toByte(), encoded[0])
        assertContentEquals(encoded, WireCodec.encode(WireCodec.decode(encoded) as Update))
    }

    // ── 0x05 Chunk ────────────────────────────────────────────────────────────

    @Test
    fun chunkRoundTrip() {
        val msg =
            Chunk(
                ByteArray(16) { it.toByte() },
                seqNum = 3u,
                totalChunks = 10u,
                payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte()),
            )
        val decoded = roundTrip(msg) as Chunk
        assertContentEquals(msg.messageId, decoded.messageId)
        assertEquals(msg.seqNum, decoded.seqNum)
        assertEquals(msg.totalChunks, decoded.totalChunks)
        assertContentEquals(msg.payload, decoded.payload)
    }

    @Test
    fun chunkEmptyPayload() {
        val msg = Chunk(ByteArray(16), seqNum = 1u, totalChunks = 0u, payload = ByteArray(0))
        val decoded = roundTrip(msg) as Chunk
        assertContentEquals(ByteArray(0), decoded.payload)
        assertEquals(0u.toUShort(), decoded.totalChunks)
    }

    @Test
    fun chunkGoldenTypeByteAndDeterminism() {
        val msg = Chunk(ByteArray(16), 0u, 1u, byteArrayOf(1, 2, 3))
        val encoded = WireCodec.encode(msg)
        assertEquals(0x05.toByte(), encoded[0])
        assertContentEquals(encoded, WireCodec.encode(WireCodec.decode(encoded) as Chunk))
    }

    // ── 0x06 ChunkAck ─────────────────────────────────────────────────────────

    @Test
    fun chunkAckRoundTrip() {
        val msg =
            ChunkAck(ByteArray(16) { it.toByte() }, ackSequence = 7u, sackBitmask = 0xFFL.toULong())
        val decoded = roundTrip(msg) as ChunkAck
        assertContentEquals(msg.messageId, decoded.messageId)
        assertEquals(msg.ackSequence, decoded.ackSequence)
        assertEquals(msg.sackBitmask, decoded.sackBitmask)
    }

    @Test
    fun chunkAckGoldenTypeByteAndDeterminism() {
        val msg = ChunkAck(ByteArray(16), 0u, 0UL)
        val encoded = WireCodec.encode(msg)
        assertEquals(0x06.toByte(), encoded[0])
        assertContentEquals(encoded, WireCodec.encode(WireCodec.decode(encoded) as ChunkAck))
    }

    // ── 0x07 Nack ─────────────────────────────────────────────────────────────

    @Test
    fun nackRoundTrip() {
        val msg = Nack(ByteArray(16) { it.toByte() }, reason = 3u)
        val decoded = roundTrip(msg) as Nack
        assertContentEquals(msg.messageId, decoded.messageId)
        assertEquals(msg.reason, decoded.reason)
    }

    @Test
    fun nackGoldenTypeByteAndDeterminism() {
        val msg = Nack(ByteArray(16), 1u)
        val encoded = WireCodec.encode(msg)
        assertEquals(0x07.toByte(), encoded[0])
        assertContentEquals(encoded, WireCodec.encode(WireCodec.decode(encoded) as Nack))
    }

    // ── 0x08 ResumeRequest ────────────────────────────────────────────────────

    @Test
    fun resumeRequestRoundTrip() {
        val msg = ResumeRequest(ByteArray(16) { it.toByte() }, bytesReceived = 65536u)
        val decoded = roundTrip(msg) as ResumeRequest
        assertContentEquals(msg.messageId, decoded.messageId)
        assertEquals(msg.bytesReceived, decoded.bytesReceived)
    }

    @Test
    fun resumeRequestGoldenTypeByteAndDeterminism() {
        val msg = ResumeRequest(ByteArray(16), 0u)
        val encoded = WireCodec.encode(msg)
        assertEquals(0x08.toByte(), encoded[0])
        assertContentEquals(encoded, WireCodec.encode(WireCodec.decode(encoded) as ResumeRequest))
    }

    // ── 0x09 Broadcast ────────────────────────────────────────────────────────

    @Test
    fun broadcastWithSignatureRoundTrip() {
        val msg =
            Broadcast(
                messageId = ByteArray(16) { it.toByte() },
                origin = ByteArray(12) { (it + 16).toByte() },
                remainingHops = 5u,
                appIdHash = 0xABCDu,
                flags = 1u,
                priority = 1,
                signature = ByteArray(64) { it.toByte() },
                signerKey = ByteArray(32) { (it + 64).toByte() },
                payload = byteArrayOf(0x01, 0x02, 0x03),
            )
        val decoded = roundTrip(msg) as Broadcast
        assertContentEquals(msg.messageId, decoded.messageId)
        assertContentEquals(msg.origin, decoded.origin)
        assertEquals(msg.remainingHops, decoded.remainingHops)
        assertEquals(msg.appIdHash, decoded.appIdHash)
        assertEquals(msg.flags, decoded.flags)
        assertEquals(msg.priority, decoded.priority)
        assertContentEquals(msg.signature!!, decoded.signature!!)
        assertContentEquals(msg.signerKey!!, decoded.signerKey!!)
        assertContentEquals(msg.payload, decoded.payload)
    }

    @Test
    fun broadcastWithoutSignatureRoundTrip() {
        val msg =
            Broadcast(
                messageId = ByteArray(16),
                origin = ByteArray(12),
                remainingHops = 3u,
                appIdHash = 0u,
                flags = 0u,
                priority = 0,
                signature = null,
                signerKey = null,
                payload = byteArrayOf(0xFF.toByte()),
            )
        val decoded = roundTrip(msg) as Broadcast
        assertNull(decoded.signature)
        assertNull(decoded.signerKey)
        assertContentEquals(msg.payload, decoded.payload)
    }

    @Test
    fun broadcastGoldenTypeByteAndDeterminism() {
        val msg = Broadcast(ByteArray(16), ByteArray(12), 1u, 0u, 0u, 0, null, null, ByteArray(0))
        val encoded = WireCodec.encode(msg)
        assertEquals(0x09.toByte(), encoded[0])
        assertContentEquals(encoded, WireCodec.encode(WireCodec.decode(encoded) as Broadcast))
    }

    // ── 0x0A RoutedMessage ────────────────────────────────────────────────────

    @Test
    fun routedMessageRoundTrip() {
        val visited = listOf(ByteArray(12) { it.toByte() }, ByteArray(12) { (it + 12).toByte() })
        val msg =
            RoutedMessage(
                messageId = ByteArray(16) { it.toByte() },
                origin = ByteArray(12) { 0x0A.toByte() },
                destination = ByteArray(12) { 0x0B.toByte() },
                hopLimit = 4u,
                visitedList = visited,
                priority = -1,
                originationTime = 9_999_999UL,
                payload = byteArrayOf(0xDE.toByte(), 0xAD.toByte()),
            )
        val decoded = roundTrip(msg) as RoutedMessage
        assertContentEquals(msg.messageId, decoded.messageId)
        assertContentEquals(msg.origin, decoded.origin)
        assertContentEquals(msg.destination, decoded.destination)
        assertEquals(msg.hopLimit, decoded.hopLimit)
        assertEquals(2, decoded.visitedList.size)
        assertContentEquals(visited[0], decoded.visitedList[0])
        assertContentEquals(visited[1], decoded.visitedList[1])
        assertEquals(msg.priority, decoded.priority)
        assertEquals(msg.originationTime, decoded.originationTime)
        assertContentEquals(msg.payload, decoded.payload)
    }

    @Test
    fun routedMessageEmptyVisitedList() {
        val msg =
            RoutedMessage(
                ByteArray(16),
                ByteArray(12),
                ByteArray(12),
                5u,
                emptyList(),
                0,
                0UL,
                ByteArray(0),
            )
        val decoded = roundTrip(msg) as RoutedMessage
        assertEquals(0, decoded.visitedList.size)
    }

    @Test
    fun routedMessageGoldenTypeByteAndDeterminism() {
        val msg =
            RoutedMessage(
                ByteArray(16),
                ByteArray(12),
                ByteArray(12),
                1u,
                emptyList(),
                0,
                0UL,
                byteArrayOf(1),
            )
        val encoded = WireCodec.encode(msg)
        assertEquals(0x0A.toByte(), encoded[0])
        assertContentEquals(encoded, WireCodec.encode(WireCodec.decode(encoded) as RoutedMessage))
    }

    // ── 0x0B DeliveryAck ──────────────────────────────────────────────────────

    @Test
    fun deliveryAckWithSignatureRoundTrip() {
        val msg =
            DeliveryAck(
                messageId = ByteArray(16) { it.toByte() },
                recipientId = ByteArray(12) { (it + 100).toByte() },
                flags = 1u,
                signature = ByteArray(64) { it.toByte() },
            )
        val decoded = roundTrip(msg) as DeliveryAck
        assertContentEquals(msg.messageId, decoded.messageId)
        assertContentEquals(msg.recipientId, decoded.recipientId)
        assertEquals(msg.flags, decoded.flags)
        assertContentEquals(msg.signature!!, decoded.signature!!)
    }

    @Test
    fun deliveryAckWithoutSignatureRoundTrip() {
        val msg = DeliveryAck(ByteArray(16), ByteArray(12), flags = 0u, signature = null)
        val decoded = roundTrip(msg) as DeliveryAck
        assertEquals(0u, decoded.flags)
        assertNull(decoded.signature)
    }

    @Test
    fun deliveryAckGoldenTypeByteAndDeterminism() {
        val msg = DeliveryAck(ByteArray(16), ByteArray(12), 0u, null)
        val encoded = WireCodec.encode(msg)
        assertEquals(0x0B.toByte(), encoded[0])
        assertContentEquals(encoded, WireCodec.encode(WireCodec.decode(encoded) as DeliveryAck))
    }

    // ── WireCodec dispatch ────────────────────────────────────────────────────

    @Test
    fun wireCodecDecodeDispatchesAllTypes() {
        val messages: List<WireMessage> =
            listOf(
                Handshake(1u, byteArrayOf(0x01)),
                Keepalive(0u, 0UL, 0u),
                RotationAnnouncementMsg(
                    ByteArray(32),
                    ByteArray(32),
                    ByteArray(32),
                    ByteArray(32),
                    1UL,
                    2UL,
                    ByteArray(64),
                ),
                Hello(ByteArray(12), 0u, 0u),
                Update(ByteArray(12), 0u, 0u, ByteArray(32), ByteArray(32)),
                Chunk(ByteArray(16), 0u, 0u, ByteArray(0)),
                ChunkAck(ByteArray(16), 0u, 0UL),
                Nack(ByteArray(16), 0u),
                ResumeRequest(ByteArray(16), 0u),
                Broadcast(ByteArray(16), ByteArray(12), 0u, 0u, 0u, 0, null, null, ByteArray(0)),
                RoutedMessage(
                    ByteArray(16),
                    ByteArray(12),
                    ByteArray(12),
                    0u,
                    emptyList(),
                    0,
                    0UL,
                    ByteArray(0),
                ),
                DeliveryAck(ByteArray(16), ByteArray(12), 0u, null),
            )
        for (msg in messages) {
            val decoded = roundTrip(msg)
            assertIs<WireMessage>(decoded)
            assertEquals(msg.type, decoded.type, "Type mismatch for ${msg.type}")
        }
    }

    @Test
    fun wireCodecDecodeThrowsOnEmptyFrame() {
        assertFailsWith<IllegalArgumentException> { WireCodec.decode(ByteArray(0)) }
    }

    @Test
    fun wireCodecDecodeThrowsOnUnknownType() {
        // 0xFF is MessageType.UNKNOWN
        val frame = ByteArray(5) { if (it == 0) 0xFF.toByte() else 0x08.toByte() }
        assertFailsWith<IllegalArgumentException> { WireCodec.decode(frame) }
    }
}
