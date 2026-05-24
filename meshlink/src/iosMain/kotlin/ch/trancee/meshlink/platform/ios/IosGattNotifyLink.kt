package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
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

private const val PEER_LOG_SUFFIX_LENGTH: Int = 6
private const val PREFERRED_NOTIFICATION_FRAME_BYTES: Int = 495
private const val MAX_FRAME_PAYLOAD_BYTES: Int = 128 * 1024
private const val MIN_NOTIFICATION_CHUNK_BYTES: Int = 1

internal class IosGattNotifyPeer
internal constructor(
    internal val hintPeerId: PeerId,
    internal val centralIdentifier: String,
    internal val central: CBCentral,
)

internal class IosGattNotifyDependencies
internal constructor(
    internal val peripheralManagerProvider: () -> CBPeripheralManager?,
    internal val notifyCharacteristicProvider: () -> CBMutableCharacteristic?,
    internal val logger: (String) -> Unit,
    internal val schedulePumpRetry: () -> Unit,
)

@Suppress("TooManyFunctions")
internal class IosGattNotifyLink
internal constructor(peer: IosGattNotifyPeer, private val dependencies: IosGattNotifyDependencies) {
    internal val hintPeerId: PeerId = peer.hintPeerId
    internal val centralIdentifier: String = peer.centralIdentifier
    private val central: CBCentral = peer.central
    private val logLabel: String = hintPeerId.value.takeLast(PEER_LOG_SUFFIX_LENGTH)

    private val outgoingFrames = IosL2capFrameBuffer()
    private val incomingFrames = IosL2capFrameBuffer()
    private val pumpState = IosGattNotifyPumpState()
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

    internal fun appendIncomingWrite(chunk: ByteArray): List<ByteArray> {
        return stateLock.withLock {
            if (pumpState.isClosed()) {
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
        val pumpTargets = resolvePumpTargets()
        val shouldPump = pumpTargets != null && beginPump()
        return when {
            pumpTargets == null -> false
            !shouldPump -> true
            else ->
                try {
                    drainPendingChunks(pumpTargets)
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

    private fun encodePendingFrame(payload: ByteArray): IosGattNotifyPendingFrame {
        val chunkBytes = maxNotificationChunkBytes(central)
        val encodedChunks =
            outgoingFrames.encode(payload).asList().chunked(chunkBytes).map { chunk ->
                chunk.toByteArray()
            }
        return IosGattNotifyPendingFrame(chunks = encodedChunks)
    }

    private fun enqueuePendingFrame(pendingFrame: IosGattNotifyPendingFrame): Boolean {
        return stateLock.withLock { pumpState.enqueue(pendingFrame) }
    }

    private suspend fun deliverPendingFrame(pendingFrame: IosGattNotifyPendingFrame): Boolean {
        requestLowLatencyIfNeeded()
        return if (pumpOnMain()) {
            pendingFrame.awaitCompletion()
        } else {
            rejectPendingFrame(pendingFrame)
        }
    }

    private fun rejectPendingFrame(pendingFrame: IosGattNotifyPendingFrame): Boolean {
        stateLock.withLock { pumpState.reject(pendingFrame) }
        pendingFrame.completeIfPending(false)
        return false
    }

    private fun resolvePumpTargets(): PumpTargets? {
        val peripheralManager = dependencies.peripheralManagerProvider()
        val notifyCharacteristic = dependencies.notifyCharacteristicProvider()
        return if (peripheralManager != null && notifyCharacteristic != null) {
            PumpTargets(
                peripheralManager = peripheralManager,
                notifyCharacteristic = notifyCharacteristic,
            )
        } else {
            null
        }
    }

    private fun beginPump(): Boolean {
        return stateLock.withLock { pumpState.beginPump() }
    }

    private fun drainPendingChunks(targets: PumpTargets): Boolean {
        while (true) {
            val nextChunk = nextPendingChunkOrNull() ?: return true
            val didSend = sendChunk(targets, nextChunk)
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

    private fun sendChunk(targets: PumpTargets, nextChunk: ByteArray): Boolean {
        return targets.peripheralManager.updateValue(
            nextChunk.toNSData(),
            forCharacteristic = targets.notifyCharacteristic,
            onSubscribedCentrals = listOf(central),
        )
    }

    private fun recordPumpAttempt(didSend: Boolean): IosGattNotifyPumpOutcome {
        return stateLock.withLock { pumpState.recordPumpAttempt(didSend) }
    }

    private fun logPumpAttempt(chunkBytes: Int, didSend: Boolean, pendingChunkCount: Int): Unit {
        dependencies.logger(
            "GATT notify pump $logLabel chunkBytes=$chunkBytes didSend=$didSend pending=$pendingChunkCount"
        )
    }

    private fun logRetryPumpScheduled(pendingChunkCount: Int): Unit {
        dependencies.logger(
            "GATT notify pump $logLabel scheduling retry pending=$pendingChunkCount"
        )
    }

    private fun requestLowLatencyIfNeeded(): Unit {
        if (lowLatencyRequested) {
            return
        }
        dependencies
            .peripheralManagerProvider()
            ?.setDesiredConnectionLatency(
                latency = platform.CoreBluetooth.CBPeripheralManagerConnectionLatencyLow,
                forCentral = central,
            )
        lowLatencyRequested = true
        dependencies.logger(
            "requested low connection latency for $logLabel central=$centralIdentifier via GATT notify"
        )
    }

    internal companion object {
        internal fun maximumPayloadBytesPerDelivery(): Int {
            return MAX_FRAME_PAYLOAD_BYTES
        }
    }
}

private class PumpTargets
internal constructor(
    internal val peripheralManager: CBPeripheralManager,
    internal val notifyCharacteristic: CBMutableCharacteristic,
)

internal fun maximumGattNotificationChunkBytes(maximumUpdateValueLength: Int): Int {
    return minOf(maximumUpdateValueLength, PREFERRED_NOTIFICATION_FRAME_BYTES)
        .coerceAtLeast(MIN_NOTIFICATION_CHUNK_BYTES)
}

private fun maxNotificationChunkBytes(central: CBCentral): Int {
    return maximumGattNotificationChunkBytes(central.maximumUpdateValueLength.toInt())
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
    val data = if (isEmpty()) null else NSMutableData.create(length = size.toULong())
    if (data != null) {
        usePinned { pinned -> memcpy(data.mutableBytes, pinned.addressOf(0), size.toULong()) }
    }
    return data ?: NSData()
}
