package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import platform.Foundation.NSData
import platform.Foundation.NSLock

private const val PEER_LOG_SUFFIX_LENGTH: Int = 6
private const val PREFERRED_NOTIFICATION_FRAME_BYTES: Int = 495
private const val MAX_FRAME_PAYLOAD_BYTES: Int = 128 * 1024
private const val MIN_NOTIFICATION_CHUNK_BYTES: Int = 1

internal class GattNotifyPeer
internal constructor(
    internal val hintPeerId: PeerId,
    internal val centralIdentifier: String,
    internal val maximumUpdateValueLength: Int,
)

internal class GattNotifyDependencies
internal constructor(
    internal val incomingFrames: L2capFrameBuffer = L2capFrameBuffer(),
    internal val peripheralAdapterProvider: () -> GattNotifyPeripheralAdapter?,
    internal val runPump: suspend (() -> Boolean) -> Boolean,
    internal val logger: (() -> String) -> Unit,
    internal val schedulePumpRetry: () -> Unit,
)

@Suppress("TooManyFunctions")
internal class GattNotifyLink
internal constructor(peer: GattNotifyPeer, private val dependencies: GattNotifyDependencies) {
    internal val hintPeerId: PeerId = peer.hintPeerId
    internal val centralIdentifier: String = peer.centralIdentifier
    private val maximumUpdateValueLength: Int = peer.maximumUpdateValueLength
    private val logLabel: String = hintPeerId.value.takeLast(PEER_LOG_SUFFIX_LENGTH)

    private val outgoingFrames = L2capFrameBuffer()
    private val incomingFrames = dependencies.incomingFrames
    private val pumpState = GattNotifyPumpState()
    private val stateLock = NSLock()
    private var lowLatencyRequested: Boolean = false

    internal suspend fun enqueue(payload: ByteArray): Boolean {
        val pendingFrame = encodePendingFrame(payload)
        return if (enqueuePendingFrame(pendingFrame)) {
            deliverPendingFrame(pendingFrame)
        } else {
            false
        }
    }

    internal fun appendIncomingWrite(chunk: NSData): List<ByteArray> {
        return stateLock.withLock {
            if (pumpState.isClosed()) {
                emptyList()
            } else {
                incomingFrames.append(chunk)
            }
        }
    }

    internal suspend fun pumpOnMain(): Boolean {
        return dependencies.runPump { pump() }
    }

    internal fun pump(): Boolean {
        val pumpTarget = resolvePumpTarget()
        val shouldPump = pumpTarget != null && beginPump()
        return when {
            pumpTarget == null -> false
            !shouldPump -> true
            else ->
                try {
                    drainPendingChunks(pumpTarget)
                } finally {
                    stateLock.withLock { pumpState.finishPump() }
                }
        }
    }

    internal fun discardQueuedFrames(): Int {
        val discardedFrames = stateLock.withLock { pumpState.discardQueuedFrames() }
        discardedFrames.forEach { frame -> frame.completeIfPending(false) }
        return discardedFrames.size
    }

    internal fun close(): Unit {
        val discardedFrames = stateLock.withLock { pumpState.close() }
        discardedFrames.forEach { frame -> frame.completeIfPending(false) }
    }

    private fun encodePendingFrame(payload: ByteArray): GattNotifyPendingFrame {
        val chunkBytes = maximumGattNotificationChunkBytes(maximumUpdateValueLength)
        val encodedFrame = outgoingFrames.encode(payload)
        val encodedChunks = ArrayList<ByteArray>((encodedFrame.size + chunkBytes - 1) / chunkBytes)
        var chunkStart = 0
        while (chunkStart < encodedFrame.size) {
            val chunkEnd = minOf(chunkStart + chunkBytes, encodedFrame.size)
            encodedChunks += encodedFrame.copyOfRange(chunkStart, chunkEnd)
            chunkStart = chunkEnd
        }
        return GattNotifyPendingFrame(chunks = encodedChunks)
    }

    private fun enqueuePendingFrame(pendingFrame: GattNotifyPendingFrame): Boolean {
        return stateLock.withLock { pumpState.enqueue(pendingFrame) }
    }

    private suspend fun deliverPendingFrame(pendingFrame: GattNotifyPendingFrame): Boolean {
        requestLowLatencyIfNeeded()
        return if (pumpOnMain()) {
            pendingFrame.awaitCompletion()
        } else {
            rejectPendingFrame(pendingFrame)
        }
    }

    private fun rejectPendingFrame(pendingFrame: GattNotifyPendingFrame): Boolean {
        stateLock.withLock { pumpState.reject(pendingFrame) }
        pendingFrame.completeIfPending(false)
        return false
    }

    private fun resolvePumpTarget(): GattNotifyPeripheralAdapter? {
        return dependencies.peripheralAdapterProvider()
    }

    private fun beginPump(): Boolean {
        return stateLock.withLock { pumpState.beginPump() }
    }

    private fun drainPendingChunks(target: GattNotifyPeripheralAdapter): Boolean {
        while (true) {
            val nextChunk = nextPendingChunkOrNull() ?: return true
            val didSend = sendChunk(target, nextChunk)
            val pumpOutcome = recordPumpAttempt(didSend)
            logPumpAttempt(
                chunkBytes = nextChunk.size,
                didSend = didSend,
                pendingChunkCount = pumpOutcome.pendingChunkCount,
            )
            pumpOutcome.completedFrame?.completeIfPending(true)
            if (pumpOutcome.shouldScheduleRetryPump) {
                logRetryPumpScheduled(pumpOutcome.pendingChunkCount)
                dependencies.schedulePumpRetry()
            }
            if (!didSend) {
                return true
            }
        }
    }

    private fun nextPendingChunkOrNull(): ByteArray? {
        return stateLock.withLock { pumpState.nextPendingChunkOrNull() }
    }

    private fun sendChunk(target: GattNotifyPeripheralAdapter, nextChunk: ByteArray): Boolean {
        return target.updateValue(nextChunk)
    }

    private fun recordPumpAttempt(didSend: Boolean): GattNotifyPumpOutcome {
        return stateLock.withLock { pumpState.recordPumpAttempt(didSend) }
    }

    private fun logPumpAttempt(chunkBytes: Int, didSend: Boolean, pendingChunkCount: Int): Unit {
        dependencies.logger {
            "GATT notify pump $logLabel chunkBytes=$chunkBytes didSend=$didSend pending=$pendingChunkCount"
        }
    }

    private fun logRetryPumpScheduled(pendingChunkCount: Int): Unit {
        dependencies.logger {
            "GATT notify pump $logLabel scheduling retry pending=$pendingChunkCount"
        }
    }

    private fun requestLowLatencyIfNeeded(): Unit {
        if (lowLatencyRequested) {
            return
        }
        dependencies.peripheralAdapterProvider()?.requestLowConnectionLatency()
        lowLatencyRequested = true
        dependencies.logger {
            "requested low connection latency for $logLabel central=$centralIdentifier via GATT notify"
        }
    }

    internal companion object {
        internal fun maximumPayloadBytesPerDelivery(): Int {
            return MAX_FRAME_PAYLOAD_BYTES
        }
    }
}

internal fun maximumGattNotificationChunkBytes(maximumUpdateValueLength: Int): Int {
    return minOf(maximumUpdateValueLength, PREFERRED_NOTIFICATION_FRAME_BYTES)
        .coerceAtLeast(MIN_NOTIFICATION_CHUNK_BYTES)
}

private inline fun <T> NSLock.withLock(block: () -> T): T {
    lock()
    return try {
        block()
    } finally {
        unlock()
    }
}
