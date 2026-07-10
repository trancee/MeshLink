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

    // Emitted when an inbound GATT connection claims a peer id (see
    // ch.trancee.meshlink.platform.android.BleTransportAdapter.registerProvisionalGattPeer) that
    // this transport had no prior DiscoveredPeer entry for -- i.e. a device that only ever accepts
    // inbound connections and never independently scan-discovers its peer. Unlike PeerDiscovered,
    // this intentionally does NOT flow through PeerPresenceTracker/announce a connected-peer UI
    // event (the claim is unauthenticated at this point -- see registerProvisionalGattPeer's own
    // doc comment), but the engine still needs a trigger to proactively initiate a handshake
    // towards this peer id if shouldInitiateHandshakeTowards says this device should -- otherwise,
    // when the *other* side also never independently scan-discovers this device (a real,
    // reproducible asymmetric-BLE-discovery case, not just a hypothetical), neither side ever
    // calls prewarmHopSession() and the pairing deadlocks with no handshake ever attempted.
    internal class InboundPeerClaimed internal constructor(internal val peerId: PeerId) :
        TransportEvent()

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

    internal class ScanFailed
    internal constructor(
        internal val errorCode: Int,
        internal val errorName: String,
        internal val willRetry: Boolean,
        internal val attempt: Int,
    ) : TransportEvent()

    // Emitted when the scan watchdog has exhausted its own recovery options (plain scan restart,
    // then an adapter power-cycle attempt where the platform permits it) and a device-specific
    // BLE scan wedge still hasn't cleared. The host app is expected to surface this as an
    // actionable prompt asking the user to manually toggle Bluetooth off/on, since no further
    // in-app recovery is possible without elevated (system-app) permissions.
    internal data object ManualBluetoothRecoveryNeeded : TransportEvent()
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
