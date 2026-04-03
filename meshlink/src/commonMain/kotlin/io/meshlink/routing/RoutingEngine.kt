package io.meshlink.routing

import io.meshlink.util.ByteArrayKey
import io.meshlink.util.currentTimeMillis
import io.meshlink.wire.WireCodec

sealed interface NextHopResult {
    data class Direct(val peerId: ByteArrayKey) : NextHopResult
    data class ViaRoute(val nextHop: ByteArrayKey) : NextHopResult
    data object Unreachable : NextHopResult
}

/**
 * Babel-style routing engine (RFC 8966 adapted for BLE).
 *
 * Routes are propagated via Hello/Update messages. The feasibility
 * condition (D(B) < FD(A)) guarantees loop-freedom at all times.
 * Triggered updates on topology changes provide fast convergence.
 *
 * Key propagation: each Update carries a 32-byte Ed25519 public key,
 * enabling E2E encryption for non-adjacent peers.
 */
class RoutingEngine(
    private val localPeerId: ByteArrayKey,
    private val dedupCapacity: Int = 10_000,
    private val routeCacheTtlMillis: Long = 300_000L,
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

    // Babel feasibility distances: destination → minimum metric ever advertised
    private val feasibilityDistance = mutableMapOf<ByteArrayKey, Double>()

    // Babel sequence number for this node's route announcements
    private var localSeqNo: UShort = 0u

    // Key propagation: destination → Ed25519 public key
    private val peerPublicKeys = mutableMapOf<ByteArrayKey, ByteArray>()

    // Next-hop reliability tracking
    private val nextHopFailures = mutableMapOf<ByteArrayKey, Int>()
    private val nextHopSuccesses = mutableMapOf<ByteArrayKey, Int>()

    companion object {
        /** Metric value representing an unreachable destination (retraction). */
        const val METRIC_INFINITY: UShort = 0xFFFFu
    }

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

    /**
     * Add or update a route. Includes sanity validation: the advertised cost
     * must be at least the link cost to the next-hop neighbor. Routes with
     * suspiciously low costs (potential blackhole attack) are rejected.
     */
    fun addRoute(
        destination: ByteArrayKey,
        nextHop: ByteArrayKey,
        cost: Double,
        sequenceNumber: UInt,
    ) {
        // Route cost sanity check (FIND-05 mitigation):
        // The cost to reach a destination via a neighbor cannot be lower
        // than the link cost to that neighbor itself.
        val linkCost = if (costCalculator.hasData(nextHop)) costCalculator.computeCost(nextHop) else null
        if (linkCost != null && cost < linkCost && cost > 0.0) {
            // Suspiciously low cost — clamp to at least the link cost.
            // This prevents trivial blackhole attacks where a malicious peer
            // advertises cost=0.1 for all destinations.
            routingTable.addRoute(destination, nextHop, linkCost, sequenceNumber)
            return
        }
        routingTable.addRoute(destination, nextHop, cost, sequenceNumber)
    }

    fun resolveNextHop(destination: ByteArrayKey): NextHopResult {
        if (destination in presenceTracker.allPeerIds()) {
            return NextHopResult.Direct(destination)
        }
        val route = routingTable.bestRoute(destination)
            ?: return NextHopResult.Unreachable
        return NextHopResult.ViaRoute(route.nextHop)
    }

    // ── Link quality measurement ──────────────────────────────────

    fun recordLinkMeasurement(peerId: ByteArrayKey, rssi: Int, lossRate: Double) {
        costCalculator.recordMeasurement(peerId, rssi, lossRate)
    }

    fun recordStableInterval(peerId: ByteArrayKey) {
        costCalculator.recordStableInterval(peerId)
    }

    fun recordLinkDisconnect(peerId: ByteArrayKey) {
        costCalculator.recordDisconnect(peerId)
    }

    fun computeLinkCost(peerId: ByteArrayKey): Double {
        return costCalculator.computeCost(peerId)
    }

    // ── Deduplication ─────────────────────────────────────────────

    fun isDuplicate(key: ByteArrayKey): Boolean = !dedup.tryInsert(key)

    // ── Babel Hello/Update ────────────────────────────────────────

    /** Build a Hello frame for this peer. */
    fun buildHello(): ByteArray {
        return WireCodec.encodeHello(localPeerId.bytes, localSeqNo)
    }

    /** Increment the local sequence number (called periodically). */
    fun bumpSeqNo() {
        localSeqNo++
    }

    /**
     * Handle an inbound Hello. Returns Update frames to send back
     * (full routing table for new neighbors, empty for known ones).
     */
    fun handleHello(
        fromPeerId: ByteArrayKey,
        @Suppress("UNUSED_PARAMETER") senderSeqNo: UShort,
    ): List<ByteArray> {
        val isNew = presenceTracker.state(fromPeerId) == null
        presenceTracker.peerSeen(fromPeerId)

        if (!isNew) return emptyList()

        // New neighbor — send full routing table as Updates
        return buildFullUpdateSet()
    }

    /**
     * Handle an inbound Update. Applies Babel feasibility condition:
     * a route is accepted only if metric < FD (feasibility distance).
     *
     * Returns the destination's public key if one was propagated, null otherwise.
     */
    fun handleUpdate(
        fromPeerId: ByteArrayKey,
        destination: ByteArrayKey,
        metric: UShort,
        seqNo: UShort,
        publicKey: ByteArray,
    ): ByteArray? {
        // Skip self-routes
        if (destination == localPeerId) return null

        // Route retraction (infinite metric)
        if (metric == METRIC_INFINITY) {
            routingTable.removeRoutesVia(destination, fromPeerId)
            return null
        }

        val linkCost = costCalculator.computeCost(fromPeerId)
        val totalMetric = metric.toDouble() + linkCost

        // Babel feasibility condition (RFC 8966 §2.4):
        // Accept if seqno is strictly newer, or (same/newer seqno AND metric < FD)
        val fd = feasibilityDistance[destination]
        val existing = routingTable.bestRoute(destination)

        if (existing != null && fd != null) {
            val existingSeqNo = existing.sequenceNumber.toUShort()
            if (seqNo < existingSeqNo) return null // older seqNo → reject
            if (seqNo == existingSeqNo && totalMetric >= fd) return null // not feasible
        }

        routingTable.addRoute(destination, fromPeerId, totalMetric, seqNo.toUInt())

        // Update feasibility distance (minimum metric ever advertised)
        val currentFd = feasibilityDistance[destination]
        if (currentFd == null || totalMetric < currentFd) {
            feasibilityDistance[destination] = totalMetric
        }

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
        updates.add(WireCodec.encodeUpdate(localPeerId.bytes, 0u, localSeqNo, selfKey))
        // Advertise all known routes
        for (route in routingTable.allBestRoutes()) {
            val destKey = peerPublicKeys[route.destination] ?: ByteArray(32)
            updates.add(
                WireCodec.encodeUpdate(
                    route.destination.bytes,
                    route.cost.toInt().coerceIn(0, 65534).toUShort(),
                    route.sequenceNumber.toUShort(),
                    destKey,
                )
            )
        }
        return updates
    }

    /**
     * Build a retraction Update for a destination that is no longer reachable.
     * Replaces AODV's missing RERR — sends metric=0xFFFF to notify neighbors.
     */
    fun buildRetraction(destination: ByteArrayKey): ByteArray {
        val destKey = peerPublicKeys[destination] ?: ByteArray(32)
        return WireCodec.encodeUpdate(
            destination.bytes,
            METRIC_INFINITY,
            routingTable.bestRoute(destination)?.sequenceNumber?.toUShort() ?: 0u,
            destKey,
        )
    }

    // ── Key propagation ───────────────────────────────────────────

    /** Store a public key for a peer (e.g., from Noise XX handshake). */
    fun registerPublicKey(peerId: ByteArrayKey, publicKey: ByteArray) {
        peerPublicKeys[peerId] = publicKey.copyOf()
    }

    /** Get the stored public key for a peer, if known. */
    fun getPublicKey(peerId: ByteArrayKey): ByteArray? = peerPublicKeys[peerId]

    // ── Route queries ───────────────────────────────────────

    fun bestRoute(destination: ByteArrayKey): RoutingTable.Route? =
        routingTable.bestRoute(destination)

    fun allBestRoutes(): List<RoutingTable.Route> = routingTable.allBestRoutes()

    // ── Health ─────────────────────────────────────────────

    val routeCount: Int get() = routingTable.size()
    val peerCount: Int get() = presenceTracker.allPeerIds().size
    val connectedPeerCount: Int get() = presenceTracker.connectedPeerIds().size
    val dedupSize: Int get() = dedup.size()
    fun avgCost(): Double = routingTable.avgCost()

    // ── Next-hop reliability tracking ──────────────────────

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

    fun nextHopFailureCount(nextHopId: ByteArrayKey): Int =
        nextHopFailures[nextHopId] ?: 0

    // ── Cleanup ──────────────────────────────────────────

    fun clear() {
        routingTable.clear()
        presenceTracker.clear()
        dedup.clear()
        nextHopFailures.clear()
        nextHopSuccesses.clear()
        costCalculator.clear()
        feasibilityDistance.clear()
        peerPublicKeys.clear()
        localSeqNo = 0u
    }

    fun clearDedup() {
        dedup.clear()
    }
}
