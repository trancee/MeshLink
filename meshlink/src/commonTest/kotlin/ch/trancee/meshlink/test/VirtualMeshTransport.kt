package ch.trancee.meshlink.test

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

private data class VirtualMeshTransportState(
    val eventChannel: Channel<TransportEvent> = Channel(capacity = Channel.UNLIMITED),
    val sentFrames: List<ByteArray> = emptyList(),
    val clearedQueuedOutboundPeers: List<String> = emptyList(),
    val discoverySuspendedTransitions: List<Boolean> = emptyList(),
    val discoveredPeerModes: Map<String, TransportMode> = emptyMap(),
    val started: Boolean = false,
)

internal class VirtualMeshTransport(
    internal val localPeerId: PeerId,
    private val network: VirtualMeshNetwork,
) : BleTransport {
    private val mutableState: MutableStateFlow<VirtualMeshTransportState> =
        MutableStateFlow(VirtualMeshTransportState())

    override val events: Flow<TransportEvent>
        get() = mutableState.value.eventChannel.receiveAsFlow()

    override suspend fun start(): Unit {
        mutableState.update { state -> state.copy(started = true) }
        network.register(this)
    }

    override suspend fun pause(): Unit {
        mutableState.update { state -> state.copy(started = false) }
    }

    override suspend fun resume(): Unit {
        mutableState.update { state -> state.copy(started = true) }
        network.register(this)
    }

    override suspend fun stop(): Unit {
        mutableState.update { state ->
            state.copy(
                eventChannel = Channel(capacity = Channel.UNLIMITED),
                sentFrames = emptyList(),
                clearedQueuedOutboundPeers = emptyList(),
                discoverySuspendedTransitions = emptyList(),
                discoveredPeerModes = emptyMap(),
                started = false,
            )
        }
        network.unregister(localPeerId)
    }

    override fun maximumPayloadBytesPerDelivery(peerId: PeerId): Int? {
        return network.maximumPayloadBytesPerDelivery()
    }

    override suspend fun setDiscoverySuspended(suspended: Boolean): Unit {
        mutableState.update { state ->
            state.copy(
                discoverySuspendedTransitions = state.discoverySuspendedTransitions + suspended
            )
        }
    }

    override suspend fun clearQueuedOutboundFrames(peerId: PeerId): Unit {
        mutableState.update { state ->
            state.copy(clearedQueuedOutboundPeers = state.clearedQueuedOutboundPeers + peerId.value)
        }
    }

    override suspend fun send(frame: OutboundFrame): TransportSendResult {
        val snapshot = mutableState.value
        if (!snapshot.started) {
            return TransportSendResult.Dropped("virtual transport is not started")
        }
        val mode = snapshot.discoveredPeerModes[frame.peerId.value]
        if (mode != TransportMode.L2CAP) {
            return TransportSendResult.Dropped("recipient transport is unavailable")
        }
        mutableState.update { state ->
            state.copy(sentFrames = state.sentFrames + frame.payload.copyOf())
        }
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

    internal fun connect(peerId: PeerId, mode: TransportMode = TransportMode.L2CAP): Unit {
        var shouldDispatch = false
        mutableState.update { state ->
            if (state.discoveredPeerModes[peerId.value] == mode) {
                return@update state
            }
            shouldDispatch = true
            state.copy(discoveredPeerModes = state.discoveredPeerModes + (peerId.value to mode))
        }
        if (shouldDispatch) {
            dispatchEvent(TransportEvent.PeerDiscovered(peerId = peerId, transportMode = mode))
        }
    }

    internal fun disconnect(peerId: PeerId): Unit {
        var shouldDispatch = false
        mutableState.update { state ->
            if (!state.discoveredPeerModes.containsKey(peerId.value)) {
                return@update state
            }
            shouldDispatch = true
            state.copy(discoveredPeerModes = state.discoveredPeerModes - peerId.value)
        }
        if (shouldDispatch) {
            dispatchEvent(TransportEvent.PeerLost(peerId = peerId))
        }
    }

    internal fun receive(senderPeerId: PeerId, payload: ByteArray): Unit {
        if (!mutableState.value.started) {
            return
        }
        dispatchEvent(TransportEvent.FrameReceived(peerId = senderPeerId, payload = payload))
    }

    private fun dispatchEvent(event: TransportEvent): Unit {
        val snapshot = mutableState.value
        if (!snapshot.started) {
            return
        }
        check(snapshot.eventChannel.trySend(event).isSuccess) {
            "virtual transport event buffer overflowed for ${localPeerId.value}"
        }
    }

    internal fun lastSentFrame(): ByteArray? {
        return mutableState.value.sentFrames.lastOrNull()?.copyOf()
    }

    internal fun sentFrames(): List<ByteArray> {
        return mutableState.value.sentFrames.map { frame -> frame.copyOf() }
    }

    internal fun clearedQueuedOutboundPeers(): List<PeerId> {
        return mutableState.value.clearedQueuedOutboundPeers.map(::PeerId)
    }

    internal fun discoverySuspendedTransitions(): List<Boolean> {
        return mutableState.value.discoverySuspendedTransitions.toList()
    }
}
