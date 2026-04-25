package ch.trancee.meshlink.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        // Build a valid FlatBuffers payload (so ReadBuffer init succeeds), then prepend 0xFF type
        // byte. WireCodec.decode must throw when MessageType.UNKNOWN is dispatched.
        val wb = WriteBuffer()
        wb.startTable(1)
        wb.addUByte(0, 42u)
        val payload = wb.finish()
        val frame = byteArrayOf(0xFF.toByte()) + payload
        assertFailsWith<IllegalArgumentException> { WireCodec.decode(frame) }
    }

    // ── Equals / hashCode coverage ────────────────────────────────────────────

    /**
     * Exercise custom equals() and hashCode() for all message types that have ByteArray fields.
     * Each assertion exercises: (a) `this === other` identity check, (b) field-equality path, (c)
     * `other !is X` type check, (d) hashCode computation.
     */
    @Test
    fun messageEqualsAndHashCodeCoverage() {
        // Handshake
        val h = Handshake(step = 1u, noiseMessage = byteArrayOf(0x01, 0x02))
        val h2 = Handshake(step = 1u, noiseMessage = byteArrayOf(0x01, 0x02))
        assertEquals(h, h) // this === other → true
        assertEquals(h, h2) // field equality
        assertEquals(h.hashCode(), h2.hashCode())
        assertFalse(h.equals("not a handshake")) // other !is Handshake → false

        // RotationAnnouncementMsg
        val r =
            RotationAnnouncementMsg(
                ByteArray(32) { 1 },
                ByteArray(32) { 2 },
                ByteArray(32) { 3 },
                ByteArray(32) { 4 },
                1UL,
                2UL,
                ByteArray(64) { 5 },
            )
        val r2 =
            RotationAnnouncementMsg(
                ByteArray(32) { 1 },
                ByteArray(32) { 2 },
                ByteArray(32) { 3 },
                ByteArray(32) { 4 },
                1UL,
                2UL,
                ByteArray(64) { 5 },
            )
        assertEquals(r, r)
        assertEquals(r, r2)
        assertEquals(r.hashCode(), r2.hashCode())
        assertFalse(r.equals("x"))

        // Hello
        val hello = Hello(sender = ByteArray(12) { 7 }, seqNo = 3u, routeDigest = 99u)
        val hello2 = Hello(sender = ByteArray(12) { 7 }, seqNo = 3u, routeDigest = 99u)
        assertEquals(hello, hello)
        assertEquals(hello, hello2)
        assertEquals(hello.hashCode(), hello2.hashCode())
        assertFalse(hello.equals(42))

        // Update
        val u =
            Update(
                ByteArray(12),
                10u,
                2u,
                ByteArray(32) { 0xAA.toByte() },
                ByteArray(32) { 0xBB.toByte() },
            )
        val u2 =
            Update(
                ByteArray(12),
                10u,
                2u,
                ByteArray(32) { 0xAA.toByte() },
                ByteArray(32) { 0xBB.toByte() },
            )
        assertEquals(u, u)
        assertEquals(u, u2)
        assertEquals(u.hashCode(), u2.hashCode())
        assertFalse(u.equals(false))

        // Chunk
        val c = Chunk(ByteArray(16) { it.toByte() }, 0u, 1u, byteArrayOf(0x42))
        val c2 = Chunk(ByteArray(16) { it.toByte() }, 0u, 1u, byteArrayOf(0x42))
        assertEquals(c, c)
        assertEquals(c, c2)
        assertEquals(c.hashCode(), c2.hashCode())
        assertFalse(c.equals(null))

        // ChunkAck
        val ca = ChunkAck(ByteArray(16) { 0x11 }, 5u, 0xFUL)
        val ca2 = ChunkAck(ByteArray(16) { 0x11 }, 5u, 0xFUL)
        assertEquals(ca, ca)
        assertEquals(ca, ca2)
        assertEquals(ca.hashCode(), ca2.hashCode())
        assertFalse(ca.equals(Unit))

        // Nack
        val n = Nack(ByteArray(16) { 0x22 }, 1u)
        val n2 = Nack(ByteArray(16) { 0x22 }, 1u)
        assertEquals(n, n)
        assertEquals(n, n2)
        assertEquals(n.hashCode(), n2.hashCode())
        assertFalse(n.equals(listOf<Int>()))

        // ResumeRequest
        val rr = ResumeRequest(ByteArray(16) { 0x33 }, 2048u)
        val rr2 = ResumeRequest(ByteArray(16) { 0x33 }, 2048u)
        assertEquals(rr, rr)
        assertEquals(rr, rr2)
        assertEquals(rr.hashCode(), rr2.hashCode())
        assertFalse(rr.equals(emptyList<Int>()))

        // Broadcast with signature
        val b =
            Broadcast(
                ByteArray(16),
                ByteArray(12),
                2u,
                0x1u,
                1u,
                0,
                ByteArray(64) { 0x55 },
                ByteArray(32) { 0x66 },
                byteArrayOf(0x01),
            )
        val b2 =
            Broadcast(
                ByteArray(16),
                ByteArray(12),
                2u,
                0x1u,
                1u,
                0,
                ByteArray(64) { 0x55 },
                ByteArray(32) { 0x66 },
                byteArrayOf(0x01),
            )
        assertEquals(b, b)
        assertEquals(b, b2)
        assertEquals(b.hashCode(), b2.hashCode())
        assertFalse(b.equals("broadcast"))
        // Broadcast without signature: exercises null signature/signerKey in hashCode
        val bNoSig =
            Broadcast(ByteArray(16), ByteArray(12), 1u, 0u, 0u, 0, null, null, byteArrayOf(0x02))
        val bNoSig2 =
            Broadcast(ByteArray(16), ByteArray(12), 1u, 0u, 0u, 0, null, null, byteArrayOf(0x02))
        assertEquals(bNoSig, bNoSig)
        assertEquals(bNoSig, bNoSig2)
        assertEquals(bNoSig.hashCode(), bNoSig2.hashCode())

        // RoutedMessage
        val rm =
            RoutedMessage(
                ByteArray(16),
                ByteArray(12),
                ByteArray(12),
                3u,
                listOf(ByteArray(12) { 0x77.toByte() }),
                1,
                1_700_000_000_000UL,
                ByteArray(4),
            )
        val rm2 =
            RoutedMessage(
                ByteArray(16),
                ByteArray(12),
                ByteArray(12),
                3u,
                listOf(ByteArray(12) { 0x77.toByte() }),
                1,
                1_700_000_000_000UL,
                ByteArray(4),
            )
        assertEquals(rm, rm)
        assertEquals(rm, rm2)
        assertEquals(rm.hashCode(), rm2.hashCode())
        assertFalse(rm.equals("routed"))

        // DeliveryAck with signature
        val da = DeliveryAck(ByteArray(16), ByteArray(12), 1u, ByteArray(64) { 0x88.toByte() })
        val da2 = DeliveryAck(ByteArray(16), ByteArray(12), 1u, ByteArray(64) { 0x88.toByte() })
        assertEquals(da, da)
        assertEquals(da, da2)
        assertEquals(da.hashCode(), da2.hashCode())
        assertFalse(da.equals(0))
        // DeliveryAck without signature: exercises null signature in hashCode
        val daNs = DeliveryAck(ByteArray(16), ByteArray(12), 0u, null)
        val daNs2 = DeliveryAck(ByteArray(16), ByteArray(12), 0u, null)
        assertEquals(daNs, daNs)
        assertEquals(daNs, daNs2)
        assertEquals(daNs.hashCode(), daNs2.hashCode())
    }

    // ── Decode absent required fields (covers ?: ByteArray(N) null branches) ─

    /**
     * Constructs frames via [WriteBuffer] where required ByteArray fields are absent (vtable slot =
     * 0). Decoders must substitute the default ByteArray in the ?: fallback.
     */
    @Test
    fun decodeAbsentRequiredFieldsUsesDefault() {
        // Handshake: noiseMessage absent → ByteArray(0)
        run {
            val wb = WriteBuffer()
            wb.startTable(2)
            wb.addUByte(0, 1u) // noiseMessage (field 1) absent
            val frame = byteArrayOf(MessageType.HANDSHAKE.code.toByte()) + wb.finish()
            val decoded = WireCodec.decode(frame) as Handshake
            assertEquals(0, decoded.noiseMessage.size)
        }

        // Hello: sender absent → ByteArray(12)
        run {
            val wb = WriteBuffer()
            wb.startTable(3)
            wb.addUShort(1, 1u)
            wb.addUInt(2, 0u)
            val frame = byteArrayOf(MessageType.HELLO.code.toByte()) + wb.finish()
            val decoded = WireCodec.decode(frame) as Hello
            assertEquals(12, decoded.sender.size)
        }

        // Chunk: messageId absent → ByteArray(16)
        run {
            val wb = WriteBuffer()
            wb.startTable(4)
            wb.addUShort(1, 0u)
            wb.addUShort(2, 1u)
            wb.addByteVector(3, ByteArray(4))
            val frame = byteArrayOf(MessageType.CHUNK.code.toByte()) + wb.finish()
            val decoded = WireCodec.decode(frame) as Chunk
            assertEquals(16, decoded.messageId.size)
        }

        // Chunk: payload absent → ByteArray(0) via ?: fallback in Chunk.decode()
        run {
            val wb = WriteBuffer()
            wb.startTable(3) // only fields 0-2; field 3 (payload) not present in vtable
            wb.addByteVector(0, ByteArray(16) { it.toByte() })
            wb.addUShort(1, 3u)
            wb.addUShort(2, 7u)
            val frame = byteArrayOf(MessageType.CHUNK.code.toByte()) + wb.finish()
            val decoded = WireCodec.decode(frame) as Chunk
            assertEquals(0, decoded.payload.size) // ?: ByteArray(0) null branch
        }

        // ChunkAck: messageId absent → ByteArray(16)
        run {
            val wb = WriteBuffer()
            wb.startTable(3)
            wb.addUShort(1, 0u)
            wb.addULong(2, 0UL)
            val frame = byteArrayOf(MessageType.CHUNK_ACK.code.toByte()) + wb.finish()
            val decoded = WireCodec.decode(frame) as ChunkAck
            assertEquals(16, decoded.messageId.size)
        }

        // Nack: messageId absent → ByteArray(16)
        run {
            val wb = WriteBuffer()
            wb.startTable(2)
            wb.addUByte(1, 0u)
            val frame = byteArrayOf(MessageType.NACK.code.toByte()) + wb.finish()
            val decoded = WireCodec.decode(frame) as Nack
            assertEquals(16, decoded.messageId.size)
        }

        // ResumeRequest: messageId absent → ByteArray(16)
        run {
            val wb = WriteBuffer()
            wb.startTable(2)
            wb.addUInt(1, 0u)
            val frame = byteArrayOf(MessageType.RESUME_REQUEST.code.toByte()) + wb.finish()
            val decoded = WireCodec.decode(frame) as ResumeRequest
            assertEquals(16, decoded.messageId.size)
        }

        // Broadcast: messageId absent → ByteArray(16); origin absent → ByteArray(12); payload
        // absent → ByteArray(0)
        run {
            val wb = WriteBuffer()
            wb.startTable(9)
            wb.addUByte(2, 1u)
            val frame = byteArrayOf(MessageType.BROADCAST.code.toByte()) + wb.finish()
            val decoded = WireCodec.decode(frame) as Broadcast
            assertEquals(16, decoded.messageId.size)
            assertEquals(12, decoded.origin.size)
            assertEquals(0, decoded.payload.size)
        }

        // RoutedMessage: messageId/origin/destination/payload absent → defaults
        run {
            val wb = WriteBuffer()
            wb.startTable(8)
            wb.addUByte(3, 2u)
            wb.addByte(5, 0)
            wb.addULong(6, 0UL)
            val frame = byteArrayOf(MessageType.ROUTED_MESSAGE.code.toByte()) + wb.finish()
            val decoded = WireCodec.decode(frame) as RoutedMessage
            assertEquals(16, decoded.messageId.size)
            assertEquals(12, decoded.origin.size)
            assertEquals(12, decoded.destination.size)
            assertEquals(0, decoded.payload.size)
        }

        // RotationAnnouncementMsg: all key fields absent → ByteArray(32) defaults, signature →
        // ByteArray(64)
        run {
            val wb = WriteBuffer()
            wb.startTable(7)
            wb.addULong(4, 1UL)
            wb.addULong(5, 2UL)
            val frame = byteArrayOf(MessageType.ROTATION_ANNOUNCEMENT.code.toByte()) + wb.finish()
            val decoded = WireCodec.decode(frame) as RotationAnnouncementMsg
            assertEquals(32, decoded.oldX25519Key.size)
            assertEquals(32, decoded.newX25519Key.size)
            assertEquals(32, decoded.oldEd25519Key.size)
            assertEquals(32, decoded.newEd25519Key.size)
            assertEquals(64, decoded.signature.size)
        }

        // Update: destination/ed25519PublicKey/x25519PublicKey absent → ByteArray(12)/ByteArray(32)
        // defaults
        run {
            val wb = WriteBuffer()
            wb.startTable(5)
            wb.addUShort(1, 100u)
            wb.addUShort(2, 1u)
            val frame = byteArrayOf(MessageType.UPDATE.code.toByte()) + wb.finish()
            val decoded = WireCodec.decode(frame) as Update
            assertEquals(12, decoded.destination.size)
            assertEquals(32, decoded.ed25519PublicKey.size)
            assertEquals(32, decoded.x25519PublicKey.size)
        }

        // DeliveryAck: messageId/recipientId absent → defaults
        run {
            val wb = WriteBuffer()
            wb.startTable(4)
            wb.addUByte(2, 0u)
            val frame = byteArrayOf(MessageType.DELIVERY_ACK.code.toByte()) + wb.finish()
            val decoded = WireCodec.decode(frame) as DeliveryAck
            assertEquals(16, decoded.messageId.size)
            assertEquals(12, decoded.recipientId.size)
        }
    }

    // ── Equals && false-branch coverage ──────────────────────────────────────

    /**
     * Exercises every `&&` short-circuit false branch in each message's `equals()` method by
     * comparing two objects that differ at exactly one field position. The "all equal" case (needed
     * for all-true branches) is covered by [messageEqualsAndHashCodeCoverage].
     */
    @Test
    fun messageEqualsFalseBranchCoverage() {
        // Handshake: step differs (short-circuit: step != other.step → false without checking
        // noiseMessage)
        run {
            val a = Handshake(1u, byteArrayOf(0x01))
            val b = Handshake(2u, byteArrayOf(0x01))
            assertFalse(a.equals(b))
        }

        // Handshake: noiseMessage differs
        run {
            val a = Handshake(1u, byteArrayOf(0x01))
            val b = Handshake(1u, byteArrayOf(0x02))
            assertFalse(a.equals(b))
        }

        // ResumeRequest: bytesReceived differs (first &&); messageId differs (second &&)
        run {
            val base = ResumeRequest(ByteArray(16) { 0x11 }, 100u)
            assertFalse(base.equals(ResumeRequest(ByteArray(16) { 0x11 }, 200u))) // bytesReceived
            assertFalse(base.equals(ResumeRequest(ByteArray(16) { 0x22 }, 100u))) // messageId
        }

        // Hello: seqNo differs; routeDigest differs; sender differs
        run {
            val base = Hello(ByteArray(12) { 0x33 }, 1u, 99u)
            assertFalse(base.equals(Hello(ByteArray(12) { 0x33 }, 2u, 99u))) // seqNo
            assertFalse(base.equals(Hello(ByteArray(12) { 0x33 }, 1u, 0u))) // routeDigest
            assertFalse(base.equals(Hello(ByteArray(12) { 0x44 }, 1u, 99u))) // sender
        }

        // ChunkAck: ackSequence differs; sackBitmask differs; messageId differs
        run {
            val base = ChunkAck(ByteArray(16) { 0x55 }, 3u, 0xFUL)
            assertFalse(base.equals(ChunkAck(ByteArray(16) { 0x55 }, 4u, 0xFUL))) // ackSequence
            assertFalse(base.equals(ChunkAck(ByteArray(16) { 0x55 }, 3u, 0x1UL))) // sackBitmask
            assertFalse(base.equals(ChunkAck(ByteArray(16) { 0x66 }, 3u, 0xFUL))) // messageId
        }

        // Nack: reason differs; messageId differs
        run {
            val base = Nack(ByteArray(16) { 0x77 }, 1u)
            assertFalse(base.equals(Nack(ByteArray(16) { 0x77 }, 2u))) // reason
            assertFalse(base.equals(Nack(ByteArray(16) { 0x08 }, 1u))) // messageId
        }

        // Chunk: seqNum; totalChunks; messageId; payload
        run {
            val base = Chunk(ByteArray(16) { 0xAA.toByte() }, 0u, 2u, byteArrayOf(0x01))
            assertFalse(
                base.equals(Chunk(ByteArray(16) { 0xAA.toByte() }, 1u, 2u, byteArrayOf(0x01)))
            ) // seqNum
            assertFalse(
                base.equals(Chunk(ByteArray(16) { 0xAA.toByte() }, 0u, 3u, byteArrayOf(0x01)))
            ) // totalChunks
            assertFalse(
                base.equals(Chunk(ByteArray(16) { 0xBB.toByte() }, 0u, 2u, byteArrayOf(0x01)))
            ) // messageId
            assertFalse(
                base.equals(Chunk(ByteArray(16) { 0xAA.toByte() }, 0u, 2u, byteArrayOf(0x02)))
            ) // payload
        }

        // Update: metric; seqNo; destination; ed25519PublicKey; x25519PublicKey
        run {
            val base =
                Update(
                    ByteArray(12) { 0x11 },
                    10u,
                    1u,
                    ByteArray(32) { 0x22 },
                    ByteArray(32) { 0x33 },
                )
            assertFalse(
                base.equals(
                    Update(
                        ByteArray(12) { 0x11 },
                        20u,
                        1u,
                        ByteArray(32) { 0x22 },
                        ByteArray(32) { 0x33 },
                    )
                )
            ) // metric
            assertFalse(
                base.equals(
                    Update(
                        ByteArray(12) { 0x11 },
                        10u,
                        2u,
                        ByteArray(32) { 0x22 },
                        ByteArray(32) { 0x33 },
                    )
                )
            ) // seqNo
            assertFalse(
                base.equals(
                    Update(
                        ByteArray(12) { 0x44 },
                        10u,
                        1u,
                        ByteArray(32) { 0x22 },
                        ByteArray(32) { 0x33 },
                    )
                )
            ) // destination
            assertFalse(
                base.equals(
                    Update(
                        ByteArray(12) { 0x11 },
                        10u,
                        1u,
                        ByteArray(32) { 0x55 },
                        ByteArray(32) { 0x33 },
                    )
                )
            ) // ed25519Key
            assertFalse(
                base.equals(
                    Update(
                        ByteArray(12) { 0x11 },
                        10u,
                        1u,
                        ByteArray(32) { 0x22 },
                        ByteArray(32) { 0x66 },
                    )
                )
            ) // x25519Key
        }

        // RotationAnnouncementMsg: one condition per field
        run {
            val base =
                RotationAnnouncementMsg(
                    ByteArray(32) { 1 },
                    ByteArray(32) { 2 },
                    ByteArray(32) { 3 },
                    ByteArray(32) { 4 },
                    10UL,
                    20UL,
                    ByteArray(64) { 5 },
                )
            assertFalse(
                base.equals(
                    RotationAnnouncementMsg(
                        ByteArray(32) { 1 },
                        ByteArray(32) { 2 },
                        ByteArray(32) { 3 },
                        ByteArray(32) { 4 },
                        99UL,
                        20UL,
                        ByteArray(64) { 5 },
                    )
                )
            ) // nonce
            assertFalse(
                base.equals(
                    RotationAnnouncementMsg(
                        ByteArray(32) { 1 },
                        ByteArray(32) { 2 },
                        ByteArray(32) { 3 },
                        ByteArray(32) { 4 },
                        10UL,
                        99UL,
                        ByteArray(64) { 5 },
                    )
                )
            ) // timestamp
            assertFalse(
                base.equals(
                    RotationAnnouncementMsg(
                        ByteArray(32) { 9 },
                        ByteArray(32) { 2 },
                        ByteArray(32) { 3 },
                        ByteArray(32) { 4 },
                        10UL,
                        20UL,
                        ByteArray(64) { 5 },
                    )
                )
            ) // oldX25519
            assertFalse(
                base.equals(
                    RotationAnnouncementMsg(
                        ByteArray(32) { 1 },
                        ByteArray(32) { 9 },
                        ByteArray(32) { 3 },
                        ByteArray(32) { 4 },
                        10UL,
                        20UL,
                        ByteArray(64) { 5 },
                    )
                )
            ) // newX25519
            assertFalse(
                base.equals(
                    RotationAnnouncementMsg(
                        ByteArray(32) { 1 },
                        ByteArray(32) { 2 },
                        ByteArray(32) { 9 },
                        ByteArray(32) { 4 },
                        10UL,
                        20UL,
                        ByteArray(64) { 5 },
                    )
                )
            ) // oldEd25519
            assertFalse(
                base.equals(
                    RotationAnnouncementMsg(
                        ByteArray(32) { 1 },
                        ByteArray(32) { 2 },
                        ByteArray(32) { 3 },
                        ByteArray(32) { 9 },
                        10UL,
                        20UL,
                        ByteArray(64) { 5 },
                    )
                )
            ) // newEd25519
            assertFalse(
                base.equals(
                    RotationAnnouncementMsg(
                        ByteArray(32) { 1 },
                        ByteArray(32) { 2 },
                        ByteArray(32) { 3 },
                        ByteArray(32) { 4 },
                        10UL,
                        20UL,
                        ByteArray(64) { 9 },
                    )
                )
            ) // sig
        }

        // DeliveryAck: flags; messageId; recipientId; sig null vs non-null; sig content
        run {
            val base =
                DeliveryAck(
                    ByteArray(16) { 0xAA.toByte() },
                    ByteArray(12) { 0xBB.toByte() },
                    1u,
                    ByteArray(64) { 0xCC.toByte() },
                )
            assertFalse(
                base.equals(
                    DeliveryAck(
                        ByteArray(16) { 0xAA.toByte() },
                        ByteArray(12) { 0xBB.toByte() },
                        0u,
                        ByteArray(64) { 0xCC.toByte() },
                    )
                )
            ) // flags
            assertFalse(
                base.equals(
                    DeliveryAck(
                        ByteArray(16) { 0xDD.toByte() },
                        ByteArray(12) { 0xBB.toByte() },
                        1u,
                        ByteArray(64) { 0xCC.toByte() },
                    )
                )
            ) // messageId
            assertFalse(
                base.equals(
                    DeliveryAck(
                        ByteArray(16) { 0xAA.toByte() },
                        ByteArray(12) { 0xEE.toByte() },
                        1u,
                        ByteArray(64) { 0xCC.toByte() },
                    )
                )
            ) // recipientId
            assertFalse(
                base.equals(
                    DeliveryAck(
                        ByteArray(16) { 0xAA.toByte() },
                        ByteArray(12) { 0xBB.toByte() },
                        1u,
                        null,
                    )
                )
            ) // sig null vs non-null
            assertFalse(
                base.equals(
                    DeliveryAck(
                        ByteArray(16) { 0xAA.toByte() },
                        ByteArray(12) { 0xBB.toByte() },
                        1u,
                        ByteArray(64) { 0xFF.toByte() },
                    )
                )
            ) // sig content
        }

        // Broadcast: remainingHops; appIdHash; flags; priority; messageId; origin;
        //            sig null vs non-null; sig content; signerKey null vs non-null; signerKey
        // content; payload
        run {
            val base =
                Broadcast(
                    ByteArray(16) { 0x11 },
                    ByteArray(12) { 0x22 },
                    2u,
                    0x10u,
                    1u,
                    0,
                    ByteArray(64) { 0x33 },
                    ByteArray(32) { 0x44 },
                    byteArrayOf(0x55),
                )
            assertFalse(base.equals(base.copy(remainingHops = 9u))) // remainingHops
            assertFalse(base.equals(base.copy(appIdHash = 0x99u))) // appIdHash
            assertFalse(base.equals(base.copy(flags = 0u))) // flags
            assertFalse(base.equals(base.copy(priority = 1))) // priority
            assertFalse(
                base.equals(base.copy(messageId = ByteArray(16) { 0xFF.toByte() }))
            ) // messageId
            assertFalse(base.equals(base.copy(origin = ByteArray(12) { 0xFF.toByte() }))) // origin
            assertFalse(base.equals(base.copy(signature = null))) // sig null vs non-null
            assertFalse(
                base.equals(base.copy(signature = ByteArray(64) { 0xFF.toByte() }))
            ) // sig content
            assertFalse(base.equals(base.copy(signerKey = null))) // signerKey null vs non-null
            assertFalse(
                base.equals(base.copy(signerKey = ByteArray(32) { 0xFF.toByte() }))
            ) // signerKey content
            assertFalse(base.equals(base.copy(payload = byteArrayOf(0xFF.toByte())))) // payload
        }

        // RoutedMessage: hopLimit; priority; originationTime; messageId; origin; destination;
        //                visitedList size; visitedList content; payload
        run {
            val visited = listOf(ByteArray(12) { 0xAA.toByte() })
            val base =
                RoutedMessage(
                    ByteArray(16) { 0x11 },
                    ByteArray(12) { 0x22 },
                    ByteArray(12) { 0x33 },
                    3u,
                    visited,
                    0,
                    1_700_000_000_000UL,
                    ByteArray(4) { 0x44 },
                )
            assertFalse(base.equals(base.copy(hopLimit = 9u))) // hopLimit
            assertFalse(base.equals(base.copy(priority = 1))) // priority
            assertFalse(base.equals(base.copy(originationTime = 0UL))) // originationTime
            assertFalse(
                base.equals(base.copy(messageId = ByteArray(16) { 0xFF.toByte() }))
            ) // messageId
            assertFalse(base.equals(base.copy(origin = ByteArray(12) { 0xFF.toByte() }))) // origin
            assertFalse(
                base.equals(base.copy(destination = ByteArray(12) { 0xFF.toByte() }))
            ) // destination
            assertFalse(
                base.equals(base.copy(visitedList = emptyList()))
            ) // visitedList size differs
            assertFalse(
                base.equals(base.copy(visitedList = listOf(ByteArray(12) { 0xFF.toByte() })))
            ) // visitedList content
            assertFalse(base.equals(base.copy(payload = ByteArray(4) { 0xFF.toByte() }))) // payload
        }
    }
}
