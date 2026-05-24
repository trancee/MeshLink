package ch.trancee.meshlink.platform.ios

internal data class IosL2capWriteRequest(val offset: Int, val requestedBytes: Int)

internal class IosL2capOutputWriteState
internal constructor(
    private val totalBytes: Int,
    startedAtMs: Long,
    private val maxBatchBytes: Int,
) {
    private val progress = IosL2capWriteProgress(lastWriteProgressAtMs = startedAtMs)
    private var nextOffset: Int = 0
    private var batchEndExclusive: Int = 0

    internal fun nextRequestOrNull(): IosL2capWriteRequest? {
        if (nextOffset >= totalBytes) {
            return null
        }
        if (nextOffset >= batchEndExclusive) {
            val batchBytes = minOf(maxBatchBytes, totalBytes - nextOffset)
            batchEndExclusive = nextOffset + batchBytes
            progress.recordBatch(batchBytes)
        }
        return IosL2capWriteRequest(
            offset = nextOffset,
            requestedBytes = batchEndExclusive - nextOffset,
        )
    }

    internal fun recordWriteCall(): Unit {
        progress.writeCalls += 1
    }

    internal fun recordReadyFalse(nowMs: Long, stallTimeoutMs: Long): Boolean {
        progress.recordBackpressure(readyFalse = true)
        return !isWriteStalled(
            lastProgressAtMs = progress.lastWriteProgressAtMs,
            nowMs = nowMs,
            stallTimeoutMs = stallTimeoutMs,
        )
    }

    internal fun recordZeroWrite(nowMs: Long, stallTimeoutMs: Long): Boolean {
        progress.recordBackpressure(readyFalse = false)
        return !isWriteStalled(
            lastProgressAtMs = progress.lastWriteProgressAtMs,
            nowMs = nowMs,
            stallTimeoutMs = stallTimeoutMs,
        )
    }

    internal fun recordProgress(writtenBytes: Int, attemptAtMs: Long): Unit {
        check(writtenBytes > 0) { "writtenBytes must be positive" }
        val remainingBatchBytes = batchEndExclusive - nextOffset
        check(remainingBatchBytes > 0) { "no pending write request exists" }
        check(writtenBytes <= remainingBatchBytes) { "writtenBytes exceeds remaining batch bytes" }
        progress.recordWrite(writtenBytes = writtenBytes, attemptAtMs = attemptAtMs)
        nextOffset += writtenBytes
    }

    internal fun toStats(totalElapsedMs: Long): IosL2capBufferWriteStats {
        return progress.toStats(totalElapsedMs = totalElapsedMs)
    }
}
