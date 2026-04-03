package io.meshlink.routing

import io.meshlink.util.ByteArrayKey
import io.meshlink.util.currentTimeMillis
import io.meshlink.wire.WireCodec

sealed interface NextHopResult {
    data class Direct(val peerId: ByteArrayKey) : NextHopResult
    data class ViaRoute(val nextHop: ByteArrayKey) : NextHopResult
    data object Unreachable : NextHopResult
}

/** Result of handling an inbound RREQ. */
sealed interface RouteRequestResult {
    /** We are the target or have a cached route — here is the RREP frame. */
    data class Reply(val replyFrame: ByteArray, val replyTo: ByteArrayKey) : RouteRequestResult

    /** RREQ should be reflooded to neighbours. */
    data class Flood(val rreqFrame: ByteArray) : RouteRequestResult

    /** Duplicate or expired RREQ — drop silently. */
    data object Drop : RouteRequestResult
}

/** Result of handling an inbound RREP. */
sealed interface RouteReplyResult {
    /** Route installed, forward the RREP toward the RREQ originator. */
    data class Forward(val rrepFrame: ByteArray, val nextHop: ByteArrayKey) : RouteReplyResult

    /** We are the RREQ originator — route is now available. */
    data class Resolved(val destination: ByteArrayKey) : RouteReplyResult

    /** Stale or duplicate RREP — drop silently. */
    data object Drop : RouteReplyResult
}

data class RouteDiscovery(
    val rreqFrame: ByteArray,
    val requestId: UInt,
)

/**
 * AODV-style reactive routing engine.
 *
 * Routes are discovered on-demand via RREQ/RREP flooding and cached
 * with a configurable TTL. 1-hop neighbour presence is tracked via
 * BLE discovery/keepalives (not routing protocol).
 *
 * MeshLink delegates all routing decisions to this engine and
 * pattern-matches on [NextHopResult] / [RouteRequestResult] /
 * [RouteReplyResult] to drive wire-level actions.
 */
class RoutingEngine(
    private val localPeerId: ByteArrayKey,
    private val dedupCapacity: Int = 10_000,
    private val routeCacheTtlMillis: Long = 60_000L,
    private val maxHops: UByte = 10u,
    private val clock: () -> Long = { currentTimeMillis() },
    private val keepaliveIntervalMillis: Long = 15_000L,
) {
    private val routingTable = RoutingTable(expiryMillis = routeCacheTtlMillis)
    private val presenceTracker = PresenceTracker()
    private val dedup = DedupSet(capacity = dedupCapacity, clock = clock)
    private val costCalculator = RouteCostCalculator(
        clock = clock,
        keepaliveIntervalMillis = keepaliveIntervalMillis,
    )

    // AODV reverse path: requestKey → sender who forwarded the RREQ to us
    private val reversePath = mutableMapOf<ByteArrayKey, ByteArrayKey>()

    // RREQ dedup: (origin + requestId) → timestamp
    private val rreqSeen = mutableMapOf<ByteArrayKey, Long>()

    private var nextRequestId: UInt = 0u

    private val nextHopFailures = mutableMapOf<ByteArrayKey, Int>()
    private val nextHopSuccesses = mutableMapOf<ByteArrayKey, Int>()

    // ── Key propagation (populated from Babel Updates) ──────────
    private val peerPublicKeys = mutableMapOf<ByteArrayKey, ByteArray>()

    // Babel sequence number for route freshness
    private var localSeqno: UShort = 0u

    // ── Presence ──────────────────────────────────────────────────

    fun peerSeen(peerId: ByteArrayKey) = presenceTracker.peerSeen(peerId)
    fun markDisconnected(peerId: ByteArrayKey) = presenceTracker.markDisconnected(peerId)
    fun presenceState(peerId: ByteArrayKey): PresenceState? = presenceTracker.state(peerId)
    fun connectedPeerIds(): Set<ByteArrayKey> = presenceTracker.connectedPeerIds()
    fun allPeerIds(): Set<ByteArrayKey> = presenceTracker.allPeerIds()
    fun sweepPresence(seenPeers: Set<ByteArrayKey>): Set<ByteArrayKey> {
        val evicted = presenceTracker.sweep(seenPeers)
        for (peerId in evicted) {
            costCalculator.removePeer(peerId)
        }
        return evicted
    }

    // ── Route management ──────────────────────────────────────────

    fun addRoute(destination: ByteArrayKey, nextHop: ByteArrayKey, cost: Double, sequenceNumber: UInt) {
        routingTable.addRoute(destination, nextHop, cost, sequenceNumber)
    }

    // ── Link quality measurement ──────────────────────────────────

    /**
     * Record a link quality measurement for a direct neighbor.
     * RSSI and loss rate feed into [RouteCostCalculator] for composite cost computation.
     */
    fun recordLinkMeasurement(peerId: ByteArrayKey, rssi: Int, lossRate: Double) {
        costCalculator.recordMeasurement(peerId, rssi, lossRate)
    }

    /**
     * Record that a keepalive interval passed with stable connectivity to a neighbor.
     * Reduces the stability penalty in route cost computation.
     */
    fun recordStableInterval(peerId: ByteArrayKey) {
        costCalculator.recordStableInterval(peerId)
    }

    /**
     * Record a GATT disconnect for a neighbor.
     * Resets the stability counter in route cost computation.
     */
    fun recordLinkDisconnect(peerId: ByteArrayKey) {
        costCalculator.recordDisconnect(peerId)
    }

    /**
     * Compute the link cost for a direct neighbor using available quality data.
     * Returns [RouteCostCalculator.DEFAULT_COST] if no measurement data exists.
     */
    fun computeLinkCost(peerId: ByteArrayKey): Double {
        return costCalculator.computeCost(peerId)
    }

    fun resolveNextHop(destination: ByteArrayKey): NextHopResult {
        if (destination in presenceTracker.allPeerIds()) {
            return NextHopResult.Direct(destination)
        }
        val route = routingTable.bestRoute(destination)
            ?: return NextHopResult.Unreachable
        return NextHopResult.ViaRoute(route.nextHop)
    }

    // ── Deduplication ─────────────────────────────────────────────

    fun isDuplicate(key: ByteArrayKey): Boolean = !dedup.tryInsert(key)

    // ── AODV Route Discovery ──────────────────────────────────────

    /**
     * Initiate a route discovery for [destination].
     * Returns an RREQ frame to flood to all neighbours.
     */
    fun initiateRouteDiscovery(destination: ByteArrayKey): RouteDiscovery {
        val reqId = nextRequestId++
        val rreqKey = rreqKey(localPeerId, reqId)
        rreqSeen[rreqKey] = clock()

        val frame = WireCodec.encodeRouteRequest(
            origin = localPeerId.bytes,
            destination = destination.bytes,
            requestId = reqId,
            hopCount = 0u,
            hopLimit = maxHops,
        )
        return RouteDiscovery(frame, reqId)
    }

    /**
     * Handle an inbound RREQ from [fromPeerId].
     * - If we are the destination, generate an RREP.
     * - If we have a cached route, generate an RREP (intermediate reply).
     * - Otherwise, record the reverse path and return [RouteRequestResult.Flood].
     */
    fun handleRouteRequest(
        fromPeerId: ByteArrayKey,
        originPeerId: ByteArrayKey,
        destinationPeerId: ByteArrayKey,
        requestId: UInt,
        hopCount: UByte,
        hopLimit: UByte,
    ): RouteRequestResult {
        val rreqKey = rreqKey(originPeerId, requestId)

        // Dedup: already seen this RREQ?
        if (rreqSeen.containsKey(rreqKey)) return RouteRequestResult.Drop
        rreqSeen[rreqKey] = clock()

        // Record reverse path: how to get back to origin
        reversePath[rreqKey] = fromPeerId

        // Install reverse route to origin (for RREP and future traffic)
        val linkCost = costCalculator.computeCost(fromPeerId)
        routingTable.addRoute(originPeerId, fromPeerId, linkCost * (hopCount.toDouble() + 1.0), requestId)

        // Are we the destination?
        if (destinationPeerId == localPeerId) {
            val rrep = WireCodec.encodeRouteReply(
                origin = originPeerId.bytes,
                destination = localPeerId.bytes,
                requestId = requestId,
                hopCount = 0u,
            )
            return RouteRequestResult.Reply(rrep, fromPeerId)
        }

        // Do we have a cached route to the destination?
        val cached = routingTable.bestRoute(destinationPeerId)
        if (cached != null) {
            val rrep = WireCodec.encodeRouteReply(
                origin = originPeerId.bytes,
                destination = destinationPeerId.bytes,
                requestId = requestId,
                hopCount = (cached.cost.toInt()).toUByte(),
            )
            return RouteRequestResult.Reply(rrep, fromPeerId)
        }

        // Hop limit check
        if (hopCount >= hopLimit) return RouteRequestResult.Drop

        // Reflood with incremented hop count
        val reflood = WireCodec.encodeRouteRequest(
            origin = originPeerId.bytes,
            destination = destinationPeerId.bytes,
            requestId = requestId,
            hopCount = (hopCount + 1u).toUByte(),
            hopLimit = hopLimit,
        )
        return RouteRequestResult.Flood(reflood)
    }

    /**
     * Handle an inbound RREP from [fromPeerId].
     * - Install forward route to destination via fromPeerId.
     * - If we are the RREQ originator, return [RouteReplyResult.Resolved].
     * - Otherwise, forward the RREP along the reverse path.
     */
    fun handleRouteReply(
        fromPeerId: ByteArrayKey,
        originPeerId: ByteArrayKey,
        destinationPeerId: ByteArrayKey,
        requestId: UInt,
        hopCount: UByte,
    ): RouteReplyResult {
        // Install forward route to destination via the sender
        val linkCost = costCalculator.computeCost(fromPeerId)
        routingTable.addRoute(destinationPeerId, fromPeerId, linkCost * (hopCount.toDouble() + 1.0), requestId)

        // Are we the original requester?
        if (originPeerId == localPeerId) {
            return RouteReplyResult.Resolved(destinationPeerId)
        }

        // Forward RREP along reverse path
        val rreqKey = rreqKey(originPeerId, requestId)
        val nextHop = reversePath[rreqKey] ?: return RouteReplyResult.Drop

        val forwarded = WireCodec.encodeRouteReply(
            origin = originPeerId.bytes,
            destination = destinationPeerId.bytes,
            requestId = requestId,
            hopCount = (hopCount + 1u).toUByte(),
        )
        return RouteReplyResult.Forward(forwarded, nextHop)
    }

    // ── Route queries ─────────────────────────────────────────────

    fun bestRoute(destination: ByteArrayKey): RoutingTable.Route? = routingTable.bestRoute(destination)

    fun allBestRoutes(): List<RoutingTable.Route> = routingTable.allBestRoutes()

    // ── Health ─────────────────────────────────────────────────────

    val routeCount: Int get() = routingTable.size()
    val peerCount: Int get() = presenceTracker.allPeerIds().size
    val connectedPeerCount: Int get() = presenceTracker.connectedPeerIds().size
    val dedupSize: Int get() = dedup.size()
    fun avgCost(): Double = routingTable.avgCost()

    // ── Next-hop reliability tracking ──────────────────────────────

    fun recordNextHopFailure(nextHopId: ByteArrayKey) {
        nextHopFailures[nextHopId] = (nextHopFailures[nextHopId] ?: 0) + 1
    }

    fun recordNextHopSuccess(nextHopId: ByteArrayKey) {
        nextHopSuccesses[nextHopId] = (nextHopSuccesses[nextHopId] ?: 0) + 1
    }

    fun nextHopFailureRate(nextHopId: ByteArrayKey): Double {
        val failures = nextHopFailures[nextHopId] ?: 0
        val successes = nextHopSuccesses[nextHopId] ?: 0
        val total = failures + successes
        if (total == 0) return 0.0
        return failures.toDouble() / total
    }

    fun nextHopFailureCount(nextHopId: ByteArrayKey): Int = nextHopFailures[nextHopId] ?: 0

    // ── Babel Hello/Update ───────────────────────────────────

    /** Build a Hello frame for this peer. */
    fun buildHello(): ByteArray {
        return WireCodec.encodeHello(localPeerId.bytes, localSeqno)
    }

    /** Increment the local sequence number (called periodically). */
    fun bumpSeqno() {
        localSeqno++
    }

    /**
     * Handle an inbound Hello. Returns Update frames to send back
     * (full routing table dump for new neighbors, empty for known ones).
     */
    fun handleHello(
        fromPeerId: ByteArrayKey,
        @Suppress("UNUSED_PARAMETER") senderSeqno: UShort,
    ): List<ByteArray> {
        val isNew = presenceTracker.state(fromPeerId) == null
        presenceTracker.peerSeen(fromPeerId)

        if (!isNew) return emptyList()

        // New neighbor — send our full routing table as Updates
        return buildFullUpdateSet()
    }

    /**
     * Handle an inbound Update. Applies Babel feasibility condition:
     * accept only if seqno is newer or (same seqno and better metric).
     * Returns the destination's public key if one was propagated.
     */
    fun handleUpdate(
        fromPeerId: ByteArrayKey,
        destination: ByteArrayKey,
        metric: UShort,
        seqno: UShort,
        publicKey: ByteArray,
    ): ByteArray? {
        // Skip self-routes
        if (destination == localPeerId) return null

        val existing = routingTable.bestRoute(destination)
        val totalMetric = metric.toDouble() + costCalculator.computeCost(fromPeerId)

        // Feasibility condition: accept if seqno is strictly newer,
        // or same seqno with lower metric (loop-free per Babel RFC 8966 §3.5.1)
        if (existing != null) {
            val existingSeqno = existing.sequenceNumber
            if (seqno < existingSeqno.toUShort()) return null
            if (seqno == existingSeqno.toUShort() && totalMetric >= existing.cost) return null
        }

        routingTable.addRoute(destination, fromPeerId, totalMetric, seqno.toUInt())

        // Store propagated public key
        if (publicKey.isNotEmpty() && !publicKey.all { it == 0.toByte() }) {
            peerPublicKeys[destination] = publicKey.copyOf()
            return publicKey
        }
        return null
    }

    /** Build Update frames for the full routing table (for new neighbor). */
    fun buildFullUpdateSet(): List<ByteArray> {
        val updates = mutableListOf<ByteArray>()
        // Advertise self with metric 0
        val selfKey = peerPublicKeys[localPeerId] ?: ByteArray(32)
        updates.add(WireCodec.encodeUpdate(localPeerId.bytes, 0u, localSeqno, selfKey))
        // Advertise all known routes
        for (route in routingTable.allBestRoutes()) {
            val destKey = peerPublicKeys[route.destination] ?: ByteArray(32)
            updates.add(
                WireCodec.encodeUpdate(
                    route.destination.bytes,
                    route.cost.toInt().coerceIn(0, 65535).toUShort(),
                    route.sequenceNumber.toUShort(),
                    destKey,
                )
            )
        }
        return updates
    }

    // ── Key propagation ─────────────────────────────────────

    /** Store a public key for a peer (e.g., from Noise XX handshake). */
    fun registerPublicKey(peerId: ByteArrayKey, publicKey: ByteArray) {
        peerPublicKeys[peerId] = publicKey.copyOf()
    }

    /** Get the stored public key for a peer, if known. */
    fun getPublicKey(peerId: ByteArrayKey): ByteArray? = peerPublicKeys[peerId]

    // ── Cleanup ────────────────────────────────────────────────────

    fun clear() {
        routingTable.clear()
        presenceTracker.clear()
        dedup.clear()
        reversePath.clear()
        rreqSeen.clear()
        nextHopFailures.clear()
        nextHopSuccesses.clear()
        costCalculator.clear()
        peerPublicKeys.clear()
        localSeqno = 0u
        nextRequestId = 0u
    }

    fun clearDedup() {
        dedup.clear()
    }

    // ── Internal ───────────────────────────────────────────────────

    private fun rreqKey(origin: ByteArrayKey, requestId: UInt): ByteArrayKey {
        val key = ByteArray(origin.bytes.size + 4)
        origin.bytes.copyInto(key)
        key[origin.bytes.size] = (requestId.toInt() and 0xFF).toByte()
        key[origin.bytes.size + 1] = ((requestId.toInt() shr 8) and 0xFF).toByte()
        key[origin.bytes.size + 2] = ((requestId.toInt() shr 16) and 0xFF).toByte()
        key[origin.bytes.size + 3] = ((requestId.toInt() shr 24) and 0xFF).toByte()
        return ByteArrayKey(key)
    }
}
