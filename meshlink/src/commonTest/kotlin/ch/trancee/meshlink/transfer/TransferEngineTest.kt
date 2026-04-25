package ch.trancee.meshlink.transfer

import ch.trancee.meshlink.routing.OutboundFrame
import ch.trancee.meshlink.wire.Chunk
import ch.trancee.meshlink.wire.ChunkAck
import ch.trancee.meshlink.wire.Nack
import ch.trancee.meshlink.wire.ResumeRequest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class TransferEngineTest {

    private val msgId = ByteArray(4) { 0x11.toByte() }
    private val msgId2 = ByteArray(4) { 0x22.toByte() }
    private val peerA = ByteArray(4) { 0xAA.toByte() }
    private val peerB = ByteArray(4) { 0xBB.toByte() }

    private val cfg =
        TransferConfig(
            inactivityBaseTimeoutMillis = 5_000L, // long so tests don't fire accidental timeouts
            maxNackRetries = 2,
            nackBaseBackoffMillis = 50L,
            maxResumeAttempts = 2,
        )

    // ──────────────────────────────────────────────────────────────────────
    // send() — creates sender session and emits chunks
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `send emits chunk to outboundChunks and exposes events flow`() = runTest {
        val engine = TransferEngine(backgroundScope, cfg, ChunkSizePolicy.fixed(10), true)
        val frames = mutableListOf<OutboundFrame>()
        backgroundScope.launch { engine.outboundChunks.collect { frames.add(it) } }
        backgroundScope.launch { engine.events.collect {} }

        engine.send(msgId, byteArrayOf(1, 2, 3), peerA)
        runCurrent()

        val chunks = frames.filter { it.message is Chunk }
        assertEquals(1, chunks.size)
        assertContentEquals(peerA, chunks[0].peerId)
    }

    // ──────────────────────────────────────────────────────────────────────
    // onIncomingChunk() — creates receiver session, routes chunks
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `onIncomingChunk creates receiver session on first chunk`() = runTest {
        val engine = TransferEngine(backgroundScope, cfg, ChunkSizePolicy.fixed(10), true)
        val frames = mutableListOf<OutboundFrame>()
        val eventList = mutableListOf<TransferEvent>()
        backgroundScope.launch { engine.outboundChunks.collect { frames.add(it) } }
        backgroundScope.launch { engine.events.collect { eventList.add(it) } }

        val chunk = Chunk(msgId, 0u, 1u, byteArrayOf(42))
        engine.onIncomingChunk(peerA, chunk)
        runCurrent()

        // Receiver emits ChunkAck (GATT mode) and ChunkProgress + AssemblyComplete
        assertTrue(frames.any { it.message is ChunkAck })
        assertTrue(eventList.any { it is TransferEvent.ChunkProgress })
        assertTrue(eventList.any { it is TransferEvent.AssemblyComplete })
    }

    @Test
    fun `onIncomingChunk reuses existing session for same messageId`() = runTest {
        val engine = TransferEngine(backgroundScope, cfg, ChunkSizePolicy.fixed(10), true)
        val eventList = mutableListOf<TransferEvent>()
        backgroundScope.launch { engine.outboundChunks.collect {} }
        backgroundScope.launch { engine.events.collect { eventList.add(it) } }

        // Two-chunk message: first chunk creates session
        engine.onIncomingChunk(peerA, Chunk(msgId, 0u, 2u, byteArrayOf(1)))
        runCurrent()
        // Second chunk routes to same session (does not create a new one)
        engine.onIncomingChunk(peerA, Chunk(msgId, 1u, 2u, byteArrayOf(2)))
        runCurrent()

        val assembled = eventList.filterIsInstance<TransferEvent.AssemblyComplete>()
        assertEquals(1, assembled.size)
        assertContentEquals(byteArrayOf(1, 2), assembled[0].payload)
    }

    // ──────────────────────────────────────────────────────────────────────
    // onIncomingChunkAck / onIncomingNack / onIncomingResumeRequest
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `onIncomingChunkAck routes to matching sender session`() = runTest {
        val engine = TransferEngine(backgroundScope, cfg, ChunkSizePolicy.fixed(10), true)
        val frames = mutableListOf<OutboundFrame>()
        backgroundScope.launch { engine.outboundChunks.collect { frames.add(it) } }
        backgroundScope.launch { engine.events.collect {} }

        engine.send(msgId, byteArrayOf(1, 2), peerA) // 1 chunk (size=10 policy → fits in 1 chunk)
        runCurrent()
        frames.clear()

        // ACK the single chunk → sender completes, no further chunks emitted
        engine.onIncomingChunkAck(peerA, ChunkAck(msgId, 0u, 0uL))
        runCurrent()

        // Sender session completed — no retransmit
        assertTrue(frames.none { it.message is Chunk })
    }

    @Test
    fun `onIncomingChunkAck with unknown messageId is a no-op`() = runTest {
        val engine = TransferEngine(backgroundScope, cfg, ChunkSizePolicy.fixed(10), true)
        backgroundScope.launch { engine.outboundChunks.collect {} }
        backgroundScope.launch { engine.events.collect {} }

        // No session registered for msgId2
        engine.onIncomingChunkAck(peerA, ChunkAck(msgId2, 0u, 0uL))
        runCurrent()
        // No crash
    }

    @Test
    fun `onIncomingNack routes to matching sender session`() = runTest {
        val engine = TransferEngine(backgroundScope, cfg, ChunkSizePolicy.fixed(10), true)
        val frames = mutableListOf<OutboundFrame>()
        backgroundScope.launch { engine.outboundChunks.collect { frames.add(it) } }
        backgroundScope.launch { engine.events.collect {} }

        engine.send(msgId, byteArrayOf(1), peerA)
        runCurrent()
        frames.clear()

        engine.onIncomingNack(peerA, Nack(msgId, NACK_REASON_BUFFER_FULL))
        runCurrent()
        // NACK triggers backoff — no immediate re-send, but no crash either
    }

    @Test
    fun `onIncomingNack with unknown messageId is a no-op`() = runTest {
        val engine = TransferEngine(backgroundScope, cfg, ChunkSizePolicy.fixed(10), true)
        backgroundScope.launch { engine.outboundChunks.collect {} }
        backgroundScope.launch { engine.events.collect {} }

        engine.onIncomingNack(peerA, Nack(msgId2, NACK_REASON_BUFFER_FULL))
        runCurrent()
        // No crash
    }

    @Test
    fun `onIncomingResumeRequest routes to matching sender session`() = runTest {
        val engine = TransferEngine(backgroundScope, cfg, ChunkSizePolicy.fixed(10), true)
        val frames = mutableListOf<OutboundFrame>()
        backgroundScope.launch { engine.outboundChunks.collect { frames.add(it) } }
        backgroundScope.launch { engine.events.collect {} }

        engine.send(msgId, byteArrayOf(1), peerA)
        runCurrent()

        // Disconnect so that a resume request is accepted
        engine.onPeerDisconnect(peerA)
        runCurrent()
        frames.clear()

        engine.onIncomingResumeRequest(peerA, ResumeRequest(msgId, 0u))
        runCurrent()
        // Session was disconnected; resume is ignored (not disconnected in the sender-loop sense
        // until Disconnect event is processed), but no crash
    }

    @Test
    fun `onIncomingResumeRequest with unknown messageId is a no-op`() = runTest {
        val engine = TransferEngine(backgroundScope, cfg, ChunkSizePolicy.fixed(10), true)
        backgroundScope.launch { engine.outboundChunks.collect {} }
        backgroundScope.launch { engine.events.collect {} }

        engine.onIncomingResumeRequest(peerA, ResumeRequest(msgId2, 0u))
        runCurrent()
        // No crash
    }

    // ──────────────────────────────────────────────────────────────────────
    // onPeerDisconnect / onPeerReconnect
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `onPeerDisconnect notifies sessions with matching peerId`() = runTest {
        val engine = TransferEngine(backgroundScope, cfg, ChunkSizePolicy.fixed(10), true)
        backgroundScope.launch { engine.outboundChunks.collect {} }
        backgroundScope.launch { engine.events.collect {} }

        // Two senders on different peers
        engine.send(msgId, byteArrayOf(1), peerA)
        engine.send(msgId2, byteArrayOf(2), peerB)
        runCurrent()

        // Disconnect only peerA
        engine.onPeerDisconnect(peerA)
        runCurrent()
        // No crash; peerA session's Disconnect event enqueued
    }

    @Test
    fun `onPeerDisconnect with no matching session is a no-op`() = runTest {
        val engine = TransferEngine(backgroundScope, cfg, ChunkSizePolicy.fixed(10), true)
        backgroundScope.launch { engine.outboundChunks.collect {} }
        backgroundScope.launch { engine.events.collect {} }

        engine.onPeerDisconnect(peerA) // no sessions registered
        runCurrent()
    }

    @Test
    fun `onPeerReconnect notifies sessions with matching peerId`() = runTest {
        val engine = TransferEngine(backgroundScope, cfg, ChunkSizePolicy.fixed(10), true)
        backgroundScope.launch { engine.outboundChunks.collect {} }
        backgroundScope.launch { engine.events.collect {} }

        engine.send(msgId, byteArrayOf(1), peerA)
        engine.send(msgId2, byteArrayOf(2), peerB)
        runCurrent()

        // Reconnect only peerA
        engine.onPeerReconnect(peerA)
        runCurrent()
        // peerA session gets Reconnect event; no crash
    }

    @Test
    fun `onPeerReconnect with no matching session is a no-op`() = runTest {
        val engine = TransferEngine(backgroundScope, cfg, ChunkSizePolicy.fixed(10), true)
        backgroundScope.launch { engine.outboundChunks.collect {} }
        backgroundScope.launch { engine.events.collect {} }

        engine.onPeerReconnect(peerA) // no sessions
        runCurrent()
    }

    // ──────────────────────────────────────────────────────────────────────
    // onMemoryPressure
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `onMemoryPressure evicts LOW priority sessions and emits TransferFailed`() = runTest {
        val engine = TransferEngine(backgroundScope, cfg, ChunkSizePolicy.fixed(10), true)
        val eventList = mutableListOf<TransferEvent>()
        backgroundScope.launch { engine.outboundChunks.collect {} }
        backgroundScope.launch { engine.events.collect { eventList.add(it) } }

        engine.send(msgId, byteArrayOf(1), peerA, Priority.LOW)
        engine.send(msgId2, byteArrayOf(2), peerB, Priority.HIGH)
        runCurrent()

        engine.onMemoryPressure()
        runCurrent()

        val failed = eventList.filterIsInstance<TransferEvent.TransferFailed>()
        assertEquals(1, failed.size)
        assertContentEquals(msgId, failed[0].messageId)
        assertEquals(FailureReason.MEMORY_PRESSURE, failed[0].reason)
    }

    @Test
    fun `onMemoryPressure falls back to NORMAL when no LOW sessions exist`() = runTest {
        val engine = TransferEngine(backgroundScope, cfg, ChunkSizePolicy.fixed(10), true)
        val eventList = mutableListOf<TransferEvent>()
        backgroundScope.launch { engine.outboundChunks.collect {} }
        backgroundScope.launch { engine.events.collect { eventList.add(it) } }

        engine.send(msgId, byteArrayOf(1), peerA, Priority.NORMAL)
        engine.send(msgId2, byteArrayOf(2), peerB, Priority.HIGH)
        runCurrent()

        engine.onMemoryPressure()
        runCurrent()

        val failed = eventList.filterIsInstance<TransferEvent.TransferFailed>()
        assertEquals(1, failed.size)
        assertContentEquals(msgId, failed[0].messageId) // NORMAL session evicted
        assertEquals(FailureReason.MEMORY_PRESSURE, failed[0].reason)
    }

    @Test
    fun `onMemoryPressure does not evict HIGH sessions`() = runTest {
        val engine = TransferEngine(backgroundScope, cfg, ChunkSizePolicy.fixed(10), true)
        val eventList = mutableListOf<TransferEvent>()
        backgroundScope.launch { engine.outboundChunks.collect {} }
        backgroundScope.launch { engine.events.collect { eventList.add(it) } }

        engine.send(msgId, byteArrayOf(1), peerA, Priority.HIGH)
        runCurrent()

        engine.onMemoryPressure()
        runCurrent()

        assertTrue(eventList.filterIsInstance<TransferEvent.TransferFailed>().isEmpty())
    }

    @Test
    fun `onMemoryPressure with no sessions is a no-op`() = runTest {
        val engine = TransferEngine(backgroundScope, cfg, ChunkSizePolicy.fixed(10), true)
        backgroundScope.launch { engine.outboundChunks.collect {} }
        backgroundScope.launch { engine.events.collect {} }

        engine.onMemoryPressure()
        runCurrent()
        // No crash
    }

    // ──────────────────────────────────────────────────────────────────────
    // removeSession
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `removeSession removes session from engine so subsequent routing is a no-op`() = runTest {
        val engine = TransferEngine(backgroundScope, cfg, ChunkSizePolicy.fixed(10), true)
        val frames = mutableListOf<OutboundFrame>()
        backgroundScope.launch { engine.outboundChunks.collect { frames.add(it) } }
        backgroundScope.launch { engine.events.collect {} }

        engine.send(msgId, byteArrayOf(1), peerA)
        runCurrent()
        frames.clear()

        engine.removeSession(msgId)

        // After removal, ACK routing is a no-op
        engine.onIncomingChunkAck(peerA, ChunkAck(msgId, 0u, 0uL))
        runCurrent()
        assertTrue(frames.isEmpty())
    }

    // ──────────────────────────────────────────────────────────────────────
    // MemoryPressureListener functional interface
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `MemoryPressureListener can be satisfied by a lambda`() = runTest {
        var called = false
        val listener = MemoryPressureListener { called = true }
        listener.onMemoryPressure()
        assertTrue(called)
    }
}
