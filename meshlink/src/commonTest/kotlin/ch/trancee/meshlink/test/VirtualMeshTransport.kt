package ch.trancee.meshlink.test

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

internal class VirtualMeshTransport(
    internal val localPeerId: PeerId,
    private val network: VirtualMeshNetwork,
) : BleTransport {
    private var eventChannel: Channel<TransportEvent> = Channel(capacity = Channel.UNLIMITED)
    private val sentFrames: MutableList<ByteArray> = mutableListOf()
    private var started: Boolean = false

    override val events: Flow<TransportEvent>
        get() = eventChannel.receiveAsFlow()

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
        eventChannel = Channel(capacity = Channel.UNLIMITED)
    }

    override suspend fun send(frame: OutboundFrame): TransportSendResult {
        if (!started) {
            return TransportSendResult.Dropped("virtual transport is not started")
        }
        sentFrames += frame.payload.copyOf()
        return when (
            network.deliver(
                senderPeerId = localPeerId,
                recipientPeerId = frame.peerId,
                payload = frame.payload,
            )
        ) {
            DeliveryOutcome.Delivered,
            DeliveryOutcome.AcceptedButDropped -> TransportSendResult.Delivered

            DeliveryOutcome.RecipientUnavailable ->
                TransportSendResult.Dropped("recipient is unavailable")
        }
    }

    internal fun connect(peerId: PeerId, mode: TransportMode = TransportMode.GATT): Unit {
        dispatchEvent(TransportEvent.PeerDiscovered(peerId = peerId, transportMode = mode))
    }

    internal fun disconnect(peerId: PeerId): Unit {
        dispatchEvent(TransportEvent.PeerLost(peerId = peerId))
    }

    internal fun receive(senderPeerId: PeerId, payload: ByteArray): Unit {
        dispatchEvent(TransportEvent.FrameReceived(peerId = senderPeerId, payload = payload))
    }

    private fun dispatchEvent(event: TransportEvent): Unit {
        check(eventChannel.trySend(event).isSuccess) {
            "virtual transport event buffer overflowed for ${localPeerId.value}"
        }
    }

    internal fun lastSentFrame(): ByteArray? {
        return sentFrames.lastOrNull()?.copyOf()
    }

    internal fun sentFrames(): List<ByteArray> {
        return sentFrames.map { frame -> frame.copyOf() }
    }
}
