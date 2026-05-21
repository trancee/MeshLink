package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBMutableCharacteristic
import platform.CoreBluetooth.CBPeripheralManager
import platform.Foundation.NSData
import platform.Foundation.NSLock
import platform.Foundation.NSMutableData
import platform.Foundation.create
import platform.posix.memcpy

internal class IosGattNotifyLink(
    internal val hintPeerId: PeerId,
    internal val centralIdentifier: String,
    private val central: CBCentral,
    private val peripheralManagerProvider: () -> CBPeripheralManager?,
    private val notifyCharacteristicProvider: () -> CBMutableCharacteristic?,
    private val logger: (String) -> Unit,
    private val schedulePumpRetry: () -> Unit,
) {
    private val outgoingFrames = IosL2capFrameBuffer()
    private val incomingFrames = IosL2capFrameBuffer()
    private val pendingFrames: ArrayDeque<PendingFrame> = ArrayDeque()
    private val stateLock = NSLock()
    private var lowLatencyRequested: Boolean = false
    private var closed: Boolean = false
    private var pumpInProgress: Boolean = false
    private var retryPumpScheduled: Boolean = false

    internal suspend fun enqueue(payload: ByteArray): Boolean {
        val chunkBytes = maxNotificationChunkBytes()
        val pendingFrame =
            PendingFrame(
                chunks =
                    outgoingFrames.encode(payload).asList().chunked(chunkBytes).map { chunk ->
                        chunk.toByteArray()
                    }
            )
        val accepted = stateLock.withLock {
            if (closed) {
                false
            } else {
                pendingFrames.addLast(pendingFrame)
                true
            }
        }
        if (!accepted) {
            return false
        }
        requestLowLatencyIfNeeded()
        if (!pumpOnMain()) {
            stateLock.withLock { pendingFrames.remove(pendingFrame) }
            pendingFrame.completeIfPending(false)
            return false
        }
        return pendingFrame.awaitCompletion()
    }

    internal fun appendIncomingWrite(chunk: ByteArray): List<ByteArray> {
        return stateLock.withLock {
            if (closed) {
                emptyList()
            } else {
                incomingFrames.append(chunk)
            }
        }
    }

    internal suspend fun pumpOnMain(): Boolean {
        return withContext(Dispatchers.Main) { pump() }
    }

    internal fun pump(): Boolean {
        val peripheralManager = peripheralManagerProvider() ?: return false
        val notifyCharacteristic = notifyCharacteristicProvider() ?: return false
        val shouldStartPump = stateLock.withLock {
            if (closed || pumpInProgress) {
                false
            } else {
                pumpInProgress = true
                retryPumpScheduled = false
                true
            }
        }
        if (!shouldStartPump) {
            return true
        }
        try {
            while (true) {
                val nextChunk =
                    stateLock.withLock {
                        when {
                            closed -> null
                            else -> pendingFrames.firstOrNull()?.nextChunkOrNull()
                        }
                    } ?: return true
                val didSend =
                    peripheralManager.updateValue(
                        nextChunk.toNSData(),
                        forCharacteristic = notifyCharacteristic,
                        onSubscribedCentrals = listOf(central),
                    )
                var completedFrame: PendingFrame? = null
                var pendingChunkCount: Int = 0
                var shouldScheduleRetryPump: Boolean = false
                stateLock.withLock {
                    if (closed) {
                        completedFrame = null
                        pendingChunkCount = 0
                    } else {
                        val headFrame = pendingFrames.firstOrNull()
                        completedFrame =
                            if (didSend && headFrame != null) {
                                val finished = headFrame.markCurrentChunkSent()
                                if (finished) {
                                    pendingFrames.removeFirst()
                                    headFrame
                                } else {
                                    null
                                }
                            } else {
                                null
                            }
                        pendingChunkCount = pendingChunkCountLocked()
                        if (!didSend && pendingChunkCount > 0 && !retryPumpScheduled) {
                            retryPumpScheduled = true
                            shouldScheduleRetryPump = true
                        }
                    }
                }
                logger(
                    "GATT notify pump ${hintPeerId.value.takeLast(6)} chunkBytes=${nextChunk.size} didSend=$didSend pending=$pendingChunkCount"
                )
                completedFrame?.completeIfPending(true)
                if (shouldScheduleRetryPump) {
                    logger(
                        "GATT notify pump ${hintPeerId.value.takeLast(6)} scheduling retry pending=$pendingChunkCount"
                    )
                    schedulePumpRetry()
                }
                if (!didSend) {
                    return true
                }
            }
        } finally {
            stateLock.withLock { pumpInProgress = false }
        }
    }

    internal fun discardQueuedFrames(): Int {
        val discardedFrames = mutableListOf<PendingFrame>()
        stateLock.withLock {
            if (pendingFrames.isEmpty()) {
                return@withLock
            }
            val preserveHead = pumpInProgress
            while (pendingFrames.size > if (preserveHead) 1 else 0) {
                discardedFrames += pendingFrames.removeLast()
            }
        }
        discardedFrames.forEach { frame -> frame.completeIfPending(false) }
        return discardedFrames.size
    }

    internal fun close(): Unit {
        val discardedFrames = mutableListOf<PendingFrame>()
        stateLock.withLock {
            closed = true
            while (pendingFrames.isNotEmpty()) {
                discardedFrames += pendingFrames.removeFirst()
            }
        }
        discardedFrames.forEach { frame -> frame.completeIfPending(false) }
    }

    private fun requestLowLatencyIfNeeded(): Unit {
        if (lowLatencyRequested) {
            return
        }
        peripheralManagerProvider()
            ?.setDesiredConnectionLatency(
                latency = platform.CoreBluetooth.CBPeripheralManagerConnectionLatencyLow,
                forCentral = central,
            )
        lowLatencyRequested = true
        logger(
            "requested low connection latency for ${hintPeerId.value.takeLast(6)} central=$centralIdentifier via GATT notify"
        )
    }

    private fun maxNotificationChunkBytes(): Int {
        val rawLength = central.maximumUpdateValueLength.toInt()
        return minOf(rawLength, PREFERRED_NOTIFICATION_FRAME_BYTES).coerceAtLeast(1)
    }

    private fun pendingChunkCountLocked(): Int {
        return pendingFrames.sumOf { frame -> frame.remainingChunkCount() }
    }

    private class PendingFrame internal constructor(chunks: List<ByteArray>) {
        private val completion: CompletableDeferred<Boolean> = CompletableDeferred()
        private val chunks: List<ByteArray> = chunks.map { chunk -> chunk.copyOf() }
        private var nextChunkIndex: Int = 0

        internal fun nextChunkOrNull(): ByteArray? {
            return chunks.getOrNull(nextChunkIndex)
        }

        internal fun markCurrentChunkSent(): Boolean {
            if (nextChunkIndex < chunks.size) {
                nextChunkIndex += 1
            }
            return nextChunkIndex >= chunks.size
        }

        internal fun remainingChunkCount(): Int {
            return (chunks.size - nextChunkIndex).coerceAtLeast(0)
        }

        internal suspend fun awaitCompletion(): Boolean {
            return completion.await()
        }

        internal fun completeIfPending(result: Boolean): Unit {
            if (!completion.isCompleted) {
                completion.complete(result)
            }
        }
    }

    internal companion object {
        internal fun maximumPayloadBytesPerDelivery(): Int {
            return MAX_FRAME_PAYLOAD_BYTES
        }

        private const val PREFERRED_NOTIFICATION_FRAME_BYTES: Int = 495
        private const val MAX_FRAME_PAYLOAD_BYTES: Int = 128 * 1024
    }
}

private inline fun <T> NSLock.withLock(block: () -> T): T {
    lock()
    return try {
        block()
    } finally {
        unlock()
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) {
        return NSData()
    }
    val data = NSMutableData.create(length = size.toULong()) ?: return NSData()
    usePinned { pinned -> memcpy(data.mutableBytes, pinned.addressOf(0), size.toULong()) }
    return data
}
