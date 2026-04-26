package ch.trancee.meshlink.transfer

import ch.trancee.meshlink.routing.OutboundFrame
import ch.trancee.meshlink.wire.Chunk
import ch.trancee.meshlink.wire.ChunkAck
import ch.trancee.meshlink.wire.Nack
import ch.trancee.meshlink.wire.ResumeRequest
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/** NACK reason code indicating the remote receiver's buffer is full. */
internal const val NACK_REASON_BUFFER_FULL: UByte = 0u

private const val DEGRADATION_PAUSE_MILLIS = 2_000L
private const val ACK_TIMEOUTS_TO_DEGRADE = 3
private const val MAX_PROBE_FAILURES = 3

/**
 * Per-transfer state machine for both sender and receiver roles.
 *
 * Use [sender] / [receiver] factory methods to construct, then call [start] to begin execution. All
 * timers use `delay()` in coroutines launched on [scope]; TestCoroutineScheduler drives them via
 * `advanceTo()` in tests.
 */
internal class TransferSession
private constructor(
    val messageId: ByteArray,
    var peerId: ByteArray,
    val priority: Priority,
    private val config: TransferConfig,
    private val chunkSizePolicy: ChunkSizePolicy,
    private val isGattMode: Boolean,
    private val hopCount: Int,
    private val scope: CoroutineScope,
    private val outboundFlow: MutableSharedFlow<OutboundFrame>,
    private val eventFlow: MutableSharedFlow<TransferEvent>,
    /** Non-null for sender role, null for receiver role. */
    payload: ByteArray?,
) {
    val isSender: Boolean = payload != null
    private val inactivityTimeoutMillis = config.inactivityBaseTimeoutMillis * min(hopCount, 4)

    // ── Sender state ──────────────────────────────────────────────────────
    private val chunks: List<ByteArray> =
        if (payload != null) splitIntoChunks(payload, chunkSizePolicy.size) else emptyList()
    private val totalChunks: UShort = chunks.size.toUShort()
    private var senderSack = SackTracker()
    private val rateController = ObservationRateController(config)
    private var nextIndexToSend: Int = 0
    private var ackTimeoutCount: Int = 0
    private var probeFailureCount: Int = 0
    private var nackRetryCount: Int = 0
    private var resumeAttempts: Int = 0
    private var senderDisconnected: Boolean = false
    private var inDegradationMode: Boolean = false
    // Initialised to a cancelled Job so .cancel() is always safe (idempotent on an already-
    // cancelled Job) regardless of whether resetSenderInactivityTimer() has run yet.
    private var senderInactivityJob: Job = Job().also { it.cancel() }

    // ── Sender event channel ──────────────────────────────────────────────
    private sealed interface SenderEvent {
        data class Ack(val ack: ChunkAck) : SenderEvent

        data class Nack(val nack: ch.trancee.meshlink.wire.Nack) : SenderEvent

        data class Resume(val req: ResumeRequest) : SenderEvent

        data object Disconnect : SenderEvent

        data class Reconnect(val newPeerId: ByteArray) : SenderEvent

        data object InactivityTimeout : SenderEvent
    }

    private val senderEvents = Channel<SenderEvent>(Channel.UNLIMITED)

    // ── Receiver state ────────────────────────────────────────────────────
    private val receiverSack = SackTracker()
    private var receiverBuffer: Array<ByteArray?> = arrayOfNulls(0)
    private var totalChunksExpected: Int = -1
    private var chunksReceived: Int = 0
    private var receiverBytesReceived: Long = 0L
    // Same cancelled-Job sentinel pattern as senderInactivityJob.
    private var receiverInactivityJob: Job = Job().also { it.cancel() }

    // ── Factory ───────────────────────────────────────────────────────────
    companion object {
        fun sender(
            messageId: ByteArray,
            peerId: ByteArray,
            payload: ByteArray,
            priority: Priority,
            hopCount: Int,
            config: TransferConfig,
            chunkSizePolicy: ChunkSizePolicy,
            isGattMode: Boolean,
            scope: CoroutineScope,
            outboundFlow: MutableSharedFlow<OutboundFrame>,
            eventFlow: MutableSharedFlow<TransferEvent>,
        ): TransferSession =
            TransferSession(
                messageId,
                peerId,
                priority,
                config,
                chunkSizePolicy,
                isGattMode,
                hopCount,
                scope,
                outboundFlow,
                eventFlow,
                payload,
            )

        fun receiver(
            messageId: ByteArray,
            peerId: ByteArray,
            priority: Priority,
            hopCount: Int,
            config: TransferConfig,
            chunkSizePolicy: ChunkSizePolicy,
            isGattMode: Boolean,
            scope: CoroutineScope,
            outboundFlow: MutableSharedFlow<OutboundFrame>,
            eventFlow: MutableSharedFlow<TransferEvent>,
        ): TransferSession =
            TransferSession(
                messageId,
                peerId,
                priority,
                config,
                chunkSizePolicy,
                isGattMode,
                hopCount,
                scope,
                outboundFlow,
                eventFlow,
                null,
            )

        private fun splitIntoChunks(payload: ByteArray, chunkSize: Int): List<ByteArray> {
            if (payload.isEmpty()) return listOf(ByteArray(0))
            val count = (payload.size + chunkSize - 1) / chunkSize
            return (0 until count).map { i ->
                payload.copyOfRange(i * chunkSize, minOf((i + 1) * chunkSize, payload.size))
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    fun start() {
        if (isSender) scope.launch { senderLoop() } else resetReceiverInactivityTimer()
    }

    fun cancel() {
        senderInactivityJob.cancel()
        receiverInactivityJob.cancel()
        senderEvents.close()
    }

    // ── Sender public API ─────────────────────────────────────────────────

    fun onChunkAck(ack: ChunkAck) {
        senderEvents.trySend(SenderEvent.Ack(ack))
    }

    fun onNack(nack: ch.trancee.meshlink.wire.Nack) {
        senderEvents.trySend(SenderEvent.Nack(nack))
    }

    fun onResumeRequest(req: ResumeRequest) {
        senderEvents.trySend(SenderEvent.Resume(req))
    }

    // ── Receiver public API ───────────────────────────────────────────────

    fun onChunk(chunk: Chunk) {
        scope.launch { processChunk(chunk) }
    }

    // ── Shared disconnect / reconnect ─────────────────────────────────────

    fun onDisconnect() {
        if (isSender) {
            senderEvents.trySend(SenderEvent.Disconnect)
        } else {
            receiverInactivityJob.cancel()
        }
    }

    fun onReconnect(newPeerId: ByteArray = peerId) {
        peerId = newPeerId
        if (isSender) {
            senderEvents.trySend(SenderEvent.Reconnect(newPeerId))
        } else {
            handleReceiverReconnect(newPeerId)
        }
    }

    // ── Sender internals ──────────────────────────────────────────────────

    private fun resetSenderInactivityTimer() {
        senderInactivityJob.cancel()
        senderInactivityJob = scope.launch {
            delay(inactivityTimeoutMillis)
            senderEvents.trySend(SenderEvent.InactivityTimeout)
        }
    }

    private suspend fun senderLoop() {
        try {
            resetSenderInactivityTimer()
            sendBatch()
            // L2CAP: no ACKs expected — done as soon as all chunks are emitted.
            if (!isGattMode) return

            while (true) {
                val event =
                    try {
                        senderEvents.receive()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        return
                    }
                when (event) {
                    is SenderEvent.Ack -> {
                        if (!senderDisconnected) {
                            resetSenderInactivityTimer()
                            ackTimeoutCount = 0
                            inDegradationMode = false
                            probeFailureCount = 0
                            updateSenderSackFromAck(event.ack)
                            rateController.onAck()
                            nackRetryCount = 0
                            // Emit sender-side progress so DeliveryPipeline can track own sessions.
                            val (ackSeq, _) = senderSack.buildAck()
                            val ackedCount =
                                if (ackSeq == UShort.MAX_VALUE) 0 else ackSeq.toInt() + 1
                            eventFlow.emit(
                                TransferEvent.ChunkProgress(messageId, ackedCount, chunks.size)
                            )
                            if (allChunksAcked()) return
                            sendBatch()
                        }
                    }
                    is SenderEvent.Nack -> {
                        if (!senderDisconnected && event.nack.reason == NACK_REASON_BUFFER_FULL) {
                            resetSenderInactivityTimer()
                            nackRetryCount++
                            if (nackRetryCount > config.maxNackRetries) {
                                eventFlow.emit(
                                    TransferEvent.TransferFailed(
                                        messageId,
                                        FailureReason.BUFFER_FULL_RETRY_EXHAUSTED,
                                    )
                                )
                                return
                            }
                            val backoff =
                                config.nackBaseBackoffMillis shl
                                    (nackRetryCount - 1).coerceAtMost(7)
                            delay(backoff)
                            sendBatch()
                        }
                    }
                    is SenderEvent.InactivityTimeout -> {
                        rateController.onTimeout()
                        if (inDegradationMode) {
                            probeFailureCount++
                            if (probeFailureCount >= MAX_PROBE_FAILURES) {
                                eventFlow.emit(
                                    TransferEvent.TransferFailed(
                                        messageId,
                                        FailureReason.DEGRADATION_PROBE_FAILED,
                                    )
                                )
                                return
                            }
                            delay(DEGRADATION_PAUSE_MILLIS)
                            sendProbe()
                            resetSenderInactivityTimer()
                        } else {
                            ackTimeoutCount++
                            if (ackTimeoutCount >= ACK_TIMEOUTS_TO_DEGRADE) {
                                ackTimeoutCount = 0
                                inDegradationMode = true
                                delay(DEGRADATION_PAUSE_MILLIS)
                                sendProbe()
                                resetSenderInactivityTimer()
                            } else {
                                resetSenderInactivityTimer()
                                sendBatch()
                            }
                        }
                    }
                    is SenderEvent.Disconnect -> {
                        senderInactivityJob.cancel()
                        senderDisconnected = true
                    }
                    is SenderEvent.Reconnect -> {
                        peerId = event.newPeerId
                        if (senderDisconnected) {
                            // Wait for explicit ResumeRequest from receiver before resuming.
                            resetSenderInactivityTimer()
                        }
                    }
                    is SenderEvent.Resume -> {
                        if (senderDisconnected) {
                            resumeAttempts++
                            if (resumeAttempts > config.maxResumeAttempts) {
                                eventFlow.emit(
                                    TransferEvent.TransferFailed(
                                        messageId,
                                        FailureReason.RESUME_FAILED,
                                    )
                                )
                                return
                            }
                            val alignedOffset =
                                ResumeCalculator.alignedOffset(
                                    event.req.bytesReceived.toLong(),
                                    chunkSizePolicy.size,
                                )
                            nextIndexToSend = (alignedOffset / chunkSizePolicy.size).toInt()
                            resetSenderSackToIndex(nextIndexToSend)
                            senderDisconnected = false
                            inDegradationMode = false
                            ackTimeoutCount = 0
                            nackRetryCount = 0
                            resetSenderInactivityTimer()
                            sendBatch()
                        }
                    }
                }
            }
        } finally {
            senderInactivityJob.cancel()
        }
    }

    private suspend fun sendBatch() {
        val rate = if (isGattMode) rateController.currentRate() else chunks.size
        var sent = 0
        val (ackSeq, _) = senderSack.buildAck()
        val baseIndex = if (ackSeq == UShort.MAX_VALUE) 0 else ackSeq.toInt() + 1

        // Re-send missing chunks already within the window.
        var index = baseIndex
        while (sent < rate && index < nextIndexToSend) {
            if (senderSack.isMissing(index.toUShort())) {
                emitChunk(index)
                sent++
            }
            index++
        }

        // Send new (first-time) chunks.
        while (sent < rate && nextIndexToSend < chunks.size) {
            emitChunk(nextIndexToSend)
            nextIndexToSend++
            sent++
        }
    }

    private suspend fun sendProbe() {
        val (ackSeq, _) = senderSack.buildAck()
        val probeIndex = if (ackSeq == UShort.MAX_VALUE) 0 else ackSeq.toInt() + 1
        emitChunk(probeIndex)
    }

    private suspend fun emitChunk(index: Int) {
        outboundFlow.emit(
            OutboundFrame(peerId, Chunk(messageId, index.toUShort(), totalChunks, chunks[index]))
        )
    }

    private fun allChunksAcked(): Boolean {
        val (ackSeq, _) = senderSack.buildAck()
        return ackSeq.toInt() == chunks.size - 1
    }

    private fun updateSenderSackFromAck(ack: ChunkAck) {
        val (currentAckSeq, _) = senderSack.buildAck()
        val start = (currentAckSeq.toUInt() + 1u).toUShort()
        val end = (ack.ackSequence.toUInt() + 1u).toUShort()
        var seq = start
        while (seq != end) {
            senderSack.markReceived(seq)
            seq = (seq.toUInt() + 1u).toUShort()
        }
        for (i in 0..63) {
            if ((ack.sackBitmask shr i) and 1uL != 0uL) {
                val bitmapSeq = (ack.ackSequence.toUInt() + 1u + i.toUInt()).toUShort()
                senderSack.markReceived(bitmapSeq)
            }
        }
    }

    /** Reconstructs the sender's SackTracker as if chunks 0..(index-1) are all acknowledged. */
    private fun resetSenderSackToIndex(index: Int) {
        senderSack = SackTracker()
        for (i in 0 until index) {
            senderSack.markReceived(i.toUShort())
        }
    }

    // ── Receiver internals ────────────────────────────────────────────────

    private fun resetReceiverInactivityTimer() {
        receiverInactivityJob.cancel()
        receiverInactivityJob = scope.launch {
            delay(inactivityTimeoutMillis)
            eventFlow.emit(
                TransferEvent.TransferFailed(messageId, FailureReason.INACTIVITY_TIMEOUT)
            )
        }
    }

    private suspend fun processChunk(chunk: Chunk) {
        if (totalChunksExpected < 0) {
            totalChunksExpected = chunk.totalChunks.toInt()
            receiverBuffer = arrayOfNulls(totalChunksExpected)
        }
        val sequenceIndex = chunk.seqNo.toInt()
        if (sequenceIndex >= totalChunksExpected) return

        if (receiverBuffer[sequenceIndex] == null) {
            receiverBuffer[sequenceIndex] = chunk.payload
            chunksReceived++
            receiverBytesReceived += chunk.payload.size
            receiverSack.markReceived(chunk.seqNo)
        }

        resetReceiverInactivityTimer()
        eventFlow.emit(TransferEvent.ChunkProgress(messageId, chunksReceived, totalChunksExpected))

        if (isGattMode) {
            val (ackSeq, bitmap) = receiverSack.buildAck()
            outboundFlow.emit(OutboundFrame(peerId, ChunkAck(messageId, ackSeq, bitmap)))
        }

        if (chunksReceived == totalChunksExpected) {
            receiverInactivityJob.cancel()
            eventFlow.emit(TransferEvent.AssemblyComplete(messageId, assemblePayload(), peerId))
        }
    }

    private fun handleReceiverReconnect(newPeerId: ByteArray) {
        if (totalChunksExpected > 0 && chunksReceived < totalChunksExpected) {
            scope.launch {
                outboundFlow.emit(
                    OutboundFrame(
                        newPeerId,
                        ResumeRequest(messageId, receiverBytesReceived.toUInt()),
                    )
                )
            }
            resetReceiverInactivityTimer()
        }
    }

    private fun assemblePayload(): ByteArray {
        // All slots are guaranteed non-null when this is called (all chunks received).
        @Suppress("UNCHECKED_CAST") val filled = receiverBuffer as Array<ByteArray>
        val result = ByteArray(filled.sumOf { it.size })
        var offset = 0
        for (chunk in filled) {
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }
}
