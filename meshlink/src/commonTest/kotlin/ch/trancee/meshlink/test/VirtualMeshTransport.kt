package ch.trancee.meshlink.test

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal class VirtualMeshTransport(
    internal val localPeerId: PeerId,
    private val network: VirtualMeshNetwork,
) : BleTransport {
    private val mutableEvents: MutableSharedFlow<TransportEvent> = MutableSharedFlow(extraBufferCapacity = 32)
    private val sentFrames: MutableList<ByteArray> = mutableListOf()
    private var started: Boolean = false

    override val events: Flow<TransportEvent> = mutableEvents.asSharedFlow()

    override suspend fun start(): Unit {
        started = true
        network.register(this)
    }

    override suspend fun pause(): Unit {
        started = false
    }

    override suspend fun resume(): Unit {
        started = true
        network.register(this)
    }

    override suspend fun stop(): Unit {
        started = false
        sentFrames.clear()
        network.unregister(localPeerId)
    }

    override suspend fun send(frame: OutboundFrame): TransportSendResult {
        if (!started) {
            return TransportSendResult.Dropped("virtual transport is not started")
        }
        sentFrames += frame.payload.copyOf()
        val delivered = network.deliver(
            senderPeerId = localPeerId,
            recipientPeerId = frame.peerId,
            payload = frame.payload,
        )
        return if (delivered) {
            TransportSendResult.Delivered
        } else {
            TransportSendResult.Dropped("recipient is unavailable")
        }
    }

    internal fun connect(peerId: PeerId, mode: TransportMode = TransportMode.GATT): Unit {
        mutableEvents.tryEmit(TransportEvent.PeerDiscovered(peerId = peerId, transportMode = mode))
    }

    internal fun disconnect(peerId: PeerId): Unit {
        mutableEvents.tryEmit(TransportEvent.PeerLost(peerId = peerId))
    }

    internal fun receive(senderPeerId: PeerId, payload: ByteArray): Unit {
        mutableEvents.tryEmit(TransportEvent.FrameReceived(peerId = senderPeerId, payload = payload))
    }

    internal fun lastSentFrame(): ByteArray? {
        return sentFrames.lastOrNull()?.copyOf()
    }
}
