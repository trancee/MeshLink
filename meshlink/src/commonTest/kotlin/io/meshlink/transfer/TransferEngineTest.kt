package io.meshlink.transfer

import io.meshlink.util.ByteArrayKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TransferEngineTest {

    private fun createEngine(): TransferEngine = TransferEngine()

    private fun key(s: String) = ByteArrayKey(s.encodeToByteArray())

    // --- Vertical slice 1: send + receive all ACKs → complete ---

    @Test
    fun sendAndReceiveAllAcksCompletes() {
        val engine = createEngine()
        val payload = "hello world".encodeToByteArray()
        val msgId = ByteArray(16) { it.toByte() }
        val msgHex = msgId.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

        val handle = engine.beginSend(key(msgHex), msgId, payload, chunkSize = 100)
        assertEquals(1, handle.totalChunks) // fits in one chunk
        assertEquals(1, handle.chunks.size)

        // Simulate receiver ACKing the single chunk
        val update = engine.onAck(key(msgHex), ackSeq = 0, sackBitmask = 0uL)
        assertIs<TransferUpdate.Complete>(update)
    }

    // --- Vertical slice 2: multi-chunk send + sequential ACKs ---

    @Test
    fun multiChunkSendWithSequentialAcks() {
        val engine = createEngine()
        val payload = ByteArray(30) { it.toByte() } // 30 bytes
        val msgId = ByteArray(16) { 1 }
        val msgHex = msgId.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

        val handle = engine.beginSend(key(msgHex), msgId, payload, chunkSize = 10)
        assertEquals(3, handle.totalChunks) // 30/10 = 3 chunks
        assertEquals(3, handle.chunks.size)

        // ACK chunk 0
        val u1 = engine.onAck(key(msgHex), ackSeq = 0, sackBitmask = 0uL)
        assertIs<TransferUpdate.Progress>(u1)
        assertEquals(1, u1.ackedCount)

        // ACK chunks 0-1
        val u2 = engine.onAck(key(msgHex), ackSeq = 1, sackBitmask = 0uL)
        assertIs<TransferUpdate.Progress>(u2)
        assertEquals(2, u2.ackedCount)

        // ACK chunks 0-2 (complete)
        val u3 = engine.onAck(key(msgHex), ackSeq = 2, sackBitmask = 0uL)
        assertIs<TransferUpdate.Complete>(u3)
    }

    // --- Vertical slice 3: inbound chunk reassembly ---

    @Test
    fun inboundChunksReassembleCorrectly() {
        val engine = createEngine()
        val msgHex = "abcdef"
        val chunk0 = "hello ".encodeToByteArray()
        val chunk1 = "world".encodeToByteArray()

        val r0 = engine.onChunkReceived(key(msgHex), seqNum = 0, totalChunks = 2, chunk0)
        assertIs<ChunkAcceptResult.Ack>(r0)
        assertEquals(0, r0.ackSeq)

        val r1 = engine.onChunkReceived(key(msgHex), seqNum = 1, totalChunks = 2, chunk1)
        assertIs<ChunkAcceptResult.MessageComplete>(r1)
        assertEquals("hello world", r1.reassembledPayload.decodeToString())
    }

    // --- Vertical slice 4: out-of-order chunks with SACK ---

    @Test
    fun outOfOrderChunksProduceSackBitmask() {
        val engine = createEngine()
        val msgHex = "sack01"

        // Receive chunk 2 first (out of order)
        val r2 = engine.onChunkReceived(key(msgHex), seqNum = 2, totalChunks = 4, "c".encodeToByteArray())
        assertIs<ChunkAcceptResult.Ack>(r2)
        // ackSeq is -1 or 0 (nothing contiguous from 0), bitmask has bit for chunk 2

        // Receive chunk 0
        val r0 = engine.onChunkReceived(key(msgHex), seqNum = 0, totalChunks = 4, "a".encodeToByteArray())
        assertIs<ChunkAcceptResult.Ack>(r0)
        assertEquals(0, r0.ackSeq) // chunk 0 is contiguous

        // Receive chunk 1 + 3 → complete
        engine.onChunkReceived(key(msgHex), seqNum = 1, totalChunks = 4, "b".encodeToByteArray())
        val r3 = engine.onChunkReceived(key(msgHex), seqNum = 3, totalChunks = 4, "d".encodeToByteArray())
        assertIs<ChunkAcceptResult.MessageComplete>(r3)
        assertEquals("abcd", r3.reassembledPayload.decodeToString())
    }

    // --- Vertical slice 5: empty payload ---

    @Test
    fun emptyPayloadProducesSingleChunk() {
        val engine = createEngine()
        val msgId = ByteArray(16)
        val msgHex = msgId.joinToString("") { "00" }

        val handle = engine.beginSend(key(msgHex), msgId, ByteArray(0), chunkSize = 100)
        assertEquals(1, handle.totalChunks)
        assertEquals(0, handle.chunks[0].payload.size)
    }

    // --- Vertical slice 6: unknown ACK returns Unknown ---

    @Test
    fun ackForUnknownTransferReturnsUnknown() {
        val engine = createEngine()
        val result = engine.onAck(key("nonexistent"), 0, 0uL)
        assertIs<TransferUpdate.Unknown>(result)
    }

    // --- Vertical slice 7: sweep stale outbound ---

    @Test
    fun sweepStaleRemovesOldTransfers() {
        var now = 1000L
        val engine = TransferEngine(clock = { now })

        val msgId = ByteArray(16)
        val msgHex = "stale01"
        engine.beginSend(key(msgHex), msgId, "data".encodeToByteArray(), chunkSize = 100)
        assertEquals(1, engine.outboundCount)

        now = 6000L // 5 seconds later
        val swept = engine.sweepStaleOutbound(maxAgeMillis = 3000L)
        assertEquals(1, swept.size)
        assertEquals(key(msgHex), swept[0])
        assertEquals(0, engine.outboundCount)
    }

    // --- Vertical slice 8: sweep stale inbound ---

    @Test
    fun sweepStaleRemovesOldReassemblies() {
        var now = 1000L
        val engine = TransferEngine(clock = { now })

        engine.onChunkReceived(key("reassembly01"), seqNum = 0, totalChunks = 3, "a".encodeToByteArray())
        assertEquals(1, engine.inboundCount)

        now = 6000L
        val swept = engine.sweepStaleInbound(maxAgeMillis = 3000L)
        assertEquals(1, swept.size)
        assertEquals(0, engine.inboundCount)
    }

    // --- Vertical slice 9: buffer bytes tracking ---

    @Test
    fun bufferBytesTracksUsage() {
        val engine = createEngine()
        val msgId = ByteArray(16)
        engine.beginSend(key("out01"), msgId, "hello".encodeToByteArray(), chunkSize = 100)
        assertTrue(engine.outboundBufferBytes() > 0)

        engine.onChunkReceived(key("in01"), seqNum = 0, totalChunks = 2, "world".encodeToByteArray())
        assertTrue(engine.inboundBufferBytes() > 0)
    }

    // --- Vertical slice 10: clearAll resets everything ---

    @Test
    fun clearAllResetsState() {
        val engine = createEngine()
        val msgId = ByteArray(16)
        engine.beginSend(key("out01"), msgId, "data".encodeToByteArray(), chunkSize = 100)
        engine.onChunkReceived(key("in01"), seqNum = 0, totalChunks = 2, "data".encodeToByteArray())

        engine.clearAll()

        assertEquals(0, engine.outboundCount)
        assertEquals(0, engine.inboundCount)
    }

    // --- TM-001: Reject chunks when inbound session limit reached ---

    @Test
    fun rejectChunksWhenInboundSessionLimitReached() {
        val engine = TransferEngine(maxConcurrentInboundSessions = 2)

        // Fill both session slots
        val r1 = engine.onChunkReceived(key("msg1"), 0, 3, "a".encodeToByteArray())
        assertIs<ChunkAcceptResult.Ack>(r1)

        val r2 = engine.onChunkReceived(key("msg2"), 0, 3, "b".encodeToByteArray())
        assertIs<ChunkAcceptResult.Ack>(r2)

        assertEquals(2, engine.inboundCount)

        // Third session rejected
        val r3 = engine.onChunkReceived(key("msg3"), 0, 3, "c".encodeToByteArray())
        assertIs<ChunkAcceptResult.Rejected>(r3)
        assertEquals(2, engine.inboundCount)

        // Existing sessions still accept chunks
        val r1b = engine.onChunkReceived(key("msg1"), 1, 3, "d".encodeToByteArray())
        assertIs<ChunkAcceptResult.Ack>(r1b)
    }
}
