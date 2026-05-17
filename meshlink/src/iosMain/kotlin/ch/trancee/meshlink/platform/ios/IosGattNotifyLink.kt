package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.IosBleTransportBridgeRegistry
import ch.trancee.meshlink.api.PeerId
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBPeripheralManager
import platform.Foundation.NSLock

internal class IosGattNotifyLink(
    internal val hintPeerId: PeerId,
    internal val centralIdentifier: String,
    private val central: CBCentral,
    private val peripheralManagerProvider: () -> CBPeripheralManager?,
    private val notifyCharacteristicProvider: () -> Any?,
    private val logger: (String) -> Unit,
) {
    private val outgoingFrames = IosL2capFrameBuffer()
    private val incomingFrames = IosL2capFrameBuffer()
    private val pendingChunks: ArrayDeque<ByteArray> = ArrayDeque()
    private val stateLock = NSLock()
    private var lowLatencyRequested: Boolean = false
    private var closed: Boolean = false
    private var pumpInProgress: Boolean = false

    internal fun enqueue(payload: ByteArray): Boolean {
        val encoded = outgoingFrames.encode(payload)
        val chunkBytes = maxNotificationChunkBytes()
        val accepted =
            stateLock.withLock {
                if (closed) {
                    false
                } else {
                    encoded.asList().chunked(chunkBytes).forEach { chunk ->
                        pendingChunks.addLast(chunk.toByteArray())
                    }
                    true
                }
            }
        if (!accepted) {
            return false
        }
        requestLowLatencyIfNeeded()
        pump()
        return true
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

    internal fun pump(): Unit {
        val callbacks = IosBleTransportBridgeRegistry.currentCallbacksOrNull() ?: return
        val peripheralManager = peripheralManagerProvider() ?: return
        val notifyCharacteristic = notifyCharacteristicProvider() ?: return
        val shouldStartPump =
            stateLock.withLock {
                if (closed || pumpInProgress) {
                    false
                } else {
                    pumpInProgress = true
                    true
                }
            }
        if (!shouldStartPump) {
            return
        }
        try {
            while (true) {
                val nextChunk =
                    stateLock.withLock {
                        when {
                            closed || pendingChunks.isEmpty() -> null
                            else -> pendingChunks.first()
                        }
                    } ?: return
                val didSend =
                    callbacks.gattNotifySend(
                        peripheralManager,
                        notifyCharacteristic,
                        central,
                        nextChunk,
                    )
                val pendingCount =
                    stateLock.withLock {
                        if (closed) {
                            0
                        } else {
                            if (didSend && pendingChunks.isNotEmpty() && pendingChunks.first() === nextChunk) {
                                pendingChunks.removeFirst()
                            }
                            pendingChunks.size
                        }
                    }
                logger(
                    "GATT notify pump ${hintPeerId.value.takeLast(6)} chunkBytes=${nextChunk.size} didSend=$didSend pending=$pendingCount"
                )
                if (!didSend) {
                    return
                }
            }
        } finally {
            stateLock.withLock {
                pumpInProgress = false
            }
        }
    }

    internal fun discardQueuedFrames(): Int {
        return stateLock.withLock {
            val discardedChunks = pendingChunks.size
            pendingChunks.clear()
            discardedChunks
        }
    }

    internal fun close(): Unit {
        stateLock.withLock {
            closed = true
            pendingChunks.clear()
        }
    }

    private fun requestLowLatencyIfNeeded(): Unit {
        if (lowLatencyRequested) {
            return
        }
        peripheralManagerProvider()?.setDesiredConnectionLatency(
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

    private companion object {
        private const val PREFERRED_NOTIFICATION_FRAME_BYTES: Int = 495
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
