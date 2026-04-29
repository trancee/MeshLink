package ch.trancee.meshlink.transfer

import ch.trancee.meshlink.routing.OutboundFrame
import ch.trancee.meshlink.wire.Chunk
import ch.trancee.meshlink.wire.ChunkAck
import ch.trancee.meshlink.wire.Nack
import ch.trancee.meshlink.wire.ResumeRequest
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hook called by the transport layer when available memory is critically low. [TransferEngine]
 * implements this interface and evicts sessions in priority order.
 */
internal fun interface MemoryPressureListener {
    fun onMemoryPressure()
}

/**
 * Coordinates multiple concurrent [TransferSession]s, routes inbound wire messages, and exposes
 * [outboundChunks] and [events] flows consumed by the transport layer.
 *
 * @param scope CoroutineScope for all session coroutines. Pass `backgroundScope` in tests to avoid
 *   `UncompletedCoroutinesError` (MEM095).
 * @param config Transfer configuration (timeouts, retries, backoff).
 * @param chunkSizePolicy Chunk-size strategy: [ChunkSizePolicy.GATT] (244 B) or
 *   [ChunkSizePolicy.L2CAP] (4 096 B).
 * @param isGattMode When true, SACK and rate controller are active; ChunkAck messages are sent.
 *   When false, sender blasts all chunks without expecting ACKs.
 */
internal class TransferEngine(
    private val scope: CoroutineScope,
    private val config: TransferConfig = TransferConfig(),
    private val chunkSizePolicy: ChunkSizePolicy = ChunkSizePolicy.GATT,
    private val isGattMode: Boolean = true,
    private val diagnosticSink: ch.trancee.meshlink.api.DiagnosticSinkApi =
        ch.trancee.meshlink.api.NoOpDiagnosticSink,
) : MemoryPressureListener {

    private val _outboundChunks = MutableSharedFlow<OutboundFrame>(extraBufferCapacity = 64)

    /** All outbound wire frames produced by active sessions (Chunk, ChunkAck, ResumeRequest). */
    val outboundChunks: SharedFlow<OutboundFrame> = _outboundChunks

    private val _events = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 64)

    /**
     * Typed transfer events: [TransferEvent.AssemblyComplete], [TransferEvent.TransferFailed],
     * [TransferEvent.ChunkProgress].
     */
    val events: SharedFlow<TransferEvent> = _events

    private val scheduler = TransferScheduler()

    /** Keyed by `messageId.asList()` for content-equality semantics (MEM047). */
    private val sessions = mutableMapOf<List<Byte>, TransferSession>()

    /** Deadline for delivery-ACK timeout — same as the per-hop inactivity window. */
    internal val ackDeadlineMillis: Long
        get() = config.inactivityBaseTimeoutMillis

    /** Maximum chunk payload size (bytes). Used by CutThroughBuffer for overflow split. */
    internal val chunkSize: Int
        get() = chunkSizePolicy.size

    // ── Outbound (sender) ─────────────────────────────────────────────────

    /**
     * Creates a sender [TransferSession] for [payload], registers it with [TransferScheduler], and
     * starts sending.
     */
    fun send(
        messageId: ByteArray,
        payload: ByteArray,
        peerId: ByteArray,
        priority: Priority = Priority.NORMAL,
        hopCount: Int = 1,
    ) {
        val session =
            TransferSession.sender(
                messageId,
                peerId,
                payload,
                priority,
                hopCount,
                config,
                chunkSizePolicy,
                isGattMode,
                scope,
                _outboundChunks,
                _events,
            )
        sessions[messageId.asList()] = session
        scheduler.register(messageId, priority)
        session.start()
    }

    // ── Inbound routing ───────────────────────────────────────────────────

    /**
     * Routes an incoming [Chunk] to the receiver [TransferSession] for [chunk.messageId], creating
     * one on first sight.
     */
    fun onIncomingChunk(peerId: ByteArray, chunk: Chunk) {
        val key = chunk.messageId.asList()
        val session =
            sessions.getOrPut(key) {
                TransferSession.receiver(
                        chunk.messageId,
                        peerId,
                        Priority.NORMAL,
                        1,
                        config,
                        chunkSizePolicy,
                        isGattMode,
                        scope,
                        _outboundChunks,
                        _events,
                    )
                    .also { it.start() }
            }
        session.onChunk(chunk)
    }

    /** Routes an incoming [ChunkAck] to the matching sender session. */
    fun onIncomingChunkAck(peerId: ByteArray, chunkAck: ChunkAck) {
        sessions[chunkAck.messageId.asList()]?.onChunkAck(chunkAck)
    }

    /** Routes an incoming [Nack] to the matching sender session. */
    fun onIncomingNack(peerId: ByteArray, nack: Nack) {
        sessions[nack.messageId.asList()]?.onNack(nack)
    }

    /** Routes an incoming [ResumeRequest] to the matching sender session. */
    fun onIncomingResumeRequest(peerId: ByteArray, req: ResumeRequest) {
        sessions[req.messageId.asList()]?.onResumeRequest(req)
    }

    // ── Connectivity events ───────────────────────────────────────────────

    /** Notifies all sessions associated with [peerId] that the link dropped. */
    fun onPeerDisconnect(peerId: ByteArray) {
        sessions.values.filter { it.peerId.contentEquals(peerId) }.forEach { it.onDisconnect() }
    }

    /** Notifies all sessions associated with [peerId] that the link is restored. */
    fun onPeerReconnect(peerId: ByteArray) {
        sessions.values
            .filter { it.peerId.contentEquals(peerId) }
            .forEach { it.onReconnect(peerId) }
    }

    // ── Memory pressure ───────────────────────────────────────────────────

    /**
     * Evicts sessions in priority order: LOW first, then NORMAL. HIGH sessions are never evicted.
     * Emits [TransferEvent.TransferFailed] with [FailureReason.MEMORY_PRESSURE] for each evicted
     * session.
     */
    override fun onMemoryPressure() {
        var toEvict: List<TransferSession> = sessions.values.filter { it.priority == Priority.LOW }

        if (toEvict.isEmpty()) {
            toEvict = sessions.values.filter { it.priority == Priority.NORMAL }
        }

        for (session in toEvict) {
            sessions.remove(session.messageId.asList())
            scheduler.deregister(session.messageId)
            session.cancel()
            scope.launch {
                _events.emit(
                    TransferEvent.TransferFailed(session.messageId, FailureReason.MEMORY_PRESSURE)
                )
            }
        }
    }

    // ── Session lifecycle helpers ─────────────────────────────────────────

    /** Removes a completed or cancelled session from the registry. */
    internal fun removeSession(messageId: ByteArray) {
        sessions.remove(messageId.asList())
        scheduler.deregister(messageId)
    }

    /** Returns the number of active transfer sessions (both sender and receiver). */
    internal fun activeSessionCount(): Int = sessions.size

    /**
     * Polls [sessions] until empty or [timeout] expires. Returns the number of sessions still
     * active when the method returns (0 = clean drain).
     */
    internal suspend fun awaitDrain(timeout: Duration): Int {
        withTimeoutOrNull(timeout) {
            while (sessions.isNotEmpty()) {
                delay(50)
            }
        }
        return sessions.size
    }
}
