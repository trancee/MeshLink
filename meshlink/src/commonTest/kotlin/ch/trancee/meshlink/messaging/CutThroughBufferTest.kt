package ch.trancee.meshlink.messaging

import ch.trancee.meshlink.api.NoOpDiagnosticSink
import ch.trancee.meshlink.wire.Chunk
import ch.trancee.meshlink.wire.RoutedMessage
import ch.trancee.meshlink.wire.WireCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CutThroughBufferTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private val localKeyHash = ByteArray(12) { 0xAA.toByte() }
    private val nextHop = ByteArray(12) { 0xBB.toByte() }
    private val relayMessageId = ByteArray(16) { 0xCC.toByte() }
    private val destination = ByteArray(12) { 0xDD.toByte() }
    private val origin = ByteArray(12) { 0xEE.toByte() }
    private val messageId = ByteArray(16) { 0x11.toByte() }

    /** Record of a single forwarded chunk: peerId + wireBytes. */
    data class ForwardedChunk(val peerId: ByteArray, val wireBytes: ByteArray)

    /** Creates a sendFn that records forwarded chunks. */
    private fun recordingSendFn():
        Pair<MutableList<ForwardedChunk>, suspend (ByteArray, ByteArray) -> Boolean> {
        val log = mutableListOf<ForwardedChunk>()
        val fn: suspend (ByteArray, ByteArray) -> Boolean = { peerId, data ->
            log.add(ForwardedChunk(peerId, data))
            true
        }
        return log to fn
    }

    /** Creates a sendFn that always fails. */
    private fun failingSendFn(): suspend (ByteArray, ByteArray) -> Boolean = { _, _ -> false }

    /**
     * Encodes a RoutedMessage and chunks it into [chunkSize]-byte pieces (same as TransferSession).
     * Returns the list of chunk payloads (raw bytes, NOT wire-encoded).
     */
    private fun encodeAndChunk(msg: RoutedMessage, chunkSize: Int): List<ByteArray> {
        val wireBytes = WireCodec.encode(msg)
        val count = (wireBytes.size + chunkSize - 1) / chunkSize
        return List(count) { i ->
            wireBytes.copyOfRange(i * chunkSize, minOf((i + 1) * chunkSize, wireBytes.size))
        }
    }

    /** Creates a default multi-chunk RoutedMessage with a large payload. */
    private fun multiChunkMessage(
        hopLimit: UByte = 5u,
        visited: List<ByteArray> = emptyList(),
        payloadSize: Int = 500,
    ): RoutedMessage =
        RoutedMessage(
            messageId = messageId,
            origin = origin,
            destination = destination,
            hopLimit = hopLimit,
            visitedList = visited,
            priority = 0,
            originationTime = 12345UL,
            payload = ByteArray(payloadSize) { (it % 256).toByte() },
        )

    /** Creates a single-chunk RoutedMessage with a small payload. */
    private fun singleChunkMessage(
        hopLimit: UByte = 3u,
        visited: List<ByteArray> = emptyList(),
    ): RoutedMessage =
        RoutedMessage(
            messageId = messageId,
            origin = origin,
            destination = destination,
            hopLimit = hopLimit,
            visitedList = visited,
            priority = 1,
            originationTime = 99UL,
            payload = ByteArray(20) { it.toByte() },
        )

    private fun createBuffer(
        sendFn: suspend (ByteArray, ByteArray) -> Boolean = failingSendFn(),
        chunkSize: Int = 244,
    ) =
        CutThroughBuffer(
            relayMessageId = relayMessageId,
            nextHop = nextHop,
            localKeyHash = localKeyHash,
            chunkSize = chunkSize,
            sendFn = sendFn,
            diagnosticSink = NoOpDiagnosticSink,
        )

    // ── RoutingHeaderView tests ──────────────────────────────────────────────

    @Test
    fun `RoutingHeaderView parses all routing fields from valid chunk 0`() {
        val msg = multiChunkMessage(hopLimit = 7u, visited = listOf(ByteArray(12) { 0x01 }))
        val chunks = encodeAndChunk(msg, 244)
        val header = CutThroughBuffer.RoutingHeaderView(chunks[0])

        assertEquals(7.toUByte(), header.hopLimit)
        assertContentEquals(destination, header.destination)
        assertContentEquals(origin, header.origin)
        assertContentEquals(messageId, header.messageId)
        assertEquals(0.toByte(), header.priority)
        assertEquals(12345UL, header.originationTime)
        assertEquals(1, header.visitedListEntries.size)
        assertContentEquals(ByteArray(12) { 0x01 }, header.visitedListEntries[0])
    }

    @Test
    fun `RoutingHeaderView parses empty visitedList`() {
        val msg = multiChunkMessage(visited = emptyList())
        val chunks = encodeAndChunk(msg, 244)
        val header = CutThroughBuffer.RoutingHeaderView(chunks[0])

        assertEquals(0, header.visitedListEntries.size)
    }

    @Test
    fun `RoutingHeaderView parses multiple visitedList entries`() {
        val v1 = ByteArray(12) { 0x01 }
        val v2 = ByteArray(12) { 0x02 }
        val msg = multiChunkMessage(visited = listOf(v1, v2))
        val chunks = encodeAndChunk(msg, 244)
        val header = CutThroughBuffer.RoutingHeaderView(chunks[0])

        assertEquals(2, header.visitedListEntries.size)
        assertContentEquals(v1, header.visitedListEntries[0])
        assertContentEquals(v2, header.visitedListEntries[1])
    }

    @Test
    fun `RoutingHeaderView throws on truncated chunk`() {
        assertFailsWith<IllegalArgumentException> {
            CutThroughBuffer.RoutingHeaderView(byteArrayOf(0x0A, 0x01))
        }
    }

    @Test
    fun `RoutingHeaderView throws on empty payload`() {
        assertFailsWith<IllegalArgumentException> {
            CutThroughBuffer.RoutingHeaderView(byteArrayOf())
        }
    }

    @Test
    fun `RoutingHeaderView throws on wrong type discriminator`() {
        // Type 0x05 = CHUNK, not ROUTED_MESSAGE.
        val msg = multiChunkMessage()
        val chunks = encodeAndChunk(msg, 244)
        val wrongType = chunks[0].copyOf()
        wrongType[0] = 0x05
        assertFailsWith<IllegalArgumentException> { CutThroughBuffer.RoutingHeaderView(wrongType) }
    }

    @Test
    fun `RoutingHeaderView throws on zero-length FlatBuffer`() {
        // Type byte only, no FlatBuffer data.
        assertFailsWith<IllegalArgumentException> {
            CutThroughBuffer.RoutingHeaderView(byteArrayOf(0x0A))
        }
    }

    // ── modifyChunk0 tests ──────────────────────────────────────────────────

    @Test
    fun `modifyChunk0 decrements hopLimit`() {
        val msg = multiChunkMessage(hopLimit = 5u)
        val chunks = encodeAndChunk(msg, 244)
        val header = CutThroughBuffer.RoutingHeaderView(chunks[0])

        val modified = CutThroughBuffer.modifyChunk0(chunks[0], 4u, localKeyHash, header)
        val modifiedHeader = CutThroughBuffer.RoutingHeaderView(modified)

        assertEquals(4.toUByte(), modifiedHeader.hopLimit)
    }

    @Test
    fun `modifyChunk0 extends visitedList by 12 bytes`() {
        val existing = ByteArray(12) { 0x01 }
        val msg = multiChunkMessage(visited = listOf(existing))
        val chunks = encodeAndChunk(msg, 244)
        val header = CutThroughBuffer.RoutingHeaderView(chunks[0])

        val modified = CutThroughBuffer.modifyChunk0(chunks[0], 4u, localKeyHash, header)

        assertEquals(chunks[0].size + 12, modified.size)

        val modifiedHeader = CutThroughBuffer.RoutingHeaderView(modified)
        assertEquals(2, modifiedHeader.visitedListEntries.size)
        assertContentEquals(existing, modifiedHeader.visitedListEntries[0])
        assertContentEquals(localKeyHash, modifiedHeader.visitedListEntries[1])
    }

    @Test
    fun `modifyChunk0 preserves other routing fields`() {
        val msg = multiChunkMessage(hopLimit = 5u, visited = listOf(ByteArray(12) { 0x01 }))
        val chunks = encodeAndChunk(msg, 244)
        val header = CutThroughBuffer.RoutingHeaderView(chunks[0])

        val modified = CutThroughBuffer.modifyChunk0(chunks[0], 4u, localKeyHash, header)
        val modifiedHeader = CutThroughBuffer.RoutingHeaderView(modified)

        assertContentEquals(destination, modifiedHeader.destination)
        assertContentEquals(origin, modifiedHeader.origin)
        assertContentEquals(messageId, modifiedHeader.messageId)
        assertEquals(0.toByte(), modifiedHeader.priority)
        assertEquals(12345UL, modifiedHeader.originationTime)
    }

    @Test
    fun `modifyChunk0 round-trip for single-chunk message`() {
        // A single-chunk message where the entire RoutedMessage fits in one chunk.
        val msg = singleChunkMessage(hopLimit = 3u)
        val wireBytes = WireCodec.encode(msg)
        // Use a chunkSize large enough that the whole message fits.
        val chunkSize = wireBytes.size + 20 // plenty of room even after +12.
        val chunks = listOf(wireBytes.copyOf()) // single chunk = entire wire message.
        val header = CutThroughBuffer.RoutingHeaderView(chunks[0])

        val modified = CutThroughBuffer.modifyChunk0(chunks[0], 2u, localKeyHash, header)

        // The modified bytes should be decodable as a RoutedMessage.
        val decoded = WireCodec.decode(modified) as RoutedMessage
        assertEquals(2.toUByte(), decoded.hopLimit)
        assertEquals(1, decoded.visitedList.size)
        assertContentEquals(localKeyHash, decoded.visitedList[0])
        assertContentEquals(destination, decoded.destination)
        assertContentEquals(origin, decoded.origin)
        assertContentEquals(msg.payload, decoded.payload)
    }

    @Test
    fun `modifyChunk0 with empty visitedList adds first entry`() {
        val msg = multiChunkMessage(visited = emptyList())
        val chunks = encodeAndChunk(msg, 244)
        val header = CutThroughBuffer.RoutingHeaderView(chunks[0])

        val modified = CutThroughBuffer.modifyChunk0(chunks[0], 4u, localKeyHash, header)
        val modifiedHeader = CutThroughBuffer.RoutingHeaderView(modified)

        assertEquals(1, modifiedHeader.visitedListEntries.size)
        assertContentEquals(localKeyHash, modifiedHeader.visitedListEntries[0])
    }

    @Test
    fun `modifyChunk0 payload uoffset updated correctly for multi-chunk reassembly`() {
        // Create a multi-chunk message, modify chunk 0, reassemble, and decode.
        val msg = multiChunkMessage(hopLimit = 5u, payloadSize = 500)
        val chunkSize = 244
        val wireBytes = WireCodec.encode(msg)
        val chunks = encodeAndChunk(msg, chunkSize)
        assertTrue(chunks.size > 1, "Must be multi-chunk")

        val header = CutThroughBuffer.RoutingHeaderView(chunks[0])
        val modified = CutThroughBuffer.modifyChunk0(chunks[0], 4u, localKeyHash, header)

        // Reassemble: modified chunk 0 + original chunks 1+.
        val reassembled = ByteArray(modified.size + chunks.drop(1).sumOf { it.size })
        modified.copyInto(reassembled, 0)
        var offset = modified.size
        for (i in 1 until chunks.size) {
            chunks[i].copyInto(reassembled, offset)
            offset += chunks[i].size
        }

        // Decode the reassembled bytes.
        val decoded = WireCodec.decode(reassembled) as RoutedMessage
        assertEquals(4.toUByte(), decoded.hopLimit)
        assertEquals(1, decoded.visitedList.size)
        assertContentEquals(localKeyHash, decoded.visitedList[0])
        assertContentEquals(msg.payload, decoded.payload)
        assertContentEquals(destination, decoded.destination)
    }

    // ── State transition tests ──────────────────────────────────────────────

    @Test
    fun `initial state is Pending`() {
        val buf = createBuffer()
        assertEquals(CutThroughBuffer.State.Pending, buf.state)
    }

    @Test
    fun `onChunk0 transitions to Active for multi-chunk message`() = runTest {
        val (log, sendFn) = recordingSendFn()
        val buf = createBuffer(sendFn = sendFn)

        val msg = multiChunkMessage()
        val chunks = encodeAndChunk(msg, 244)
        val chunk0 = Chunk(messageId, 0u, chunks.size.toUShort(), chunks[0])

        val activated = buf.onChunk0(chunk0)
        assertTrue(activated)
        assertEquals(CutThroughBuffer.State.Active, buf.state)
    }

    @Test
    fun `single-chunk message transitions directly to Complete`() = runTest {
        val (log, sendFn) = recordingSendFn()
        val msg = singleChunkMessage()
        val wireBytes = WireCodec.encode(msg)
        // Use chunkSize large enough for single chunk + 12 byte growth.
        val chunkSize = wireBytes.size + 20
        val buf =
            CutThroughBuffer(
                relayMessageId = relayMessageId,
                nextHop = nextHop,
                localKeyHash = localKeyHash,
                chunkSize = chunkSize,
                sendFn = sendFn,
                diagnosticSink = NoOpDiagnosticSink,
            )

        val chunk0 = Chunk(messageId, 0u, 1u, wireBytes)
        val activated = buf.onChunk0(chunk0)

        assertTrue(activated)
        assertEquals(CutThroughBuffer.State.Complete, buf.state)
        assertEquals(1, log.size)
    }

    @Test
    fun `multi-chunk message completes after all chunks forwarded`() = runTest {
        val (log, sendFn) = recordingSendFn()
        val buf = createBuffer(sendFn = sendFn, chunkSize = 244)

        val msg = multiChunkMessage(payloadSize = 500)
        val chunks = encodeAndChunk(msg, 244)
        assertTrue(chunks.size > 1)

        val chunk0 = Chunk(messageId, 0u, chunks.size.toUShort(), chunks[0])
        val activated = buf.onChunk0(chunk0)
        assertTrue(activated)
        assertEquals(CutThroughBuffer.State.Active, buf.state)

        // Forward remaining chunks.
        for (i in 1 until chunks.size) {
            val chunk = Chunk(messageId, i.toUShort(), chunks.size.toUShort(), chunks[i])
            buf.onSubsequentChunk(chunk)
        }

        assertEquals(CutThroughBuffer.State.Complete, buf.state)
    }

    // ── Overflow split tests ────────────────────────────────────────────────

    @Test
    fun `overflow split forwards seqNo-adjusted chunks`() = runTest {
        val (log, sendFn) = recordingSendFn()
        val buf = createBuffer(sendFn = sendFn, chunkSize = 244)

        val msg = multiChunkMessage(payloadSize = 500)
        val chunkSize = 244
        val chunks = encodeAndChunk(msg, chunkSize)
        assertTrue(chunks.size > 1)

        val chunk0 = Chunk(messageId, 0u, chunks.size.toUShort(), chunks[0])
        buf.onChunk0(chunk0)

        // After chunk 0, two chunks should have been forwarded (chunk 0 split into two).
        assertEquals(2, log.size)

        // Decode forwarded chunks and verify seqNo.
        val fwd0 = WireCodec.decode(log[0].wireBytes) as Chunk
        val fwd1 = WireCodec.decode(log[1].wireBytes) as Chunk

        assertEquals(0.toUShort(), fwd0.seqNo)
        assertEquals(1.toUShort(), fwd1.seqNo)

        // Total chunks should be original + 1.
        val expectedTotal = (chunks.size + 1).toUShort()
        assertEquals(expectedTotal, fwd0.totalChunks)
        assertEquals(expectedTotal, fwd1.totalChunks)
    }

    @Test
    fun `overflow split subsequent chunk seqNo offset by 1`() = runTest {
        val (log, sendFn) = recordingSendFn()
        val buf = createBuffer(sendFn = sendFn, chunkSize = 244)

        val msg = multiChunkMessage(payloadSize = 500)
        val chunkSize = 244
        val chunks = encodeAndChunk(msg, chunkSize)

        val chunk0 = Chunk(messageId, 0u, chunks.size.toUShort(), chunks[0])
        buf.onChunk0(chunk0)

        // Forward chunk 1 (original seqNo=1 → forwarded seqNo=2).
        val chunk1 = Chunk(messageId, 1u, chunks.size.toUShort(), chunks[1])
        buf.onSubsequentChunk(chunk1)

        // The third forwarded chunk should have seqNo=2.
        assertEquals(3, log.size)
        val fwd2 = WireCodec.decode(log[2].wireBytes) as Chunk
        assertEquals(2.toUShort(), fwd2.seqNo)
    }

    // ── Forwarded chunks use relay messageId ────────────────────────────────

    @Test
    fun `forwarded chunks use relay messageId not original`() = runTest {
        val (log, sendFn) = recordingSendFn()
        val buf = createBuffer(sendFn = sendFn, chunkSize = 244)

        val msg = multiChunkMessage()
        val chunks = encodeAndChunk(msg, 244)
        val chunk0 = Chunk(messageId, 0u, chunks.size.toUShort(), chunks[0])
        buf.onChunk0(chunk0)

        assertTrue(log.isNotEmpty())
        val fwd = WireCodec.decode(log[0].wireBytes) as Chunk
        assertContentEquals(relayMessageId, fwd.messageId)
        assertFalse(fwd.messageId.contentEquals(messageId))
    }

    @Test
    fun `forwarded chunks sent to nextHop`() = runTest {
        val (log, sendFn) = recordingSendFn()
        val buf = createBuffer(sendFn = sendFn, chunkSize = 244)

        val msg = multiChunkMessage()
        val chunks = encodeAndChunk(msg, 244)
        val chunk0 = Chunk(messageId, 0u, chunks.size.toUShort(), chunks[0])
        buf.onChunk0(chunk0)

        assertTrue(log.isNotEmpty())
        assertContentEquals(nextHop, log[0].peerId)
    }

    // ── Fallback / negative tests ───────────────────────────────────────────

    @Test
    fun `onChunk0 returns false and falls back on hopLimit 0`() = runTest {
        val buf = createBuffer()
        val msg = multiChunkMessage(hopLimit = 0u)
        val chunks = encodeAndChunk(msg, 244)
        val chunk0 = Chunk(messageId, 0u, chunks.size.toUShort(), chunks[0])

        val activated = buf.onChunk0(chunk0)
        assertFalse(activated)
        assertEquals(CutThroughBuffer.State.Fallback, buf.state)
    }

    @Test
    fun `onChunk0 returns false on loop detection`() = runTest {
        val buf = createBuffer()
        val msg = multiChunkMessage(visited = listOf(localKeyHash))
        val chunks = encodeAndChunk(msg, 244)
        val chunk0 = Chunk(messageId, 0u, chunks.size.toUShort(), chunks[0])

        val activated = buf.onChunk0(chunk0)
        assertFalse(activated)
        assertEquals(CutThroughBuffer.State.Fallback, buf.state)
    }

    @Test
    fun `onChunk0 returns false on malformed chunk 0`() = runTest {
        val buf = createBuffer()
        val truncatedPayload = byteArrayOf(0x0A, 0x01, 0x02) // Too short for FlatBuffer.
        val chunk0 = Chunk(messageId, 0u, 1u, truncatedPayload)

        val activated = buf.onChunk0(chunk0)
        assertFalse(activated)
        assertEquals(CutThroughBuffer.State.Fallback, buf.state)
    }

    @Test
    fun `onChunk0 returns false on wrong type discriminator`() = runTest {
        val buf = createBuffer()
        val msg = multiChunkMessage()
        val chunks = encodeAndChunk(msg, 244)
        val wrongType = chunks[0].copyOf()
        wrongType[0] = 0x05 // CHUNK type, not ROUTED_MESSAGE.
        val chunk0 = Chunk(messageId, 0u, chunks.size.toUShort(), wrongType)

        val activated = buf.onChunk0(chunk0)
        assertFalse(activated)
        assertEquals(CutThroughBuffer.State.Fallback, buf.state)
    }

    @Test
    fun `onChunk0 returns false when already activated`() = runTest {
        val (_, sendFn) = recordingSendFn()
        val buf = createBuffer(sendFn = sendFn)
        val msg = multiChunkMessage()
        val chunks = encodeAndChunk(msg, 244)
        val chunk0 = Chunk(messageId, 0u, chunks.size.toUShort(), chunks[0])

        assertTrue(buf.onChunk0(chunk0))
        // Second call should return false (state is Active, not Pending).
        assertFalse(buf.onChunk0(chunk0))
    }

    // ── Timeout / eviction ──────────────────────────────────────────────────

    @Test
    fun `markTimedOut transitions from Active to TimedOut`() = runTest {
        val (_, sendFn) = recordingSendFn()
        val buf = createBuffer(sendFn = sendFn)
        val msg = multiChunkMessage()
        val chunks = encodeAndChunk(msg, 244)
        val chunk0 = Chunk(messageId, 0u, chunks.size.toUShort(), chunks[0])
        buf.onChunk0(chunk0)

        assertEquals(CutThroughBuffer.State.Active, buf.state)
        buf.markTimedOut()
        assertEquals(CutThroughBuffer.State.TimedOut, buf.state)
    }

    @Test
    fun `markTimedOut transitions from Pending to TimedOut`() {
        val buf = createBuffer()
        assertEquals(CutThroughBuffer.State.Pending, buf.state)
        buf.markTimedOut()
        assertEquals(CutThroughBuffer.State.TimedOut, buf.state)
    }

    @Test
    fun `markTimedOut is no-op when already Complete`() = runTest {
        val (_, sendFn) = recordingSendFn()
        val msg = singleChunkMessage()
        val wireBytes = WireCodec.encode(msg)
        val chunkSize = wireBytes.size + 20
        val buf =
            CutThroughBuffer(
                relayMessageId = relayMessageId,
                nextHop = nextHop,
                localKeyHash = localKeyHash,
                chunkSize = chunkSize,
                sendFn = sendFn,
                diagnosticSink = NoOpDiagnosticSink,
            )
        buf.onChunk0(Chunk(messageId, 0u, 1u, wireBytes))
        assertEquals(CutThroughBuffer.State.Complete, buf.state)

        buf.markTimedOut()
        assertEquals(CutThroughBuffer.State.Complete, buf.state) // unchanged.
    }

    // ── Send failure handling ───────────────────────────────────────────────

    @Test
    fun `send failure during chunk 0 still activates`() = runTest {
        val buf = createBuffer(sendFn = failingSendFn())
        val msg = multiChunkMessage()
        val chunks = encodeAndChunk(msg, 244)
        val chunk0 = Chunk(messageId, 0u, chunks.size.toUShort(), chunks[0])

        val activated = buf.onChunk0(chunk0)
        assertTrue(activated) // Activation succeeds even if send fails.
        assertEquals(CutThroughBuffer.State.Active, buf.state)
    }

    @Test
    fun `onSubsequentChunk is ignored when not Active`() = runTest {
        val buf = createBuffer()
        assertEquals(CutThroughBuffer.State.Pending, buf.state)

        val chunk = Chunk(messageId, 1u, 3u, ByteArray(100))
        buf.onSubsequentChunk(chunk) // should not throw.
        assertEquals(CutThroughBuffer.State.Pending, buf.state)
    }

    // ── Routing header exposed after activation ─────────────────────────────

    @Test
    fun `routingHeader is populated after successful onChunk0`() = runTest {
        val (_, sendFn) = recordingSendFn()
        val buf = createBuffer(sendFn = sendFn)
        val msg = multiChunkMessage(hopLimit = 5u)
        val chunks = encodeAndChunk(msg, 244)
        val chunk0 = Chunk(messageId, 0u, chunks.size.toUShort(), chunks[0])

        buf.onChunk0(chunk0)
        val header = buf.routingHeader
        assertNotNull(header)
        assertContentEquals(destination, header.destination)
        assertEquals(5.toUByte(), header.hopLimit)
    }

    // ── Boundary: single-chunk with overflow ────────────────────────────────

    @Test
    fun `single-chunk message that overflows after modification`() = runTest {
        val (log, sendFn) = recordingSendFn()
        // Create a message that fills exactly chunkSize as a single chunk, then +12 causes split.
        val msg = singleChunkMessage()
        val wireBytes = WireCodec.encode(msg)
        // Use chunkSize = wireBytes.size exactly so modification causes overflow.
        val chunkSize = wireBytes.size
        val buf =
            CutThroughBuffer(
                relayMessageId = relayMessageId,
                nextHop = nextHop,
                localKeyHash = localKeyHash,
                chunkSize = chunkSize,
                sendFn = sendFn,
                diagnosticSink = NoOpDiagnosticSink,
            )

        val chunk0 = Chunk(messageId, 0u, 1u, wireBytes)
        val activated = buf.onChunk0(chunk0)

        assertTrue(activated)
        // Original was 1 chunk, modified overflows → 2 forwarded chunks → Complete.
        assertEquals(CutThroughBuffer.State.Complete, buf.state)
        assertEquals(2, log.size)

        val fwd0 = WireCodec.decode(log[0].wireBytes) as Chunk
        val fwd1 = WireCodec.decode(log[1].wireBytes) as Chunk
        assertEquals(0.toUShort(), fwd0.seqNo)
        assertEquals(1.toUShort(), fwd1.seqNo)
        assertEquals(2.toUShort(), fwd0.totalChunks)
        assertEquals(2.toUShort(), fwd1.totalChunks)

        // Reassemble and decode the forwarded message.
        val reassembled = fwd0.payload + fwd1.payload
        val decoded = WireCodec.decode(reassembled) as RoutedMessage
        assertEquals((msg.hopLimit - 1u).toUByte(), decoded.hopLimit)
        assertEquals(1, decoded.visitedList.size)
        assertContentEquals(localKeyHash, decoded.visitedList[0])
        assertContentEquals(msg.payload, decoded.payload)
    }

    // ── Full end-to-end: multi-chunk reassembly correctness ─────────────────

    @Test
    fun `full multi-chunk relay produces correct reassembled RoutedMessage`() = runTest {
        val (log, sendFn) = recordingSendFn()
        val buf = createBuffer(sendFn = sendFn, chunkSize = 244)

        val existingVisited = ByteArray(12) { 0x77 }
        val msg =
            multiChunkMessage(hopLimit = 4u, visited = listOf(existingVisited), payloadSize = 500)
        val chunkSize = 244
        val chunks = encodeAndChunk(msg, chunkSize)
        assertTrue(chunks.size > 1, "Must produce multiple chunks")

        // Feed chunk 0.
        val chunk0 = Chunk(messageId, 0u, chunks.size.toUShort(), chunks[0])
        assertTrue(buf.onChunk0(chunk0))

        // Feed remaining chunks.
        for (i in 1 until chunks.size) {
            buf.onSubsequentChunk(Chunk(messageId, i.toUShort(), chunks.size.toUShort(), chunks[i]))
        }

        assertEquals(CutThroughBuffer.State.Complete, buf.state)

        // Reassemble all forwarded chunk payloads in seqNo order.
        val forwarded = log.map { WireCodec.decode(it.wireBytes) as Chunk }
        val sorted = forwarded.sortedBy { it.seqNo.toInt() }
        val reassembled = ByteArray(sorted.sumOf { it.payload.size })
        var off = 0
        for (c in sorted) {
            c.payload.copyInto(reassembled, off)
            off += c.payload.size
        }

        // Decode the reassembled wire bytes.
        val decoded = WireCodec.decode(reassembled) as RoutedMessage
        assertEquals(3.toUByte(), decoded.hopLimit) // 4 - 1.
        assertEquals(2, decoded.visitedList.size)
        assertContentEquals(existingVisited, decoded.visitedList[0])
        assertContentEquals(localKeyHash, decoded.visitedList[1])
        assertContentEquals(msg.payload, decoded.payload)
        assertContentEquals(destination, decoded.destination)
        assertContentEquals(origin, decoded.origin)
        assertEquals(msg.originationTime, decoded.originationTime)
    }
}
