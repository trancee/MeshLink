package ch.trancee.meshlink.transfer

import ch.trancee.meshlink.routing.OutboundFrame
import ch.trancee.meshlink.wire.Chunk
import ch.trancee.meshlink.wire.ChunkAck
import ch.trancee.meshlink.wire.Nack
import ch.trancee.meshlink.wire.ResumeRequest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class TransferSessionTest {

    // ── fixtures ──────────────────────────────────────────────────────────

    private val msgId = ByteArray(4) { 0xAA.toByte() }
    private val peerId = ByteArray(4) { 0xBB.toByte() }

    /** Config with small timeouts so virtual-time tests advance quickly. */
    private val fastConfig =
        TransferConfig(
            inactivityBaseTimeoutMillis = 100L,
            maxNackRetries = 2,
            nackBaseBackoffMillis = 50L,
            maxResumeAttempts = 2,
            acksBeforeDouble = 2,
            acksBeforeQuad = 4,
        )

    private fun makeOutbound() = MutableSharedFlow<OutboundFrame>(extraBufferCapacity = 64)

    private fun makeEvents() = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 64)

    private fun ack(seq: Int, bitmap: ULong = 0uL) = ChunkAck(msgId, seq.toUShort(), bitmap)

    private fun chunk(seq: Int, total: Int, payload: ByteArray = byteArrayOf(seq.toByte())) =
        Chunk(msgId, seq.toUShort(), total.toUShort(), payload)

    // ──────────────────────────────────────────────────────────────────────
    // Receiver — basic chunk processing
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `receiver GATT mode emits ChunkProgress and ChunkAck on each chunk`() = runTest {
        val outbound = makeOutbound()
        val events = makeEvents()
        val frames = mutableListOf<OutboundFrame>()
        val eventList = mutableListOf<TransferEvent>()
        backgroundScope.launch { outbound.collect { frames.add(it) } }
        backgroundScope.launch { events.collect { eventList.add(it) } }

        val session =
            TransferSession.receiver(
                msgId,
                peerId,
                Priority.NORMAL,
                1,
                fastConfig,
                ChunkSizePolicy.fixed(10),
                true,
                backgroundScope,
                outbound,
                events,
            )
        session.start()
        session.onChunk(chunk(0, 2))
        runCurrent()

        val progresses = eventList.filterIsInstance<TransferEvent.ChunkProgress>()
        assertEquals(1, progresses.size)
        assertEquals(1, progresses[0].chunksReceived)
        assertEquals(2, progresses[0].totalChunks)

        val acks = frames.filter { it.message is ChunkAck }
        assertEquals(1, acks.size)
    }

    @Test
    fun `receiver L2CAP mode does not send ChunkAck`() = runTest {
        val outbound = makeOutbound()
        val events = makeEvents()
        val frames = mutableListOf<OutboundFrame>()
        backgroundScope.launch { outbound.collect { frames.add(it) } }

        val session =
            TransferSession.receiver(
                msgId,
                peerId,
                Priority.NORMAL,
                1,
                fastConfig,
                ChunkSizePolicy.fixed(10),
                false, // isGattMode = false
                backgroundScope,
                outbound,
                events,
            )
        session.start()
        session.onChunk(chunk(0, 2))
        runCurrent()

        assertTrue(frames.none { it.message is ChunkAck })
    }

    @Test
    fun `receiver assembles payload from multiple chunks and emits AssemblyComplete`() = runTest {
        val outbound = makeOutbound()
        val events = makeEvents()
        val eventList = mutableListOf<TransferEvent>()
        backgroundScope.launch { outbound.collect {} }
        backgroundScope.launch { events.collect { eventList.add(it) } }

        val session =
            TransferSession.receiver(
                msgId,
                peerId,
                Priority.NORMAL,
                1,
                fastConfig,
                ChunkSizePolicy.fixed(10),
                true,
                backgroundScope,
                outbound,
                events,
            )
        session.start()
        session.onChunk(chunk(0, 3, byteArrayOf(1, 2)))
        session.onChunk(chunk(1, 3, byteArrayOf(3, 4)))
        session.onChunk(chunk(2, 3, byteArrayOf(5)))
        runCurrent()

        val assembled = eventList.filterIsInstance<TransferEvent.AssemblyComplete>()
        assertEquals(1, assembled.size)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), assembled[0].payload)
        assertContentEquals(peerId, assembled[0].fromPeerId)
    }

    @Test
    fun `receiver ignores duplicate chunk`() = runTest {
        val events = makeEvents()
        val eventList = mutableListOf<TransferEvent>()
        backgroundScope.launch { makeOutbound().collect {} }
        backgroundScope.launch { events.collect { eventList.add(it) } }

        val session =
            TransferSession.receiver(
                msgId,
                peerId,
                Priority.NORMAL,
                1,
                fastConfig,
                ChunkSizePolicy.fixed(10),
                true,
                backgroundScope,
                makeOutbound(),
                events,
            )
        session.start()
        session.onChunk(chunk(0, 2))
        session.onChunk(chunk(0, 2)) // duplicate
        runCurrent()

        val progresses = eventList.filterIsInstance<TransferEvent.ChunkProgress>()
        // Both calls emit progress (timer reset each time), but chunksReceived stays 1
        assertEquals(2, progresses.size)
        assertTrue(progresses.all { it.chunksReceived == 1 })
    }

    @Test
    fun `receiver ignores out-of-bounds seqNum`() = runTest {
        val events = makeEvents()
        val eventList = mutableListOf<TransferEvent>()
        backgroundScope.launch { makeOutbound().collect {} }
        backgroundScope.launch { events.collect { eventList.add(it) } }

        val session =
            TransferSession.receiver(
                msgId,
                peerId,
                Priority.NORMAL,
                1,
                fastConfig,
                ChunkSizePolicy.fixed(10),
                true,
                backgroundScope,
                makeOutbound(),
                events,
            )
        session.start()
        session.onChunk(chunk(0, 2)) // sets totalChunksExpected=2
        session.onChunk(chunk(2, 2)) // seqIdx=2 >= totalChunksExpected=2 → ignored
        runCurrent()

        val progresses = eventList.filterIsInstance<TransferEvent.ChunkProgress>()
        assertEquals(1, progresses.size) // only first chunk counted
    }

    @Test
    fun `receiver inactivity timeout emits TransferFailed INACTIVITY_TIMEOUT`() = runTest {
        val events = makeEvents()
        val eventList = mutableListOf<TransferEvent>()
        backgroundScope.launch { makeOutbound().collect {} }
        backgroundScope.launch { events.collect { eventList.add(it) } }

        val session =
            TransferSession.receiver(
                msgId,
                peerId,
                Priority.NORMAL,
                1,
                fastConfig,
                ChunkSizePolicy.fixed(10),
                true,
                backgroundScope,
                makeOutbound(),
                events,
            )
        session.start()
        session.onChunk(chunk(0, 2)) // partial: only chunk 0 received
        runCurrent()
        advanceTimeBy(fastConfig.inactivityBaseTimeoutMillis + 1L)
        runCurrent()

        val failed = eventList.filterIsInstance<TransferEvent.TransferFailed>()
        assertEquals(1, failed.size)
        assertEquals(FailureReason.INACTIVITY_TIMEOUT, failed[0].reason)
    }

    @Test
    fun `receiver hopCount scales inactivity timeout`() = runTest {
        val events = makeEvents()
        val eventList = mutableListOf<TransferEvent>()
        backgroundScope.launch { makeOutbound().collect {} }
        backgroundScope.launch { events.collect { eventList.add(it) } }

        val session =
            TransferSession.receiver(
                msgId,
                peerId,
                Priority.NORMAL,
                2, // hopCount = 2 → timeout = 100 * min(2,4) = 200ms
                fastConfig,
                ChunkSizePolicy.fixed(10),
                true,
                backgroundScope,
                makeOutbound(),
                events,
            )
        session.start()
        session.onChunk(chunk(0, 2))
        runCurrent()

        // No timeout at 100ms (less than 200ms scaled timeout)
        advanceTimeBy(100L)
        runCurrent()
        assertTrue(eventList.filterIsInstance<TransferEvent.TransferFailed>().isEmpty())

        // Timeout fires at 200ms
        advanceTimeBy(101L)
        runCurrent()
        assertEquals(1, eventList.filterIsInstance<TransferEvent.TransferFailed>().size)
    }

    @Test
    fun `receiver disconnect before start cancels dummy inactivity job without crash`() = runTest {
        val session =
            TransferSession.receiver(
                msgId,
                peerId,
                Priority.NORMAL,
                1,
                fastConfig,
                ChunkSizePolicy.fixed(10),
                true,
                backgroundScope,
                makeOutbound(),
                makeEvents(),
            )
        // cancel() before start() — both Job fields are cancelled dummies
        session.cancel()
        runCurrent()
        // No crash and no event
    }

    @Test
    fun `receiver disconnect after start cancels running inactivity timer`() = runTest {
        val events = makeEvents()
        val eventList = mutableListOf<TransferEvent>()
        backgroundScope.launch { makeOutbound().collect {} }
        backgroundScope.launch { events.collect { eventList.add(it) } }

        val session =
            TransferSession.receiver(
                msgId,
                peerId,
                Priority.NORMAL,
                1,
                fastConfig,
                ChunkSizePolicy.fixed(10),
                true,
                backgroundScope,
                makeOutbound(),
                events,
            )
        session.start()
        session.onChunk(chunk(0, 2)) // starts inactivity timer
        runCurrent()
        session.onDisconnect() // should cancel the timer

        // Advance past what the timeout would have been
        advanceTimeBy(fastConfig.inactivityBaseTimeoutMillis + 200L)
        runCurrent()

        // Timer was cancelled, no failure emitted
        assertTrue(eventList.filterIsInstance<TransferEvent.TransferFailed>().isEmpty())
    }

    @Test
    fun `receiver reconnect while transfer in progress sends ResumeRequest`() = runTest {
        val outbound = makeOutbound()
        val frames = mutableListOf<OutboundFrame>()
        backgroundScope.launch { outbound.collect { frames.add(it) } }

        val session =
            TransferSession.receiver(
                msgId,
                peerId,
                Priority.NORMAL,
                1,
                fastConfig,
                ChunkSizePolicy.fixed(2),
                true,
                backgroundScope,
                outbound,
                makeEvents(),
            )
        session.start()
        // Receive one chunk (2 bytes)
        session.onChunk(chunk(0, 3, byteArrayOf(1, 2)))
        runCurrent()
        frames.clear()

        val newPeerId = ByteArray(4) { 0xCC.toByte() }
        session.onReconnect(newPeerId)
        runCurrent()

        val resumes = frames.filter { it.message is ResumeRequest }
        assertEquals(1, resumes.size)
        val req = resumes[0].message as ResumeRequest
        assertEquals(2u, req.bytesReceived) // 2 bytes received so far
        assertContentEquals(newPeerId, resumes[0].peerId)
    }

    @Test
    fun `receiver reconnect before any chunk does not send ResumeRequest`() = runTest {
        val outbound = makeOutbound()
        val frames = mutableListOf<OutboundFrame>()
        backgroundScope.launch { outbound.collect { frames.add(it) } }

        val session =
            TransferSession.receiver(
                msgId,
                peerId,
                Priority.NORMAL,
                1,
                fastConfig,
                ChunkSizePolicy.fixed(10),
                true,
                backgroundScope,
                outbound,
                makeEvents(),
            )
        session.start()
        session.onReconnect() // totalChunksExpected still -1
        runCurrent()

        assertFalse(frames.any { it.message is ResumeRequest })
    }

    @Test
    fun `receiver reconnect after complete transfer does not send ResumeRequest`() = runTest {
        val outbound = makeOutbound()
        val events = makeEvents()
        val frames = mutableListOf<OutboundFrame>()
        backgroundScope.launch { outbound.collect { frames.add(it) } }
        backgroundScope.launch { events.collect {} }

        val session =
            TransferSession.receiver(
                msgId,
                peerId,
                Priority.NORMAL,
                1,
                fastConfig,
                ChunkSizePolicy.fixed(10),
                true,
                backgroundScope,
                outbound,
                events,
            )
        session.start()
        session.onChunk(chunk(0, 1, byteArrayOf(42))) // single-chunk → complete
        runCurrent()
        frames.clear()

        session.onReconnect() // chunksReceived == totalChunksExpected → no resume
        runCurrent()

        assertFalse(frames.any { it.message is ResumeRequest })
    }

    // ──────────────────────────────────────────────────────────────────────
    // Sender — basic flows
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `sender empty payload sends one empty chunk`() = runTest {
        val outbound = makeOutbound()
        val frames = mutableListOf<OutboundFrame>()
        backgroundScope.launch { outbound.collect { frames.add(it) } }

        val session =
            TransferSession.sender(
                msgId,
                peerId,
                ByteArray(0), // 0-byte payload → splitIntoChunks returns [ByteArray(0)]
                Priority.NORMAL,
                1,
                fastConfig,
                ChunkSizePolicy.fixed(10),
                true,
                backgroundScope,
                outbound,
                makeEvents(),
            )
        session.start()
        runCurrent()

        // One empty chunk is emitted; sender waits for ACK
        val chunks = frames.filter { it.message is Chunk }
        assertEquals(1, chunks.size)
        val c = chunks[0].message as Chunk
        assertEquals(0, c.payload.size)
        assertEquals(1u, c.totalChunks)
    }

    @Test
    fun `sender L2CAP mode blasts all chunks immediately`() = runTest {
        val outbound = makeOutbound()
        val events = makeEvents()
        val frames = mutableListOf<OutboundFrame>()
        backgroundScope.launch { outbound.collect { frames.add(it) } }
        backgroundScope.launch { events.collect {} }

        val payload = ByteArray(9) { it.toByte() } // 3 chunks of 3 bytes
        val session =
            TransferSession.sender(
                msgId,
                peerId,
                payload,
                Priority.NORMAL,
                1,
                fastConfig,
                ChunkSizePolicy.fixed(3),
                false, // L2CAP
                backgroundScope,
                outbound,
                makeEvents(),
            )
        session.start()
        runCurrent()

        val chunks = frames.filter { it.message is Chunk }
        assertEquals(3, chunks.size)
        // Sender exits without needing ACKs
    }

    @Test
    fun `sender GATT mode single chunk completes after ACK`() = runTest {
        val outbound = makeOutbound()
        val events = makeEvents()
        val frames = mutableListOf<OutboundFrame>()
        val eventList = mutableListOf<TransferEvent>()
        backgroundScope.launch { outbound.collect { frames.add(it) } }
        backgroundScope.launch { events.collect { eventList.add(it) } }

        val session =
            TransferSession.sender(
                msgId,
                peerId,
                byteArrayOf(1, 2, 3),
                Priority.NORMAL,
                1,
                fastConfig,
                ChunkSizePolicy.fixed(10),
                true,
                backgroundScope,
                outbound,
                events,
            )
        session.start()
        runCurrent()
        assertEquals(1, frames.filter { it.message is Chunk }.size)

        session.onChunkAck(ack(0))
        runCurrent()

        assertTrue(eventList.filterIsInstance<TransferEvent.TransferFailed>().isEmpty())
    }

    @Test
    fun `sender GATT mode multi-chunk completes after all ACKs`() = runTest {
        val outbound = makeOutbound()
        val events = makeEvents()
        val frames = mutableListOf<OutboundFrame>()
        backgroundScope.launch { outbound.collect { frames.add(it) } }
        backgroundScope.launch { events.collect {} }

        // 3 chunks of 1 byte each; acksBeforeDouble=2 → rate stays 1 for first 2 ACKs
        val session =
            TransferSession.sender(
                msgId,
                peerId,
                byteArrayOf(1, 2, 3),
                Priority.NORMAL,
                1,
                fastConfig,
                ChunkSizePolicy.fixed(1),
                true,
                backgroundScope,
                outbound,
                events,
            )
        session.start()
        runCurrent()
        assertEquals(1, frames.filter { it.message is Chunk }.size) // chunk 0 sent

        session.onChunkAck(ack(0)) // ACK chunk 0 → send chunk 1
        runCurrent()
        session.onChunkAck(ack(1)) // ACK chunk 1 → send chunk 2 (rate maybe 2 now)
        runCurrent()
        session.onChunkAck(ack(2)) // ACK chunk 2 → all done
        runCurrent()

        val chunksSent = frames.filter { it.message is Chunk }
        assertTrue(chunksSent.size >= 3)
    }

    @Test
    fun `sender ACK while disconnected is ignored`() = runTest {
        val outbound = makeOutbound()
        val events = makeEvents()
        val frames = mutableListOf<OutboundFrame>()
        val eventList = mutableListOf<TransferEvent>()
        backgroundScope.launch { outbound.collect { frames.add(it) } }
        backgroundScope.launch { events.collect { eventList.add(it) } }

        val session =
            TransferSession.sender(
                msgId,
                peerId,
                byteArrayOf(1, 2),
                Priority.NORMAL,
                1,
                fastConfig,
                ChunkSizePolicy.fixed(1),
                true,
                backgroundScope,
                outbound,
                events,
            )
        session.start()
        runCurrent()

        session.onDisconnect()
        session.onChunkAck(ack(0)) // ignored while disconnected
        runCurrent()

        // No completion event
        assertTrue(eventList.filterIsInstance<TransferEvent.TransferFailed>().isEmpty())
    }

    @Test
    fun `sender reconnect while not disconnected does not affect state`() = runTest {
        val outbound = makeOutbound()
        val events = makeEvents()
        backgroundScope.launch { outbound.collect {} }
        backgroundScope.launch { events.collect {} }

        val session =
            TransferSession.sender(
                msgId,
                peerId,
                byteArrayOf(1),
                Priority.NORMAL,
                1,
                fastConfig,
                ChunkSizePolicy.fixed(10),
                true,
                backgroundScope,
                outbound,
                events,
            )
        session.start()
        runCurrent()

        // Reconnect while NOT disconnected (no-op for reconnect branch)
        val newPeer = ByteArray(4) { 0xEE.toByte() }
        session.onReconnect(newPeer)
        runCurrent()
        // Session continues normally; peerId updated
        assertContentEquals(newPeer, session.peerId)
    }

    @Test
    fun `sender resume while not disconnected is ignored`() = runTest {
        val outbound = makeOutbound()
        val events = makeEvents()
        backgroundScope.launch { outbound.collect {} }
        backgroundScope.launch { events.collect {} }

        val session =
            TransferSession.sender(
                msgId,
                peerId,
                byteArrayOf(1, 2),
                Priority.NORMAL,
                1,
                fastConfig,
                ChunkSizePolicy.fixed(1),
                true,
                backgroundScope,
                outbound,
                events,
            )
        session.start()
        runCurrent()

        session.onResumeRequest(ResumeRequest(msgId, 0u))
        runCurrent()
        // No crash, session continues
    }

    // ──────────────────────────────────────────────────────────────────────
    // Sender — NACK handling
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `sender NACK with non-buffer-full reason is ignored`() = runTest {
        val outbound = makeOutbound()
        val events = makeEvents()
        val frames = mutableListOf<OutboundFrame>()
        backgroundScope.launch { outbound.collect { frames.add(it) } }
        backgroundScope.launch { events.collect {} }

        val session =
            TransferSession.sender(
                msgId,
                peerId,
                byteArrayOf(1),
                Priority.NORMAL,
                1,
                fastConfig,
                ChunkSizePolicy.fixed(10),
                true,
                backgroundScope,
                outbound,
                events,
            )
        session.start()
        runCurrent()
        val initial = frames.size

        session.onNack(Nack(msgId, 42u)) // reason 42 ≠ NACK_REASON_BUFFER_FULL (0)
        runCurrent()
        // sendBatch not triggered
        assertEquals(initial, frames.size)
    }

    @Test
    fun `sender NACK buffer-full triggers backoff then retry`() = runTest {
        val outbound = makeOutbound()
        val events = makeEvents()
        val frames = mutableListOf<OutboundFrame>()
        backgroundScope.launch { outbound.collect { frames.add(it) } }
        backgroundScope.launch { events.collect {} }

        val session =
            TransferSession.sender(
                msgId,
                peerId,
                byteArrayOf(1),
                Priority.NORMAL,
                1,
                fastConfig,
                ChunkSizePolicy.fixed(10),
                true,
                backgroundScope,
                outbound,
                events,
            )
        session.start()
        runCurrent()
        val initial = frames.size

        session.onNack(Nack(msgId, NACK_REASON_BUFFER_FULL))
        advanceTimeBy(fastConfig.nackBaseBackoffMillis + 1L)
        runCurrent()

        // Re-send after backoff
        assertTrue(frames.size > initial)
    }

    @Test
    fun `sender NACK retry exhaustion emits TransferFailed BUFFER_FULL_RETRY_EXHAUSTED`() =
        runTest {
            val outbound = makeOutbound()
            val events = makeEvents()
            val eventList = mutableListOf<TransferEvent>()
            backgroundScope.launch { outbound.collect {} }
            backgroundScope.launch { events.collect { eventList.add(it) } }

            // Long inactivity so the timer never fires during this test
            val cfg =
                TransferConfig(
                    inactivityBaseTimeoutMillis = 60_000L,
                    maxNackRetries = 2,
                    nackBaseBackoffMillis = 10L,
                )
            val session =
                TransferSession.sender(
                    msgId,
                    peerId,
                    byteArrayOf(1),
                    Priority.NORMAL,
                    1,
                    cfg,
                    ChunkSizePolicy.fixed(10),
                    true,
                    backgroundScope,
                    outbound,
                    events,
                )
            session.start()
            runCurrent()

            // NACK 1: nackRetryCount=1 ≤ 2 → backoff(10ms), retry
            session.onNack(Nack(msgId, NACK_REASON_BUFFER_FULL))
            advanceTimeBy(11L)
            runCurrent()

            // NACK 2: nackRetryCount=2 ≤ 2 → backoff(20ms), retry
            session.onNack(Nack(msgId, NACK_REASON_BUFFER_FULL))
            advanceTimeBy(21L)
            runCurrent()

            // NACK 3: nackRetryCount=3 > 2 → failure emitted immediately (no delay)
            session.onNack(Nack(msgId, NACK_REASON_BUFFER_FULL))
            runCurrent()

            val failed = eventList.filterIsInstance<TransferEvent.TransferFailed>()
            assertEquals(1, failed.size)
            assertEquals(FailureReason.BUFFER_FULL_RETRY_EXHAUSTED, failed[0].reason)
        }

    @Test
    fun `sender NACK while disconnected is ignored`() = runTest {
        val outbound = makeOutbound()
        val events = makeEvents()
        val eventList = mutableListOf<TransferEvent>()
        backgroundScope.launch { outbound.collect {} }
        backgroundScope.launch { events.collect { eventList.add(it) } }

        val session =
            TransferSession.sender(
                msgId,
                peerId,
                byteArrayOf(1),
                Priority.NORMAL,
                1,
                fastConfig,
                ChunkSizePolicy.fixed(10),
                true,
                backgroundScope,
                outbound,
                events,
            )
        session.start()
        runCurrent()
        session.onDisconnect()
        session.onNack(Nack(msgId, NACK_REASON_BUFFER_FULL))
        advanceTimeBy(fastConfig.nackBaseBackoffMillis + 1L)
        runCurrent()
        assertTrue(eventList.filterIsInstance<TransferEvent.TransferFailed>().isEmpty())
    }

    // ──────────────────────────────────────────────────────────────────────
    // Sender — inactivity timeout / degradation
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `sender inactivity timeout retries before entering degradation mode`() = runTest {
        val outbound = makeOutbound()
        val frames = mutableListOf<OutboundFrame>()
        backgroundScope.launch { outbound.collect { frames.add(it) } }

        val session =
            TransferSession.sender(
                msgId,
                peerId,
                byteArrayOf(1),
                Priority.NORMAL,
                1,
                fastConfig, // inactivityBaseTimeoutMillis=100
                ChunkSizePolicy.fixed(10),
                true,
                backgroundScope,
                outbound,
                makeEvents(),
            )
        session.start()
        runCurrent()
        val initial = frames.size

        // One timeout (ackTimeoutCount=1 < ACK_TIMEOUTS_TO_DEGRADE=3) → retry sendBatch
        advanceTimeBy(fastConfig.inactivityBaseTimeoutMillis + 1L)
        runCurrent()

        assertTrue(frames.size > initial)
    }

    @Test
    fun `sender 3 inactivity timeouts enter degradation and eventually fail with probe error`() =
        runTest {
            val outbound = makeOutbound()
            val events = makeEvents()
            val eventList = mutableListOf<TransferEvent>()
            backgroundScope.launch { outbound.collect {} }
            backgroundScope.launch { events.collect { eventList.add(it) } }

            val session =
                TransferSession.sender(
                    msgId,
                    peerId,
                    byteArrayOf(1),
                    Priority.NORMAL,
                    1,
                    fastConfig, // inactivityBaseTimeoutMillis=100
                    ChunkSizePolicy.fixed(10),
                    true,
                    backgroundScope,
                    outbound,
                    events,
                )
            session.start()
            runCurrent()

            // 3 timeouts (100ms each) enter degradation (ACK_TIMEOUTS_TO_DEGRADE=3),
            // then 3 probe failures (DEGRADATION_PAUSE_MILLIS=2000ms each) → fatal
            // Total time: 3*100 + 3*(2000+100) = 300 + 6300 = 6600ms
            advanceTimeBy(7_000L)
            runCurrent()

            val failed = eventList.filterIsInstance<TransferEvent.TransferFailed>()
            assertEquals(1, failed.size)
            assertEquals(FailureReason.DEGRADATION_PROBE_FAILED, failed[0].reason)
        }

    @Test
    fun `sender probe uses ackSeq+1 after first ACK`() = runTest {
        val outbound = makeOutbound()
        val frames = mutableListOf<OutboundFrame>()
        backgroundScope.launch { outbound.collect { frames.add(it) } }

        // 2 chunks; ACK first one, then let inactivity fire to trigger probe on chunk 1
        val session =
            TransferSession.sender(
                msgId,
                peerId,
                byteArrayOf(1, 2),
                Priority.NORMAL,
                1,
                fastConfig,
                ChunkSizePolicy.fixed(1),
                true,
                backgroundScope,
                outbound,
                makeEvents(),
            )
        session.start()
        runCurrent()
        session.onChunkAck(ack(0)) // ACK chunk 0 → sends chunk 1, ackSeq=0
        runCurrent()
        frames.clear()

        // Trigger 3 timeouts to enter degradation and fire first probe
        advanceTimeBy(3 * fastConfig.inactivityBaseTimeoutMillis + 2_001L)
        runCurrent()

        val chunks = frames.filter { it.message is Chunk }
        assertTrue(chunks.isNotEmpty())
        // Probe should be for chunk 1 (ackSeq+1 = 0+1 = 1)
        val probeChunk = chunks.last().message as Chunk
        assertEquals(1u, probeChunk.seqNum)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Sender — disconnect / reconnect / resume
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `sender disconnect sets disconnected flag and cancels inactivity timer`() = runTest {
        val events = makeEvents()
        val eventList = mutableListOf<TransferEvent>()
        backgroundScope.launch { makeOutbound().collect {} }
        backgroundScope.launch { events.collect { eventList.add(it) } }

        val session =
            TransferSession.sender(
                msgId,
                peerId,
                byteArrayOf(1),
                Priority.NORMAL,
                1,
                fastConfig,
                ChunkSizePolicy.fixed(10),
                true,
                backgroundScope,
                makeOutbound(),
                events,
            )
        session.start()
        runCurrent()

        session.onDisconnect()
        // Advance past where inactivity timeout would have fired
        advanceTimeBy(fastConfig.inactivityBaseTimeoutMillis * 5L + 10_000L)
        runCurrent()

        // No inactivity failure (timer was cancelled on disconnect)
        assertTrue(eventList.filterIsInstance<TransferEvent.TransferFailed>().isEmpty())
    }

    @Test
    fun `sender disconnect then reconnect then resume resumes transfer`() = runTest {
        val outbound = makeOutbound()
        val events = makeEvents()
        val frames = mutableListOf<OutboundFrame>()
        val eventList = mutableListOf<TransferEvent>()
        backgroundScope.launch { outbound.collect { frames.add(it) } }
        backgroundScope.launch { events.collect { eventList.add(it) } }

        val session =
            TransferSession.sender(
                msgId,
                peerId,
                byteArrayOf(1, 2, 3),
                Priority.NORMAL,
                1,
                fastConfig,
                ChunkSizePolicy.fixed(1),
                true,
                backgroundScope,
                outbound,
                events,
            )
        session.start()
        runCurrent()

        session.onDisconnect()
        runCurrent()

        val newPeer = ByteArray(4) { 0xDD.toByte() }
        session.onReconnect(newPeer)
        runCurrent()
        frames.clear()

        // Resume from beginning (bytesReceived=0 → nextIdxToSend=0)
        session.onResumeRequest(ResumeRequest(msgId, 0u))
        runCurrent()

        val chunks = frames.filter { it.message is Chunk }
        assertTrue(chunks.isNotEmpty())
        assertEquals(0u, (chunks[0].message as Chunk).seqNum)
    }

    @Test
    fun `sender resume attempts exhaustion emits TransferFailed RESUME_FAILED`() = runTest {
        val outbound = makeOutbound()
        val events = makeEvents()
        val eventList = mutableListOf<TransferEvent>()
        backgroundScope.launch { outbound.collect {} }
        backgroundScope.launch { events.collect { eventList.add(it) } }

        val session =
            TransferSession.sender(
                msgId,
                peerId,
                byteArrayOf(1),
                Priority.NORMAL,
                1,
                fastConfig, // maxResumeAttempts=2
                ChunkSizePolicy.fixed(10),
                true,
                backgroundScope,
                outbound,
                events,
            )
        session.start()
        runCurrent()

        // Disconnect first so resume requests are processed
        session.onDisconnect()
        runCurrent()

        // maxResumeAttempts=2 → 3rd resume exhausts the limit
        session.onResumeRequest(ResumeRequest(msgId, 0u)) // resumeAttempts=1 ≤ 2 → OK
        runCurrent()
        session.onResumeRequest(
            ResumeRequest(msgId, 0u)
        ) // senderDisconnected=false after first resume → ignored
        runCurrent()
        // Disconnect again to re-enable resume processing
        session.onDisconnect()
        runCurrent()
        session.onResumeRequest(ResumeRequest(msgId, 0u)) // resumeAttempts=2 ≤ 2 → OK
        runCurrent()
        session.onDisconnect()
        runCurrent()
        session.onResumeRequest(ResumeRequest(msgId, 0u)) // resumeAttempts=3 > 2 → FAIL
        runCurrent()

        val failed = eventList.filterIsInstance<TransferEvent.TransferFailed>()
        assertEquals(1, failed.size)
        assertEquals(FailureReason.RESUME_FAILED, failed[0].reason)
    }

    @Test
    fun `sender resume with partial bytes received resets SACK to correct index`() = runTest {
        val outbound = makeOutbound()
        val events = makeEvents()
        val frames = mutableListOf<OutboundFrame>()
        backgroundScope.launch { outbound.collect { frames.add(it) } }
        backgroundScope.launch { events.collect {} }

        // 5 chunks of 1 byte each; after disconnect, resume claims 2 bytes received
        // → alignedOffset = 2/1 * 1 = 2, nextIdxToSend=2, resetSenderSackToIndex(2)
        // → loop runs for i in 0..1 (marks chunks 0 and 1 as received)
        val session =
            TransferSession.sender(
                msgId,
                peerId,
                byteArrayOf(1, 2, 3, 4, 5),
                Priority.NORMAL,
                1,
                TransferConfig(inactivityBaseTimeoutMillis = 60_000L),
                ChunkSizePolicy.fixed(1),
                true,
                backgroundScope,
                outbound,
                events,
            )
        session.start()
        runCurrent()

        session.onDisconnect()
        runCurrent()
        frames.clear()

        // bytesReceived=2 → nextIdxToSend=2, resetSenderSackToIndex(2) → loop body executed twice
        session.onResumeRequest(ResumeRequest(msgId, 2u))
        runCurrent()

        val chunks = frames.filter { it.message is Chunk }
        assertTrue(chunks.isNotEmpty())
        // Resumed from chunk index 2 (first unACKed after 2 bytes)
        assertEquals(2u, (chunks[0].message as Chunk).seqNum)
    }

    @Test
    fun `sender cancel closes channel and sender loop exits cleanly`() = runTest {
        val outbound = makeOutbound()
        val events = makeEvents()
        backgroundScope.launch { outbound.collect {} }
        backgroundScope.launch { events.collect {} }

        val session =
            TransferSession.sender(
                msgId,
                peerId,
                byteArrayOf(1),
                Priority.NORMAL,
                1,
                fastConfig,
                ChunkSizePolicy.fixed(10),
                true,
                backgroundScope,
                outbound,
                events,
            )
        session.start()
        runCurrent()
        session.cancel() // closes senderEvents channel
        runCurrent()
        // No crash, loop exited via the ClosedReceiveChannelException catch
    }

    // ──────────────────────────────────────────────────────────────────────
    // Sender — re-send loop isMissing=false branch
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `sender skips non-missing chunk in re-send loop when SACK bitmap marks it received`() =
        runTest {
            val outbound = makeOutbound()
            val events = makeEvents()
            val frames = mutableListOf<OutboundFrame>()
            backgroundScope.launch { outbound.collect { frames.add(it) } }
            backgroundScope.launch { events.collect {} }

            // 3 chunks; acksBeforeDouble=1 → rate doubles to 2 after first ACK
            val config =
                TransferConfig(
                    inactivityBaseTimeoutMillis = 100L,
                    maxNackRetries = 5,
                    nackBaseBackoffMillis = 50L,
                    maxResumeAttempts = 2,
                    acksBeforeDouble = 1,
                    acksBeforeQuad = 2,
                )
            val session =
                TransferSession.sender(
                    msgId,
                    peerId,
                    byteArrayOf(1, 2, 3),
                    Priority.NORMAL,
                    1,
                    config,
                    ChunkSizePolicy.fixed(1),
                    true,
                    backgroundScope,
                    outbound,
                    events,
                )
            session.start()
            runCurrent()
            // Sends chunk 0 (rate=1)

            // ACK with ackSeq=0 (marks chunk 0 cumulative) AND sackBitmask=2 (bit 1 →
            // chunk at ackSeq+1+1 = 2 → chunk 2 received via SACK bitmap, NOT chunk 1).
            // This keeps ackSeq=0 (chunk 1 still missing), so re-send loop covers both
            // isMissing=true (chunk 1) and isMissing=false (chunk 2). Rate ramps to 2.
            session.onChunkAck(ChunkAck(msgId, 0u.toUShort(), 2uL))
            runCurrent()
            // After ACK: ackSeq=0 (chunk 0 cumulative), chunk 2 marked via SACK bitmap (bit 1),
            // ackSeq stays at 0 (chunk 1 still missing, bit 0 not set). rate=2.
            // sendBatch: baseIdx=1, nextIdxToSend=1 → no re-sends; new-chunk loop sends 1,2.
            frames.clear()

            // Trigger NACK → sendBatch re-runs with baseIdx=1, nextIdxToSend=3:
            //   idx=1: isMissing(1)? bit 0 from ackSeq=0 → clear → TRUE → re-send
            //   idx=2: isMissing(2)? bit 1 from ackSeq=0 → SET (chunk 2 via SACK) → FALSE → skip
            session.onNack(Nack(msgId, NACK_REASON_BUFFER_FULL))
            advanceTimeBy(config.nackBaseBackoffMillis + 1L)
            runCurrent()

            val chunksSent =
                frames.filter { it.message is Chunk }.map { (it.message as Chunk).seqNum.toInt() }
            // Chunk 2 should NOT be re-sent (it's in SACK bitmap), chunk 1 SHOULD be re-sent
            assertFalse(chunksSent.contains(2), "chunk 2 should not be re-sent (marked in SACK)")
            assertTrue(chunksSent.contains(1), "chunk 1 should be re-sent (missing)")
        }

    // ──────────────────────────────────────────────────────────────────────
    // Sender — rate controller + baseIdx after ACK
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `sender sendBatch uses ackSeq+1 as baseIdx after first ACK`() = runTest {
        val outbound = makeOutbound()
        val frames = mutableListOf<OutboundFrame>()
        backgroundScope.launch { outbound.collect { frames.add(it) } }

        val config =
            TransferConfig(
                inactivityBaseTimeoutMillis = 100L,
                maxNackRetries = 2,
                nackBaseBackoffMillis = 50L,
                acksBeforeDouble = 1,
                acksBeforeQuad = 10,
            )
        val session =
            TransferSession.sender(
                msgId,
                peerId,
                byteArrayOf(1, 2, 3, 4),
                Priority.NORMAL,
                1,
                config,
                ChunkSizePolicy.fixed(1),
                true,
                backgroundScope,
                outbound,
                makeEvents(),
            )
        session.start()
        runCurrent()
        // Chunk 0 sent (rate=1, ackSeq=MAX_VALUE → baseIdx=0)
        assertEquals(1, frames.filter { it.message is Chunk }.size)

        session.onChunkAck(ack(0)) // ACK chunk 0 → ackSeq=0, rate→2, baseIdx=1
        runCurrent()

        // After first ACK: sendBatch with rate=2, baseIdx=1 (ackSeq+1).
        // Sends chunks 1 and 2 (new). No re-sends since nextIdxToSend was 1 and now advances.
        val allChunks = frames.filter { it.message is Chunk }
        assertTrue(allChunks.size >= 2) // at least chunks 0, 1, and possibly 2
    }
}
