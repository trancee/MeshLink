package ch.trancee.meshlink.transport

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.power.PowerPolicy
import kotlinx.coroutines.flow.Flow

internal enum class TransportMode {
    L2CAP,
    GATT,
}

internal class OutboundFrame
internal constructor(
    internal val peerId: PeerId,
    payload: ByteArray,
    internal val preferredMode: TransportMode? = null,
) {
    internal val payload: ByteArray = payload.copyOf()
}

internal sealed class TransportSendResult {
    internal data object Delivered : TransportSendResult()

    internal class Dropped internal constructor(internal val reason: String) : TransportSendResult()
}

internal sealed class TransportEvent {
    internal class PeerDiscovered
    internal constructor(internal val peerId: PeerId, internal val transportMode: TransportMode) :
        TransportEvent()

    internal class PeerLost internal constructor(internal val peerId: PeerId) : TransportEvent()

    internal class FrameReceived
    internal constructor(internal val peerId: PeerId, payload: ByteArray) : TransportEvent() {
        internal val payload: ByteArray = payload.copyOf()
    }

    internal class TransportModeChanged
    internal constructor(internal val peerId: PeerId, internal val transportMode: TransportMode) :
        TransportEvent()

    internal class AdvertiseFailed
    internal constructor(
        internal val errorCode: Int,
        internal val errorName: String,
        internal val willRetry: Boolean,
        internal val attempt: Int,
    ) : TransportEvent()
}

internal interface BleTransport {
    val events: Flow<TransportEvent>

    suspend fun start(): Unit

    suspend fun pause(): Unit

    suspend fun resume(): Unit

    suspend fun stop(): Unit

    suspend fun updatePowerPolicy(policy: PowerPolicy): Unit = Unit

    suspend fun setDiscoverySuspended(suspended: Boolean): Unit = Unit

    suspend fun clearQueuedOutboundFrames(peerId: PeerId): Unit = Unit

    suspend fun promoteTemporaryPeer(temporaryPeerId: PeerId, canonicalPeerId: PeerId): Unit = Unit

    fun maximumPayloadBytesPerDelivery(peerId: PeerId): Int? = null

    suspend fun send(frame: OutboundFrame): TransportSendResult
}
