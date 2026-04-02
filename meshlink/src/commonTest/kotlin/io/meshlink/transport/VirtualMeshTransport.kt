package io.meshlink.transport

import io.meshlink.util.toHex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Simulated BLE stack for testing.
 *
 * Models the GAP/GATT layers of Bluetooth Low Energy:
 * - **Advertising & scanning**: [startAdvertisingAndScanning] enables the radio.
 *   Linked neighbours that are also advertising are discovered automatically
 *   (mutual [AdvertisementEvent] emission).
 * - **Peer loss**: [stopAll] notifies scanning neighbours via [PeerLostEvent].
 *   [unlinkFrom] simulates a peer moving out of BLE range.
 * - **GATT connections**: Lazily established on first [sendToPeer]. Each
 *   connection negotiates an MTU of `min(local, remote)`.
 * - **Packet control**: [dropFilter] simulates packet loss, [sendFailure]
 *   simulates transport errors.
 *
 * Manual injection helpers ([simulateDiscovery], [simulatePeerLost],
 * [receiveData]) remain available for edge-case tests.
 */
class VirtualMeshTransport(
    override val localPeerId: ByteArray,
    val mtu: Int = DEFAULT_MTU,
) : BleTransport {

    companion object {
        const val DEFAULT_MTU = 185
    }

    override var advertisementServiceData: ByteArray = ByteArray(0)

    private val _advertisementEvents = MutableSharedFlow<AdvertisementEvent>(extraBufferCapacity = 64)
    private val _incomingData = MutableSharedFlow<IncomingData>(extraBufferCapacity = 64)
    private val _peerLostEvents = MutableSharedFlow<PeerLostEvent>(extraBufferCapacity = 64)

    override val advertisementEvents: Flow<AdvertisementEvent> = _advertisementEvents.asSharedFlow()
    override val incomingData: Flow<IncomingData> = _incomingData.asSharedFlow()
    override val peerLostEvents: Flow<PeerLostEvent> = _peerLostEvents.asSharedFlow()

    // ── BLE Radio State ──

    /** Whether this transport is currently advertising and scanning. */
    var advertising = false
        private set

    // ── Link Topology (simulated BLE range) ──

    private val neighbors = mutableMapOf<String, VirtualMeshTransport>()

    // ── GATT Connection State ──

    /** Snapshot of a negotiated GATT connection to a peer. */
    data class GattConnection(
        val peer: VirtualMeshTransport,
        val negotiatedMtu: Int,
    )

    private val gattConnections = mutableMapOf<String, GattConnection>()

    /** Currently active GATT connections (read-only snapshot). */
    val activeConnections: Map<String, GattConnection> get() = gattConnections.toMap()

    // ── BleTransport Implementation ──

    /**
     * Turn on the radio: start advertising our service data and scanning
     * for neighbours. Any linked neighbour that is already advertising
     * is discovered immediately (both peers receive an [AdvertisementEvent]).
     */
    override suspend fun startAdvertisingAndScanning() {
        advertising = true
        for ((_, neighbor) in neighbors) {
            if (neighbor.advertising) {
                _advertisementEvents.emit(
                    AdvertisementEvent(neighbor.localPeerId, neighbor.advertisementServiceData)
                )
                neighbor._advertisementEvents.emit(
                    AdvertisementEvent(localPeerId, advertisementServiceData)
                )
            }
        }
    }

    /**
     * Turn off the radio: stop advertising and scanning.
     * Scanning neighbours receive a [PeerLostEvent] and GATT connections
     * are torn down.
     */
    override suspend fun stopAll() {
        advertising = false
        for ((_, neighbor) in neighbors) {
            if (neighbor.advertising) {
                neighbor._peerLostEvents.emit(PeerLostEvent(localPeerId))
            }
        }
        gattConnections.clear()
    }

    /**
     * Send data to a peer. A GATT connection is established lazily on first
     * send (MTU negotiated as `min(local, remote)`). The data is delivered
     * to the target's [incomingData] flow.
     */
    override suspend fun sendToPeer(peerId: ByteArray, data: ByteArray) {
        val key = peerId.toHex()
        sentData.add(key to data)
        if (sendFailure) throw RuntimeException("Transport send failure")
        if (dropFilter?.invoke(data) == true) {
            droppedCount++
            return
        }
        val neighbor = neighbors[key] ?: error("Peer $key not in BLE range")
        if (key !in gattConnections) {
            val negotiated = minOf(mtu, neighbor.mtu)
            gattConnections[key] = GattConnection(neighbor, negotiated)
            neighbor.gattConnections[localPeerId.toHex()] = GattConnection(this, negotiated)
        }
        neighbor.receiveData(localPeerId, data)
    }

    // ── Test Control API ──

    /** All data sent via [sendToPeer] (peerId hex → payload), for test assertions. */
    val sentData = mutableListOf<Pair<String, ByteArray>>()

    /** When true, [sendToPeer] throws [RuntimeException]. */
    var sendFailure: Boolean = false

    /** Optional predicate: return true to silently drop the packet. */
    var dropFilter: ((ByteArray) -> Boolean)? = null

    /** Number of packets dropped by [dropFilter]. */
    var droppedCount: Int = 0

    /**
     * Place two transports within simulated BLE range of each other.
     * If both are already advertising, mutual discovery events fire.
     */
    suspend fun linkTo(other: VirtualMeshTransport) {
        neighbors[other.localPeerId.toHex()] = other
        other.neighbors[localPeerId.toHex()] = this
        if (advertising && other.advertising) {
            _advertisementEvents.emit(
                AdvertisementEvent(other.localPeerId, other.advertisementServiceData)
            )
            other._advertisementEvents.emit(
                AdvertisementEvent(localPeerId, advertisementServiceData)
            )
        }
    }

    /**
     * Remove a transport from BLE range. If the peer was previously
     * discovered, both sides receive a [PeerLostEvent] and any GATT
     * connection is torn down.
     */
    suspend fun unlinkFrom(other: VirtualMeshTransport) {
        val otherKey = other.localPeerId.toHex()
        val selfKey = localPeerId.toHex()
        neighbors.remove(otherKey)
        other.neighbors.remove(selfKey)
        gattConnections.remove(otherKey)
        other.gattConnections.remove(selfKey)
        if (advertising) {
            _peerLostEvents.emit(PeerLostEvent(other.localPeerId))
        }
        if (other.advertising) {
            other._peerLostEvents.emit(PeerLostEvent(localPeerId))
        }
    }

    /**
     * Manually inject a discovery event. Prefer [linkTo] +
     * [startAdvertisingAndScanning] for standard flows; use this for
     * edge-case tests that need fine-grained control.
     */
    suspend fun simulateDiscovery(
        peerId: ByteArray,
        advertisementPayload: ByteArray = ByteArray(0),
    ) {
        _advertisementEvents.emit(AdvertisementEvent(peerId, advertisementPayload))
    }

    /** Manually inject a peer loss event. */
    suspend fun simulatePeerLost(peerId: ByteArray) {
        _peerLostEvents.emit(PeerLostEvent(peerId))
    }

    /** Deliver raw data as if received via a GATT write from a peer. */
    suspend fun receiveData(fromPeerId: ByteArray, data: ByteArray) {
        _incomingData.emit(IncomingData(fromPeerId, data))
    }
}
