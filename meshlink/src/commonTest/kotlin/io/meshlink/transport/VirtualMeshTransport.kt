package io.meshlink.transport

import io.meshlink.util.toHex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-process BLE transport simulator for testing.
 * Each instance represents one virtual device in the mesh.
 */
class VirtualMeshTransport(
    override val localPeerId: ByteArray,
) : BleTransport {

    private val _advertisementEvents = MutableSharedFlow<AdvertisementEvent>(extraBufferCapacity = 64)
    private val _incomingData = MutableSharedFlow<IncomingData>(extraBufferCapacity = 64)
    private val _peerLostEvents = MutableSharedFlow<PeerLostEvent>(extraBufferCapacity = 64)

    override val advertisementEvents: Flow<AdvertisementEvent> = _advertisementEvents.asSharedFlow()
    override val incomingData: Flow<IncomingData> = _incomingData.asSharedFlow()
    override val peerLostEvents: Flow<PeerLostEvent> = _peerLostEvents.asSharedFlow()

    /** Whether this transport is currently advertising/scanning. */
    var advertising = false
        private set

    override suspend fun startAdvertisingAndScanning() {
        advertising = true
    }

    override suspend fun stopAll() {
        advertising = false
    }

    override suspend fun sendToPeer(peerId: ByteArray, data: ByteArray) {
        sentData.add(peerId.toHex() to data)
        // Simulate transport failure if configured
        if (sendFailure) throw RuntimeException("Transport send failure")
        // Check if this packet should be dropped
        if (dropFilter?.invoke(data) == true) {
            droppedCount++
            return
        }
        val target = peers[peerId.toHex()] ?: error("No linked peer: ${peerId.toHex()}")
        target.receiveData(localPeerId, data)
    }

    // --- Simulation control ---

    private val peers = mutableMapOf<String, VirtualMeshTransport>()

    /** All data sent via sendToPeer (peerId hex → data), for test assertions. */
    val sentData = mutableListOf<Pair<String, ByteArray>>()

    /** When true, sendToPeer throws RuntimeException. */
    var sendFailure: Boolean = false

    /** Optional predicate: return true to drop the packet silently. */
    var dropFilter: ((ByteArray) -> Boolean)? = null

    /** Count of packets dropped by dropFilter. */
    var droppedCount: Int = 0

    /** Link two virtual transports so they can discover and communicate. */
    fun linkTo(other: VirtualMeshTransport) {
        peers[other.localPeerId.toHex()] = other
        other.peers[localPeerId.toHex()] = this
    }

    /** Simulate peer discovery (as if we received their advertisement). */
    suspend fun simulateDiscovery(peerId: ByteArray, advertisementPayload: ByteArray = ByteArray(17)) {
        _advertisementEvents.emit(AdvertisementEvent(peerId, advertisementPayload))
    }

    /** Simulate peer disappearance. */
    suspend fun simulatePeerLost(peerId: ByteArray) {
        _peerLostEvents.emit(PeerLostEvent(peerId))
    }

    /** Deliver raw data as if it arrived from a peer. */
    suspend fun receiveData(fromPeerId: ByteArray, data: ByteArray) {
        _incomingData.emit(IncomingData(fromPeerId, data))
    }
}
