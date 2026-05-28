package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.identity.toHexString

internal data class L2capBufferWriteStats(
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
)

internal data class L2capCoalescedBufferSnapshot(
    val buffer: ByteArray,
    val batchHeadHex: String,
    val batchTailHex: String,
)

internal data class L2capWriteProgress(
    var lastWriteProgressAtMs: Long,
    var writeCalls: Int = 0,
    var writeBatches: Int = 0,
    var backpressureSpins: Int = 0,
    var readyFalseCount: Int = 0,
    var minWriteChunkBytes: Int = Int.MAX_VALUE,
    var maxWriteChunkBytes: Int = 0,
    var minWriteBatchBytes: Int = Int.MAX_VALUE,
    var maxWriteBatchBytes: Int = 0,
    var previousPositiveWriteAtMs: Long? = null,
    var maxInterWriteGapMs: Long = 0L,
)

internal fun L2capWriteProgress.recordBatch(batchBytes: Int): Unit {
    writeBatches += 1
    maxWriteBatchBytes = maxOf(maxWriteBatchBytes, batchBytes)
    minWriteBatchBytes = minOf(minWriteBatchBytes, batchBytes)
}

internal fun L2capWriteProgress.recordBackpressure(readyFalse: Boolean): Unit {
    backpressureSpins += 1
    if (readyFalse) {
        readyFalseCount += 1
    }
}

internal fun L2capWriteProgress.recordWrite(writtenBytes: Int, attemptAtMs: Long): Unit {
    maxWriteChunkBytes = maxOf(maxWriteChunkBytes, writtenBytes)
    minWriteChunkBytes = minOf(minWriteChunkBytes, writtenBytes)
    previousPositiveWriteAtMs?.let { previousAtMs ->
        maxInterWriteGapMs = maxOf(maxInterWriteGapMs, attemptAtMs - previousAtMs)
    }
    previousPositiveWriteAtMs = attemptAtMs
    lastWriteProgressAtMs = attemptAtMs
}

internal fun L2capWriteProgress.toStats(totalElapsedMs: Long): L2capBufferWriteStats {
    return L2capBufferWriteStats(
        writeCalls = writeCalls,
        writeBatches = writeBatches,
        backpressureSpins = backpressureSpins,
        readyFalseCount = readyFalseCount,
        minWriteChunkBytes = if (minWriteChunkBytes == Int.MAX_VALUE) 0 else minWriteChunkBytes,
        maxWriteChunkBytes = maxWriteChunkBytes,
        minWriteBatchBytes = if (minWriteBatchBytes == Int.MAX_VALUE) 0 else minWriteBatchBytes,
        maxWriteBatchBytes = maxWriteBatchBytes,
        maxInterWriteGapMs = maxInterWriteGapMs,
        totalElapsedMs = totalElapsedMs,
    )
}

internal fun buildCoalescedBufferSnapshot(
    encodedFrames: List<ByteArray>
): L2capCoalescedBufferSnapshot {
    val coalescedBuffer = ByteArray(encodedFrames.sumOf { frame -> frame.size })
    var copyOffset = 0
    encodedFrames.forEach { encodedFrame ->
        encodedFrame.copyInto(coalescedBuffer, destinationOffset = copyOffset)
        copyOffset += encodedFrame.size
    }
    return L2capCoalescedBufferSnapshot(
        buffer = coalescedBuffer,
        batchHeadHex =
            coalescedBuffer
                .copyOf(minOf(coalescedBuffer.size, TELEMETRY_HEX_SNIPPET_BYTES))
                .toHexString(),
        batchTailHex =
            coalescedBuffer
                .copyOfRange(
                    maxOf(0, coalescedBuffer.size - TELEMETRY_HEX_SNIPPET_BYTES),
                    coalescedBuffer.size,
                )
                .toHexString(),
    )
}

private const val TELEMETRY_HEX_SNIPPET_BYTES: Int = 16
