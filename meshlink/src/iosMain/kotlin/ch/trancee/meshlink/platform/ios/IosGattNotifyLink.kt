package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.IosBleTransportBridgeRegistry
import ch.trancee.meshlink.api.PeerId
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBPeripheralManager

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
    private var lowLatencyRequested: Boolean = false
    private var closed: Boolean = false

    internal fun enqueue(payload: ByteArray): Boolean {
        if (closed) {
            return false
        }
        val encoded = outgoingFrames.encode(payload)
        val chunkBytes = maxNotificationChunkBytes()
        encoded.asList().chunked(chunkBytes).forEach { chunk ->
            pendingChunks.addLast(chunk.toByteArray())
        }
        requestLowLatencyIfNeeded()
        pump()
        return true
    }

    internal fun appendIncomingWrite(chunk: ByteArray): List<ByteArray> {
        if (closed) {
            return emptyList()
        }
        return incomingFrames.append(chunk)
    }

    internal fun pump(): Unit {
        val callbacks = IosBleTransportBridgeRegistry.currentCallbacksOrNull() ?: return
        val peripheralManager = peripheralManagerProvider() ?: return
        val notifyCharacteristic = notifyCharacteristicProvider() ?: return
        while (!closed && pendingChunks.isNotEmpty()) {
            val nextChunk = pendingChunks.first()
            val didSend =
                callbacks.gattNotifySend(
                    peripheralManager,
                    notifyCharacteristic,
                    central,
                    nextChunk,
                )
            logger(
                "GATT notify pump ${hintPeerId.value.takeLast(6)} chunkBytes=${nextChunk.size} didSend=$didSend pending=${pendingChunks.size}"
            )
            if (!didSend) {
                return
            }
            pendingChunks.removeFirst()
        }
    }

    internal fun close(): Unit {
        closed = true
        pendingChunks.clear()
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
