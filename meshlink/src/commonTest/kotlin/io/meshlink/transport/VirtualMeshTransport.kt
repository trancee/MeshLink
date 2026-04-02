package io.meshlink.transport

import io.meshlink.util.toHex
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.random.Random

/**
 * Per-link properties modelling real-world BLE radio characteristics.
 *
 * @property rssi Simulated received signal strength in dBm (metadata only).
 * @property packetLossRate Probability that a packet is silently dropped
 *   (0.0 = perfect link, 1.0 = total loss). Uses the transport's [Random]
 *   instance for deterministic testing.
 * @property latencyMillis One-way latency injected before data delivery.
 *   Works with [kotlinx.coroutines.test.TestCoroutineScheduler] (virtual time).
 */
data class LinkProperties(
    val rssi: Int = -60,
    val packetLossRate: Double = 0.0,
    val latencyMillis: Long = 0,
)

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
 * - **Connection limits**: [maxConnections] caps concurrent GATT connections
 *   (default 7, matching typical consumer BLE hardware).
 * - **Link quality**: Per-link [LinkProperties] control packet loss rate,
 *   latency, and RSSI for each neighbour independently.
 * - **Packet control**: [dropFilter] adds custom per-transport drop logic,
 *   [sendFailure] simulates hard transport errors.
 *
 * Manual injection helpers ([simulateDiscovery], [simulatePeerLost],
 * [receiveData]) remain available for edge-case tests.
 *
 * @param localPeerId 8-byte identifier for this virtual peer.
 * @param mtu Maximum transmission unit for GATT writes (negotiated down
 *   to `min(local, remote)` on connection).
 * @param maxConnections Maximum concurrent GATT connections (throws on overflow).
 * @param random Random source for packet loss decisions. Use `Random(seed)`
 *   for deterministic tests.
 */
class VirtualMeshTransport(
    override val localPeerId: ByteArray,
    val mtu: Int = DEFAULT_MTU,
    val maxConnections: Int = DEFAULT_MAX_CONNECTIONS,
    private val random: Random = Random,
) : BleTransport {

    companion object {
        const val DEFAULT_MTU = 185
        const val DEFAULT_MAX_CONNECTIONS = 7
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

    private data class LinkedNeighbor(
        val transport: VirtualMeshTransport,
        val properties: LinkProperties,
    )

    private val neighbors = mutableMapOf<String, LinkedNeighbor>()

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
        for ((_, linked) in neighbors) {
            if (linked.transport.advertising) {
                _advertisementEvents.emit(
                    AdvertisementEvent(linked.transport.localPeerId, linked.transport.advertisementServiceData)
                )
                linked.transport._advertisementEvents.emit(
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
        for ((_, linked) in neighbors) {
            if (linked.transport.advertising) {
                linked.transport._peerLostEvents.emit(PeerLostEvent(localPeerId))
            }
        }
        gattConnections.clear()
    }

    /**
     * Send data to a peer. Enforces:
     * 1. Transport-level [sendFailure] check.
     * 2. Custom [dropFilter] check.
     * 3. Per-link [LinkProperties.packetLossRate] (probabilistic drop).
     * 4. [maxConnections] limit — throws if exceeded.
     * 5. GATT connection establishment (lazy, MTU negotiation).
     * 6. Per-link [LinkProperties.latencyMillis] delay.
     * 7. Data delivery to target's [incomingData] flow.
     */
    override suspend fun sendToPeer(peerId: ByteArray, data: ByteArray) {
        val key = peerId.toHex()
        sentData.add(key to data)
        if (sendFailure) throw RuntimeException("Transport send failure")
        if (dropFilter?.invoke(data) == true) {
            droppedCount++
            return
        }
        val linked = neighbors[key] ?: error("Peer $key not in BLE range")
        if (linked.properties.packetLossRate > 0.0 &&
            random.nextDouble() < linked.properties.packetLossRate
        ) {
            droppedCount++
            return
        }
        if (key !in gattConnections) {
            if (gattConnections.size >= maxConnections) {
                throw RuntimeException(
                    "Connection limit reached: ${gattConnections.size}/$maxConnections"
                )
            }
            val negotiated = minOf(mtu, linked.transport.mtu)
            gattConnections[key] = GattConnection(linked.transport, negotiated)
            linked.transport.gattConnections[localPeerId.toHex()] =
                GattConnection(this, negotiated)
        }
        if (linked.properties.latencyMillis > 0) {
            delay(linked.properties.latencyMillis)
        }
        linked.transport.receiveData(localPeerId, data)
    }

    // ── Test Control API ──

    /** All data sent via [sendToPeer] (peerId hex → payload), for test assertions. */
    val sentData = mutableListOf<Pair<String, ByteArray>>()

    /** When true, [sendToPeer] throws [RuntimeException]. */
    var sendFailure: Boolean = false

    /** Optional predicate: return true to silently drop the packet. */
    var dropFilter: ((ByteArray) -> Boolean)? = null

    /** Number of packets dropped by [dropFilter] or [LinkProperties.packetLossRate]. */
    var droppedCount: Int = 0

    /**
     * Place two transports within simulated BLE range of each other.
     * If both are already advertising, mutual discovery events fire.
     *
     * @param properties Link quality from this transport to [other].
     * @param reverseProperties Link quality from [other] back to this
     *   transport. Defaults to [properties] (symmetric link). Pass a
     *   different value to model asymmetric RF conditions.
     */
    suspend fun linkTo(
        other: VirtualMeshTransport,
        properties: LinkProperties = LinkProperties(),
        reverseProperties: LinkProperties = properties,
    ) {
        neighbors[other.localPeerId.toHex()] = LinkedNeighbor(other, properties)
        other.neighbors[localPeerId.toHex()] = LinkedNeighbor(this, reverseProperties)
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

    /** Current [LinkProperties] for the link to [other], or null if not linked. */
    fun linkPropertiesTo(other: VirtualMeshTransport): LinkProperties? =
        neighbors[other.localPeerId.toHex()]?.properties

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
