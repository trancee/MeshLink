package ch.trancee.meshlink.platform.ios

internal class IosGattNotifyPumpState {
    private val pendingFrames: ArrayDeque<IosGattNotifyPendingFrame> = ArrayDeque()
    private var closed: Boolean = false
    private var pumpInProgress: Boolean = false
    private var retryPumpScheduled: Boolean = false

    internal fun isClosed(): Boolean {
        return closed
    }

    internal fun enqueue(frame: IosGattNotifyPendingFrame): Boolean {
        if (closed) {
            return false
        }
        pendingFrames.addLast(frame)
        return true
    }

    internal fun beginPump(): Boolean {
        if (closed || pumpInProgress) {
            return false
        }
        pumpInProgress = true
        retryPumpScheduled = false
        return true
    }

    internal fun finishPump(): Unit {
        pumpInProgress = false
    }

    internal fun nextPendingChunkOrNull(): ByteArray? {
        if (closed) {
            return null
        }
        return pendingFrames.firstOrNull()?.nextChunkOrNull()
    }

    internal fun recordPumpAttempt(didSend: Boolean): IosGattNotifyPumpOutcome {
        if (closed) {
            return IosGattNotifyPumpOutcome(
                completedFrame = null,
                pendingChunkCount = 0,
                shouldScheduleRetryPump = false,
            )
        }

        val headFrame = pendingFrames.firstOrNull()
        val completedFrame =
            if (didSend && headFrame != null && headFrame.markCurrentChunkSent()) {
                pendingFrames.removeFirst()
                headFrame
            } else {
                null
            }
        val pendingChunkCount = pendingFrames.sumOf { frame -> frame.remainingChunkCount() }
        val shouldScheduleRetryPump = !didSend && pendingChunkCount > 0 && !retryPumpScheduled
        if (shouldScheduleRetryPump) {
            retryPumpScheduled = true
        }

        return IosGattNotifyPumpOutcome(
            completedFrame = completedFrame,
            pendingChunkCount = pendingChunkCount,
            shouldScheduleRetryPump = shouldScheduleRetryPump,
        )
    }

    internal fun reject(frame: IosGattNotifyPendingFrame): Unit {
        pendingFrames.remove(frame)
    }

    internal fun discardQueuedFrames(): List<IosGattNotifyPendingFrame> {
        if (pendingFrames.isEmpty()) {
            return emptyList()
        }

        val discardedFrames = mutableListOf<IosGattNotifyPendingFrame>()
        val preserveHead = pumpInProgress
        while (pendingFrames.size > if (preserveHead) 1 else 0) {
            discardedFrames += pendingFrames.removeLast()
        }
        return discardedFrames
    }

    internal fun close(): List<IosGattNotifyPendingFrame> {
        closed = true
        pumpInProgress = false
        retryPumpScheduled = false
        val discardedFrames = pendingFrames.toList()
        pendingFrames.clear()
        return discardedFrames
    }
}

internal class IosGattNotifyPumpOutcome
internal constructor(
    internal val completedFrame: IosGattNotifyPendingFrame?,
    internal val pendingChunkCount: Int,
    internal val shouldScheduleRetryPump: Boolean,
)
