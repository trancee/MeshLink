@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.engine.DirectWireFrame
import ch.trancee.meshlink.identity.toHexString
import ch.trancee.meshlink.transport.PendingFrameWindow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.NSOutputStream

internal class IosL2capWriteTiming
internal constructor(internal val nowMillis: () -> Long, internal val activePollIntervalMs: Long)

internal class IosL2capWritePumpDependencies
internal constructor(
    internal val hintPeerIdProvider: () -> PeerId,
    internal val telemetryEnabled: Boolean,
    internal val telemetryLogger: (String) -> Unit,
    internal val promoteActiveWriteLatency: () -> Unit,
    internal val timing: IosL2capWriteTiming,
)

internal class IosL2capFrameTelemetry
internal constructor(
    internal val directType: String,
    internal val dataClass: String,
    internal val innerBytes: Int,
)

internal fun classifyL2capFrame(payload: ByteArray): IosL2capFrameTelemetry {
    return runCatching { DirectWireFrame.decode(payload) }
        .getOrNull()
        ?.let { frame ->
            when (frame) {
                is DirectWireFrame.HandshakeMessage1 ->
                    IosL2capFrameTelemetry(
                        directType = "HANDSHAKE_MESSAGE_1",
                        dataClass = "handshake",
                        innerBytes = frame.payload.size,
                    )
                is DirectWireFrame.HandshakeMessage2 ->
                    IosL2capFrameTelemetry(
                        directType = "HANDSHAKE_MESSAGE_2",
                        dataClass = "handshake",
                        innerBytes = frame.payload.size,
                    )
                is DirectWireFrame.HandshakeMessage3 ->
                    IosL2capFrameTelemetry(
                        directType = "HANDSHAKE_MESSAGE_3",
                        dataClass = "handshake",
                        innerBytes = frame.payload.size,
                    )
                is DirectWireFrame.Data ->
                    IosL2capFrameTelemetry(
                        directType = "DATA",
                        dataClass =
                            if (frame.payload.size <= ACK_LIKELY_ENCRYPTED_BYTES) {
                                "ackLikely"
                            } else {
                                "bulkLikely"
                            },
                        innerBytes = frame.payload.size,
                    )
            }
        }
        ?: IosL2capFrameTelemetry(
            directType = "UNKNOWN",
            dataClass = "unknown",
            innerBytes = payload.size,
        )
}

internal fun emitL2capTelemetry(
    telemetryLogger: (String) -> Unit,
    event: String,
    fields: Map<String, String>,
): Unit {
    val body =
        fields.entries.joinToString(separator = " ") { entry -> "${entry.key}=${entry.value}" }
    telemetryLogger("MeshLinkTransportTelemetry event=$event $body")
}

internal class IosL2capWritePump
internal constructor(
    private val outputStream: NSOutputStream,
    private val frameCodec: IosL2capFrameBuffer,
    private val dependencies: IosL2capWritePumpDependencies,
) {
    private val enqueueMutex = Mutex()
    private val pendingFrameWindow =
        PendingFrameWindow(
            maxPendingFrames = PENDING_FRAME_WINDOW_FRAMES,
            maxPendingBytes = PENDING_FRAME_WINDOW_BYTES,
        )
    private val outboundFrames = Channel<QueuedFrame>(capacity = Channel.UNLIMITED)
    private val outputWriter =
        IosL2capOutputWriter(NsOutputStreamAdapter(outputStream), dependencies.timing)
    private var writeSequence: Long = 0L
    private var cumulativeEncodedBytesWritten: Long = 0L
    private var activeWriteLatencyPromoted: Boolean = false

    internal suspend fun enqueue(payload: ByteArray): Boolean {
        val classification = classifyL2capFrame(payload)
        val encoded = frameCodec.encode(payload)
        if (!pendingFrameWindow.acquire(encoded.size)) {
            return false
        }
        val queuedFrame =
            QueuedFrame(
                sequence = nextWriteSequence(),
                payloadSize = payload.size,
                encoded = encoded,
                classification = classification,
                enqueuedAtMs = dependencies.timing.nowMillis(),
            )
        val queued = outboundFrames.trySend(queuedFrame).isSuccess
        if (!queued) {
            pendingFrameWindow.release(encoded.size)
        }
        return queued
    }

    internal suspend fun runLoop(): Unit {
        try {
            while (true) {
                val queuedFrames = receiveCoalescedBatchOrNull() ?: break
                writeQueuedFrames(queuedFrames)
            }
        } finally {
            releaseRemainingQueuedFrames()
        }
    }

    internal fun close(): Unit {
        outboundFrames.close()
        pendingFrameWindow.close()
    }

    internal suspend fun discardQueuedFrames(): Int {
        var discardedFrames = 0
        while (true) {
            val queuedFrame = outboundFrames.tryReceive().getOrNull() ?: return discardedFrames
            pendingFrameWindow.release(queuedFrame.encoded.size)
            discardedFrames += 1
        }
    }

    private suspend fun nextWriteSequence(): Long {
        return enqueueMutex.withLock { ++writeSequence }
    }

    private suspend fun receiveCoalescedBatchOrNull(): List<QueuedFrame>? {
        val firstQueuedFrame = outboundFrames.receiveCatching().getOrNull() ?: return null
        val queuedFrames = mutableListOf(firstQueuedFrame)
        var coalescedBytes = firstQueuedFrame.encoded.size
        var shouldContinueCoalescing = true
        while (shouldContinueCoalescing && queuedFrames.size < MAX_COALESCED_FRAMES) {
            val nextQueuedFrame = outboundFrames.tryReceive().getOrNull()
            if (nextQueuedFrame == null) {
                shouldContinueCoalescing = false
            } else {
                queuedFrames += nextQueuedFrame
                coalescedBytes += nextQueuedFrame.encoded.size
                shouldContinueCoalescing = coalescedBytes < MAX_COALESCED_BATCH_BYTES
            }
        }
        return queuedFrames
    }

    private suspend fun writeQueuedFrames(queuedFrames: List<QueuedFrame>): Unit {
        try {
            val batchStats = writeBatch(queuedFrames)
            if (dependencies.telemetryEnabled) {
                emitBatchTelemetry(
                    queuedFrames = queuedFrames,
                    batchStats = batchStats,
                    hintPeerId = dependencies.hintPeerIdProvider(),
                    telemetryLogger = dependencies.telemetryLogger,
                )
                emitQueuedFrameTelemetry(
                    queuedFrames = queuedFrames,
                    batchStats = batchStats,
                    hintPeerId = dependencies.hintPeerIdProvider(),
                    telemetryLogger = dependencies.telemetryLogger,
                )
            }
        } finally {
            releaseBatchFrames(queuedFrames)
        }
    }

    private suspend fun writeBatch(queuedFrames: List<QueuedFrame>): BatchWriteStats {
        if (!activeWriteLatencyPromoted) {
            dependencies.promoteActiveWriteLatency()
            activeWriteLatencyPromoted = true
        }
        val startedAtMs = dependencies.timing.nowMillis()
        val coalescedBuffer =
            buildCoalescedBufferSnapshot(queuedFrames.map { queuedFrame -> queuedFrame.encoded })
        val writeStats = outputWriter.write(coalescedBuffer.buffer)
        val streamByteStart = cumulativeEncodedBytesWritten
        cumulativeEncodedBytesWritten += coalescedBuffer.buffer.size.toLong()
        return BatchWriteStats(
            writeCalls = writeStats.writeCalls,
            writeBatches = writeStats.writeBatches,
            backpressureSpins = writeStats.backpressureSpins,
            readyFalseCount = writeStats.readyFalseCount,
            minWriteChunkBytes = writeStats.minWriteChunkBytes,
            maxWriteChunkBytes = writeStats.maxWriteChunkBytes,
            minWriteBatchBytes = writeStats.minWriteBatchBytes,
            maxWriteBatchBytes = writeStats.maxWriteBatchBytes,
            maxInterWriteGapMs = writeStats.maxInterWriteGapMs,
            totalElapsedMs = dependencies.timing.nowMillis() - startedAtMs,
            coalescedFrames = queuedFrames.size,
            coalescedBytes = coalescedBuffer.buffer.size,
            batchStartedAtMs = startedAtMs,
            streamByteStart = streamByteStart,
            streamByteEndExclusive = cumulativeEncodedBytesWritten,
            batchHeadHex = coalescedBuffer.batchHeadHex,
            batchTailHex = coalescedBuffer.batchTailHex,
        )
    }

    private suspend fun releaseBatchFrames(queuedFrames: List<QueuedFrame>): Unit {
        queuedFrames.forEach { queuedFrame -> pendingFrameWindow.release(queuedFrame.encoded.size) }
    }

    private suspend fun releaseRemainingQueuedFrames(): Unit {
        pendingFrameWindow.close()
        while (true) {
            val queuedFrame = outboundFrames.tryReceive().getOrNull() ?: return
            pendingFrameWindow.release(queuedFrame.encoded.size)
        }
    }
}

internal class IosL2capOutputWriter(
    private val outputStream: IosL2capOutputStreamAdapter,
    private val timing: IosL2capWriteTiming,
) {
    suspend fun write(coalescedBuffer: ByteArray): IosL2capBufferWriteStats {
        val startedAtMs = timing.nowMillis()
        val state =
            IosL2capOutputWriteState(
                totalBytes = coalescedBuffer.size,
                startedAtMs = startedAtMs,
                maxBatchBytes = WRITE_BATCH_BYTES,
            )
        while (true) {
            val request = state.nextRequestOrNull() ?: break
            when (val attempt = attemptWriteChunk(request, coalescedBuffer)) {
                IosL2capWriteAttempt.ReadyFalse -> {
                    check(
                        state.recordReadyFalse(
                            nowMs = timing.nowMillis(),
                            stallTimeoutMs = WRITE_STALL_TIMEOUT_MS,
                        )
                    ) {
                        "iOS L2CAP output stream stalled"
                    }
                    delay(timing.activePollIntervalMs)
                }

                IosL2capWriteAttempt.ZeroWrite -> {
                    state.recordWriteCall()
                    check(
                        state.recordZeroWrite(
                            nowMs = timing.nowMillis(),
                            stallTimeoutMs = WRITE_STALL_TIMEOUT_MS,
                        )
                    ) {
                        "iOS L2CAP output stream stalled"
                    }
                    delay(timing.activePollIntervalMs)
                }

                is IosL2capWriteAttempt.Progress -> {
                    state.recordWriteCall()
                    state.recordProgress(
                        writtenBytes = attempt.writtenBytes,
                        attemptAtMs = attempt.attemptAtMs,
                    )
                }
            }
        }
        return state.toStats(totalElapsedMs = timing.nowMillis() - startedAtMs)
    }

    private fun attemptWriteChunk(
        request: IosL2capWriteRequest,
        coalescedBuffer: ByteArray,
    ): IosL2capWriteAttempt {
        check(
            !isStreamClosed(
                streamStatus = outputStream.streamStatus(),
                hasError = outputStream.hasError(),
            )
        ) {
            "iOS L2CAP output stream closed"
        }
        if (!outputStream.hasSpaceAvailable()) {
            return IosL2capWriteAttempt.ReadyFalse
        }
        val attemptAtMs = timing.nowMillis()
        val written = outputStream.write(coalescedBuffer, request.offset, request.requestedBytes)
        check(written >= 0) { "iOS L2CAP output stream closed" }
        return if (written == 0L) {
            IosL2capWriteAttempt.ZeroWrite
        } else {
            IosL2capWriteAttempt.Progress(written.toInt(), attemptAtMs)
        }
    }
}

private data class QueuedFrame(
    val sequence: Long,
    val payloadSize: Int,
    val encoded: ByteArray,
    val classification: IosL2capFrameTelemetry,
    val enqueuedAtMs: Long,
)

private data class BatchWriteStats(
    val writeCalls: Int,
    val writeBatches: Int,
    val backpressureSpins: Int,
    val readyFalseCount: Int,
    val minWriteChunkBytes: Int,
    val maxWriteChunkBytes: Int,
    val minWriteBatchBytes: Int,
    val maxWriteBatchBytes: Int,
    val maxInterWriteGapMs: Long,
    val totalElapsedMs: Long,
    val coalescedFrames: Int,
    val coalescedBytes: Int,
    val batchStartedAtMs: Long,
    val streamByteStart: Long,
    val streamByteEndExclusive: Long,
    val batchHeadHex: String,
    val batchTailHex: String,
)

private sealed interface IosL2capWriteAttempt {
    data class Progress(val writtenBytes: Int, val attemptAtMs: Long) : IosL2capWriteAttempt

    data object ReadyFalse : IosL2capWriteAttempt

    data object ZeroWrite : IosL2capWriteAttempt
}

private fun emitBatchTelemetry(
    queuedFrames: List<QueuedFrame>,
    batchStats: BatchWriteStats,
    hintPeerId: PeerId,
    telemetryLogger: (String) -> Unit,
): Unit {
    emitL2capTelemetry(
        telemetryLogger = telemetryLogger,
        event = "write.batch",
        fields =
            mapOf(
                "peer" to hintPeerId.logSuffix(),
                "seqStart" to queuedFrames.first().sequence.toString(),
                "seqEnd" to queuedFrames.last().sequence.toString(),
                "coalescedFrames" to batchStats.coalescedFrames.toString(),
                "coalescedBytes" to batchStats.coalescedBytes.toString(),
                "streamByteStart" to batchStats.streamByteStart.toString(),
                "streamByteEndExclusive" to batchStats.streamByteEndExclusive.toString(),
                "frameHeaders" to
                    queuedFrames.joinToString(separator = ",") { queuedFrame ->
                        queuedFrame.encoded
                            .copyOf(minOf(queuedFrame.encoded.size, FRAME_PREFIX_BYTES))
                            .toHexString()
                    },
                "batchHeadHex" to batchStats.batchHeadHex,
                "batchTailHex" to batchStats.batchTailHex,
                "writeCalls" to batchStats.writeCalls.toString(),
                "writeBatches" to batchStats.writeBatches.toString(),
                "backpressureSpins" to batchStats.backpressureSpins.toString(),
                "readyFalseCount" to batchStats.readyFalseCount.toString(),
            ),
    )
}

private fun emitQueuedFrameTelemetry(
    queuedFrames: List<QueuedFrame>,
    batchStats: BatchWriteStats,
    hintPeerId: PeerId,
    telemetryLogger: (String) -> Unit,
): Unit {
    queuedFrames.forEachIndexed { frameIndex, queuedFrame ->
        emitL2capTelemetry(
            telemetryLogger = telemetryLogger,
            event = "write.frame",
            fields =
                mapOf(
                    "seq" to queuedFrame.sequence.toString(),
                    "peer" to hintPeerId.logSuffix(),
                    "directType" to queuedFrame.classification.directType,
                    "dataClass" to queuedFrame.classification.dataClass,
                    "frameBytes" to queuedFrame.payloadSize.toString(),
                    "innerBytes" to queuedFrame.classification.innerBytes.toString(),
                    "encodedBytes" to queuedFrame.encoded.size.toString(),
                    "frameIndex" to (frameIndex + 1).toString(),
                    "coalescedFrames" to batchStats.coalescedFrames.toString(),
                    "coalescedBytes" to batchStats.coalescedBytes.toString(),
                    "queueDelayMs" to
                        (batchStats.batchStartedAtMs - queuedFrame.enqueuedAtMs).toString(),
                    "writeCalls" to batchStats.writeCalls.toString(),
                    "writeBatches" to batchStats.writeBatches.toString(),
                    "backpressureSpins" to batchStats.backpressureSpins.toString(),
                    "readyFalseCount" to batchStats.readyFalseCount.toString(),
                    "minWriteChunkBytes" to batchStats.minWriteChunkBytes.toString(),
                    "maxWriteChunkBytes" to batchStats.maxWriteChunkBytes.toString(),
                    "minWriteBatchBytes" to batchStats.minWriteBatchBytes.toString(),
                    "maxWriteBatchBytes" to batchStats.maxWriteBatchBytes.toString(),
                    "maxInterWriteGapMs" to batchStats.maxInterWriteGapMs.toString(),
                    "totalElapsedMs" to batchStats.totalElapsedMs.toString(),
                ),
        )
    }
}

private const val PENDING_FRAME_WINDOW_FRAMES: Int = 16
private const val PENDING_FRAME_WINDOW_BYTES: Int = 16 * 1024
private const val MAX_COALESCED_FRAMES: Int = 16
private const val MAX_COALESCED_BATCH_BYTES: Int = 16 * 1024
private const val WRITE_BATCH_BYTES: Int = 4 * 1024
private const val ACK_LIKELY_ENCRYPTED_BYTES: Int = 192
private const val WRITE_STALL_TIMEOUT_MS: Long = 10_000L
private const val FRAME_PREFIX_BYTES: Int = 4
