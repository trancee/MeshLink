package ch.trancee.meshlink.routing

import ch.trancee.meshlink.crypto.TrustStore
import ch.trancee.meshlink.wire.Hello
import ch.trancee.meshlink.wire.Update
import ch.trancee.meshlink.wire.WireMessage
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * Compute composite link cost from raw signal metrics (spec §4).
 *
 * ```
 * link_cost = rssiBaseCost × lossMultiplier × freshnessPenalty + stabilityPenalty
 * ```
 *
 * Components:
 * - **rssiBaseCost**: `max(1.0, (-rssi - 50) / 5.0)` — -50 dBm → 1.0, -70 dBm → 4.0
 * - **lossMultiplier**: `1.0 + lossRate × 10.0` — 0% → 1.0, 5% → 1.5
 * - **freshnessPenalty**: `1.0 + 0.5 × min(1.0, ms / (4 × keepalive))` — range 1.0→1.5
 * - **stabilityPenalty**: `max(0.0, 3.0 - consecutiveStableIntervals)` — new link +3, decays
 */
internal fun computeLinkCost(
    rssi: Int,
    lossRate: Double,
    millisSinceLastHello: Long,
    keepaliveIntervalMillis: Long,
    consecutiveStableIntervals: Int,
): Double {
    val rssiBaseCost = max(1.0, (-rssi - 50).toDouble() / 5.0)
    val lossMultiplier = 1.0 + (lossRate * 10.0)
    val freshnessPenalty =
        1.0 + 0.5 * min(1.0, millisSinceLastHello.toDouble() / (4.0 * keepaliveIntervalMillis))
    val stabilityPenalty = max(0.0, 3.0 - consecutiveStableIntervals.toDouble())
    return rssiBaseCost * lossMultiplier * freshnessPenalty + stabilityPenalty
}

/**
 * Per-neighbour state used to compute composite link cost on subsequent Hellos and Updates.
 *
 * Plain class (not data class) to avoid Kover `!is NeighborState` dead-branch in generated equals —
 * consistent with the RouteEntry precedent in T02.
 */
internal class NeighborState(
    val peerInfo: PeerInfo,
    val lastHelloTimeMillis: Long,
    val consecutiveStableIntervals: Int,
)

/**
 * Active coordinator that wires [RoutingTable], [RoutingEngine], [DedupSet], [PresenceTracker], and
 * [TrustStore] together.
 *
 * Responsibilities:
 * - Computes composite link cost from [PeerInfo] signal metrics.
 * - Processes peer-connect/disconnect events: installs direct routes, retracts stale routes.
 * - Dispatches inbound [WireMessage]s to the routing engine; pins X25519 keys on accepted Updates.
 * - Provides [lookupNextHop] with rate-limited on-demand discovery via Hello broadcast.
 * - Forwards [RoutingEngine.outboundMessages] through [outboundFrames] once [start] is called.
 */
internal class RouteCoordinator(
    private val localPeerId: ByteArray,
    private val localEdPublicKey: ByteArray,
    private val localDhPublicKey: ByteArray,
    private val routingTable: RoutingTable,
    private val routingEngine: RoutingEngine,
    private val dedupSet: DedupSet,
    private val presenceTracker: PresenceTracker,
    private val trustStore: TrustStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val config: RoutingConfig,
) {
    private val _outboundFrames = MutableSharedFlow<OutboundFrame>(extraBufferCapacity = 64)

    /** Outbound frames ready for the transport layer. Broadcast frames have `peerId == null`. */
    val outboundFrames: Flow<OutboundFrame> = _outboundFrames

    // Per-neighbour state keyed by peerId.asList() for content-equality semantics (MEM047).
    private val neighborStates: HashMap<List<Byte>, NeighborState> = HashMap()

    // Rate-limit on-demand discovery: destKey → lastDiscoveryAttemptMillis.
    private val discoveryAttempts: HashMap<List<Byte>, Long> = HashMap()

    // ── onPeerConnected ───────────────────────────────────────────────────────

    /**
     * Called when a new BLE peer connects.
     *
     * Steps: install direct route → register neighbour → notify presence → trigger full dump →
     * record neighbour state.
     */
    fun onPeerConnected(peerInfo: PeerInfo) {
        val linkCost =
            computeLinkCost(
                rssi = peerInfo.rssi,
                lossRate = peerInfo.lossRate,
                millisSinceLastHello = 0L,
                keepaliveIntervalMillis = config.helloIntervalMillis,
                consecutiveStableIntervals = 0,
            )
        routingTable.install(
            RouteEntry(
                destination = peerInfo.peerId,
                nextHop = peerInfo.peerId,
                metric = linkCost,
                seqNo = 0u,
                feasibilityDistance = linkCost,
                expiresAt = clock() + config.routeExpiryMillis,
                ed25519PublicKey = ByteArray(32),
                x25519PublicKey = trustStore.getPinnedKey(peerInfo.peerId) ?: ByteArray(32),
            )
        )
        routingEngine.registerNeighbor(peerInfo.peerId)
        presenceTracker.onPeerConnected(peerInfo.peerId)

        // Trigger full dump to the new peer.
        val updates =
            routingEngine.processHello(
                peerInfo.peerId,
                Hello(localPeerId, 0u, routingTable.routeDigest()),
            )
        for (update in updates) {
            _outboundFrames.tryEmit(OutboundFrame(peerId = peerInfo.peerId, message = update))
        }

        neighborStates[peerInfo.peerId.asList()] =
            NeighborState(
                peerInfo = peerInfo,
                lastHelloTimeMillis = clock(),
                consecutiveStableIntervals = 0,
            )
    }

    // ── onPeerDisconnected ────────────────────────────────────────────────────

    /**
     * Called when a BLE peer disconnects.
     *
     * Retracts all routes whose next-hop was the departing peer, broadcasts retraction Updates, and
     * cleans up tracking state.
     */
    fun onPeerDisconnected(peerId: ByteArray) {
        val retractions = routingEngine.retractRoutesVia(peerId)
        for (retraction in retractions) {
            _outboundFrames.tryEmit(OutboundFrame(peerId = null, message = retraction))
        }
        presenceTracker.onPeerDisconnected(peerId)
        routingEngine.unregisterNeighbor(peerId)
        neighborStates.remove(peerId.asList())
    }

    // ── processInbound ────────────────────────────────────────────────────────

    /**
     * Process an inbound [WireMessage] from [fromPeerId].
     *
     * - [Hello]: delegates to [RoutingEngine.processHello], emits returned Updates back to sender,
     *   and refreshes the neighbour's Hello timestamp and stability counter.
     * - [Update]: computes link cost from neighbour state, delegates to
     *   [RoutingEngine.processUpdate]. On acceptance, pins the X25519 key via [TrustStore].
     * - All other message types: silently ignored.
     */
    fun processInbound(fromPeerId: ByteArray, message: WireMessage) {
        when (message) {
            is Hello -> {
                val updates = routingEngine.processHello(fromPeerId, message)
                for (update in updates) {
                    _outboundFrames.tryEmit(OutboundFrame(peerId = fromPeerId, message = update))
                }
                val neighborState = neighborStates[fromPeerId.asList()]
                if (neighborState != null) {
                    neighborStates[fromPeerId.asList()] =
                        NeighborState(
                            peerInfo = neighborState.peerInfo,
                            lastHelloTimeMillis = clock(),
                            consecutiveStableIntervals =
                                neighborState.consecutiveStableIntervals + 1,
                        )
                }
            }
            is Update -> {
                val neighborState = neighborStates[fromPeerId.asList()]
                val linkCost =
                    if (neighborState != null) {
                        computeLinkCost(
                            rssi = neighborState.peerInfo.rssi,
                            lossRate = neighborState.peerInfo.lossRate,
                            millisSinceLastHello = clock() - neighborState.lastHelloTimeMillis,
                            keepaliveIntervalMillis = config.helloIntervalMillis,
                            consecutiveStableIntervals = neighborState.consecutiveStableIntervals,
                        )
                    } else {
                        config.defaultLinkCost
                    }
                val accepted = routingEngine.processUpdate(fromPeerId, message, linkCost)
                if (accepted) {
                    trustStore.pinKey(message.destination, message.x25519PublicKey)
                }
            }
            else -> {
                // Keepalive, Handshake, Chunk, etc.: not routing messages, silently ignored.
            }
        }
    }

    // ── lookupNextHop ─────────────────────────────────────────────────────────

    /**
     * Look up the next-hop for [destination].
     *
     * Returns the next-hop [ByteArray] when a valid route exists. When no route is known, emits a
     * Hello broadcast for on-demand discovery (rate-limited per destination by
     * [RoutingConfig.routeDiscoveryTimeoutMillis]) and returns `null`.
     */
    fun lookupNextHop(destination: ByteArray): ByteArray? {
        val nextHop = routingTable.lookupNextHop(destination)
        if (nextHop != null) return nextHop

        val destKey = destination.asList()
        val lastAttempt = discoveryAttempts[destKey]
        val now = clock()
        if (lastAttempt == null || now - lastAttempt > config.routeDiscoveryTimeoutMillis) {
            discoveryAttempts[destKey] = now
            _outboundFrames.tryEmit(
                OutboundFrame(
                    peerId = null,
                    message = Hello(localPeerId, 0u, routingTable.routeDigest()),
                )
            )
        }
        return null
    }

    // ── connectedPeers ────────────────────────────────────────────────────────

    /**
     * Returns the peer IDs of all currently-connected neighbours (keys of [neighborStates]
     * converted back from `List<Byte>` to [ByteArray]).
     */
    fun connectedPeers(): List<ByteArray> = neighborStates.keys.map { it.toByteArray() }

    // ── lookupEdPublicKey ─────────────────────────────────────────────────────

    /**
     * Returns the Ed25519 public key for [destination] from the routing table, or `null` if no
     * valid route exists.
     */
    fun lookupEdPublicKey(destination: ByteArray): ByteArray? =
        routingTable.lookupRoute(destination)?.ed25519PublicKey

    // ── isDuplicate ───────────────────────────────────────────────────────────

    /**
     * Returns `true` if [messageId] has already been processed (duplicate); records it otherwise.
     */
    fun isDuplicate(messageId: ByteArray): Boolean {
        if (dedupSet.isDuplicate(messageId)) return true
        dedupSet.add(messageId)
        return false
    }

    // ── start ─────────────────────────────────────────────────────────────────

    /**
     * Start background timers and wire [RoutingEngine.outboundMessages] into [outboundFrames].
     *
     * Must be called once after construction. Uses [scope] (pass `backgroundScope` in tests).
     */
    fun start() {
        routingEngine.startTimers()
        scope.launch {
            routingEngine.outboundMessages.collect { frame -> _outboundFrames.emit(frame) }
        }
    }
}
