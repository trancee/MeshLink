package ch.trancee.meshlink.testing

import ch.trancee.meshlink.api.ExperimentalMeshLinkApi
import ch.trancee.meshlink.transport.AdvertisementEvent
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.IncomingData
import ch.trancee.meshlink.transport.PeerLostEvent
import ch.trancee.meshlink.transport.PeerLostReason
import ch.trancee.meshlink.transport.SendResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

// ── Public test transport ────────────────────────────────────────────────────

/**
 * A virtual BLE transport for integration tests. Two [VirtualMeshTransport] instances linked via
 * [linkTo] exchange frames as if on a real BLE link without requiring hardware.
 *
 * Obtain a linked pair using [linkedTransports]:
 * ```
 * val (transportA, transportB) = linkedTransports()
 * ```
 *
 * This class is experimental and intended for testing only.
 *
 * @param peerId Synthetic 12-byte peer identity for this node.
 */
@ExperimentalMeshLinkApi
public class VirtualMeshTransport(public val peerId: ByteArray) {

    private val _advertisementEvents =
        MutableSharedFlow<AdvertisementEvent>(replay = 0, extraBufferCapacity = 64)
    private val _peerLostEvents =
        MutableSharedFlow<PeerLostEvent>(replay = 0, extraBufferCapacity = 64)
    private val _incomingData =
        MutableSharedFlow<IncomingData>(replay = 0, extraBufferCapacity = 64)

    private val links = mutableMapOf<ByteArrayKey, VirtualMeshTransport>()

    /**
     * The internal [BleTransport] implementation passed to [MeshLink.createForTest]. Not accessible
     * outside this module — consumers interact via the public methods on this class.
     */
    internal val transport: BleTransport = TransportImpl()

    /**
     * Links this transport bidirectionally with [other]. Frames sent to [other]'s [peerId] will be
     * delivered to [other]'s incoming data flow, and vice versa.
     */
    public fun linkTo(other: VirtualMeshTransport) {
        links[ByteArrayKey(other.peerId)] = other
        other.links[ByteArrayKey(peerId)] = this
    }

    /**
     * Removes the bidirectional link with [other]. Frames sent to [other] after this call will
     * fail.
     */
    public fun unlink(other: VirtualMeshTransport) {
        links.remove(ByteArrayKey(other.peerId))
        other.links.remove(ByteArrayKey(peerId))
    }

    /**
     * Injects a synthetic discovery event as if the given [fromPeerId] was seen in a BLE
     * advertisement.
     */
    public suspend fun simulateDiscovery(
        fromPeerId: ByteArray,
        serviceData: ByteArray,
        rssi: Int = -50,
    ) {
        _advertisementEvents.emit(AdvertisementEvent(fromPeerId, serviceData, rssi))
    }

    /** Injects a synthetic peer-lost event as if the given [lostPeerId] disconnected. */
    public suspend fun simulatePeerLost(lostPeerId: ByteArray) {
        _peerLostEvents.emit(PeerLostEvent(lostPeerId, PeerLostReason.CONNECTION_LOST))
    }

    /**
     * Injects raw bytes directly into the incoming data flow without going through the link layer.
     * Useful for testing malformed frame handling.
     */
    public suspend fun injectRawIncoming(fromPeerId: ByteArray, rawBytes: ByteArray) {
        _incomingData.emit(IncomingData(fromPeerId.copyOf(), rawBytes))
    }

    // ── Internal BleTransport delegate ───────────────────────────────────────

    private inner class TransportImpl : BleTransport {
        override val localPeerId: ByteArray
            get() = this@VirtualMeshTransport.peerId

        override var advertisementServiceData: ByteArray = ByteArray(16)
        override var advertisementPseudonym: ByteArray = ByteArray(12)

        override val advertisementEvents: Flow<AdvertisementEvent>
            get() = _advertisementEvents

        override val peerLostEvents: Flow<PeerLostEvent>
            get() = _peerLostEvents

        override val incomingData: Flow<IncomingData>
            get() = _incomingData

        override suspend fun startAdvertisingAndScanning() {}

        override suspend fun stopAll() {}

        override suspend fun sendToPeer(peerId: ByteArray, data: ByteArray): SendResult {
            val target =
                links[ByteArrayKey(peerId)]
                    ?: return SendResult.Failure("Peer not linked: ${peerId.contentToString()}")
            target._incomingData.emit(IncomingData(this@VirtualMeshTransport.peerId.copyOf(), data))
            return SendResult.Success
        }

        override suspend fun disconnect(peerId: ByteArray) {
            val target = links.remove(ByteArrayKey(peerId)) ?: return
            target.links.remove(ByteArrayKey(this@VirtualMeshTransport.peerId))
            target._peerLostEvents.emit(
                PeerLostEvent(
                    this@VirtualMeshTransport.peerId.copyOf(),
                    PeerLostReason.MANUAL_DISCONNECT,
                )
            )
            _peerLostEvents.emit(PeerLostEvent(peerId.copyOf(), PeerLostReason.MANUAL_DISCONNECT))
        }

        override suspend fun requestConnectionPriority(peerId: ByteArray, highPriority: Boolean) {}
    }

    // ── Internal key wrapper ─────────────────────────────────────────────────

    private class ByteArrayKey(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is ByteArrayKey && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }
}
