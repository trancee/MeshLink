@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.identity.toHexString
import ch.trancee.meshlink.transport.PendingFrameWindow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.NSOutputStream

internal class L2capWriteTiming
internal constructor(internal val nowMillis: () -> Long, internal val activePollIntervalMs: Long)

internal class L2capWritePumpDependencies
internal constructor(
    internal val hintPeerIdProvider: () -> PeerId,
    internal val telemetryEnabled: Boolean,
    internal val telemetryLogger: (String) -> Unit,
    internal val promoteActiveWriteLatency: () -> Unit,
    internal val timing: L2capWriteTiming,
    internal val awaitWritable: suspend (Long) -> Boolean,
)

internal class L2capWritePump
internal constructor(
    private val outputStream: NSOutputStream,
    private val frameCodec: L2capFrameBuffer,
    private val dependencies: L2capWritePumpDependencies,
) {
    private val enqueueMutex = Mutex()
    private val pendingFrameWindow =
        PendingFrameWindow(
            maxPendingFrames = PENDING_FRAME_WINDOW_FRAMES,
            maxPendingBytes = PENDING_FRAME_WINDOW_BYTES,
        )
    private val outboundFrames = Channel<QueuedFrame>(capacity = Channel.UNLIMITED)
    private val outputWriter =
        L2capOutputWriter(
            outputStream = NsOutputStreamAdapter(outputStream),
            timing = dependencies.timing,
            awaitWritable = dependencies.awaitWritable,
        )
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

internal class L2capOutputWriter(
    private val outputStream: L2capOutputStreamAdapter,
    private val timing: L2capWriteTiming,
    private val awaitWritable: suspend (Long) -> Boolean = { timeoutMs ->
        val waitMillis = minOf(timeoutMs, timing.activePollIntervalMs.coerceAtLeast(0L))
        if (waitMillis > 0L) {
            delay(waitMillis)
        }
        true
    },
) {
    suspend fun write(coalescedBuffer: ByteArray): L2capBufferWriteStats {
        val startedAtMs = timing.nowMillis()
        val state =
            L2capOutputWriteState(
                totalBytes = coalescedBuffer.size,
                startedAtMs = startedAtMs,
                maxBatchBytes = WRITE_BATCH_BYTES,
            )
        while (true) {
            val request = state.nextRequestOrNull() ?: break
            when (val attempt = attemptWriteChunk(request, coalescedBuffer)) {
                L2capWriteAttempt.ReadyFalse -> {
                    awaitWritable(WRITE_STALL_TIMEOUT_MS)
                    check(
                        state.recordReadyFalse(
                            nowMs = timing.nowMillis(),
                            stallTimeoutMs = WRITE_STALL_TIMEOUT_MS,
                        )
                    ) {
                        "iOS L2CAP output stream stalled"
                    }
                }

                L2capWriteAttempt.ZeroWrite -> {
                    state.recordWriteCall()
                    awaitWritable(WRITE_STALL_TIMEOUT_MS)
                    check(
                        state.recordZeroWrite(
                            nowMs = timing.nowMillis(),
                            stallTimeoutMs = WRITE_STALL_TIMEOUT_MS,
                        )
                    ) {
                        "iOS L2CAP output stream stalled"
                    }
                }

                is L2capWriteAttempt.Progress -> {
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
        request: L2capWriteRequest,
        coalescedBuffer: ByteArray,
    ): L2capWriteAttempt {
        check(
            !isStreamClosed(
                streamStatus = outputStream.streamStatus(),
                hasError = outputStream.hasError(),
            )
        ) {
            "iOS L2CAP output stream closed"
        }
        if (!outputStream.hasSpaceAvailable()) {
            return L2capWriteAttempt.ReadyFalse
        }
        val attemptAtMs = timing.nowMillis()
        val written = outputStream.write(coalescedBuffer, request.offset, request.requestedBytes)
        check(written >= 0) { "iOS L2CAP output stream closed" }
        return if (written == 0L) {
            L2capWriteAttempt.ZeroWrite
        } else {
            L2capWriteAttempt.Progress(written.toInt(), attemptAtMs)
        }
    }
}

private data class QueuedFrame(
    val sequence: Long,
    val payloadSize: Int,
    val encoded: ByteArray,
    val classification: L2capFrameTelemetry,
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

private sealed interface L2capWriteAttempt {
    data class Progress(val writtenBytes: Int, val attemptAtMs: Long) : L2capWriteAttempt

    data object ReadyFalse : L2capWriteAttempt

    data object ZeroWrite : L2capWriteAttempt
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
private const val WRITE_STALL_TIMEOUT_MS: Long = 10_000L
private const val FRAME_PREFIX_BYTES: Int = 4
