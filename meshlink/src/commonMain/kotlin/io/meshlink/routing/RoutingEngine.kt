package io.meshlink.routing

import io.meshlink.util.currentTimeMillis

sealed interface NextHopResult {
    data class Direct(val peerId: String) : NextHopResult
    data class ViaRoute(val nextHop: String) : NextHopResult
    data object Unreachable : NextHopResult
}

sealed interface RouteLearnResult {
    data object NoSignificantChange : RouteLearnResult
    data class SignificantChange(
        val routeChanges: List<RouteChange>,
    ) : RouteLearnResult
}

data class RouteChange(
    val destination: String,
    val oldNextHop: String,
    val newNextHop: String,
)

data class GossipEntry(
    val destination: String,
    val cost: Double,
    val sequenceNumber: UInt,
    val hopCount: UByte = 1u,
)

data class LearnedRoute(
    val destination: String,
    val cost: Double,
    val sequenceNumber: UInt,
)

/**
 * Facade consolidating routing table, presence tracking, deduplication,
 * gossip preparation (split-horizon / poison-reverse), and adaptive
 * gossip timing behind sealed result types.
 *
 * MeshLink delegates all routing decisions to this engine and
 * pattern-matches on [NextHopResult] / [RouteLearnResult] to drive
 * wire-level actions.
 */
class RoutingEngine(
    private val localPeerId: String,
    private val dedupCapacity: Int = 10_000,
    private val triggeredUpdateThreshold: Double = 0.3,
    private val gossipIntervalMillis: Long = 0L,
    private val clock: () -> Long = { currentTimeMillis() },
) {
    private val routingTable = RoutingTable()
    private val presenceTracker = PresenceTracker()
    private val dedup = DedupSet(capacity = dedupCapacity, clock = clock)
    private val previousNextHop = mutableMapOf<String, String>()
    private val lastTriggeredUpdateTime = mutableMapOf<String, Long>()
    private var lastGossipSentMillis: Long = 0L
    private val nextHopFailures = mutableMapOf<String, Int>()
    private val nextHopSuccesses = mutableMapOf<String, Int>()

    // ── Presence ──────────────────────────────────────────────────

    fun peerSeen(peerId: String) = presenceTracker.peerSeen(peerId)
    fun markDisconnected(peerId: String) = presenceTracker.markDisconnected(peerId)
    fun presenceState(peerId: String): PresenceState? = presenceTracker.state(peerId)
    fun connectedPeerIds(): Set<String> = presenceTracker.connectedPeerIds()
    fun allPeerIds(): Set<String> = presenceTracker.allPeerIds()
    fun sweepPresence(seenPeers: Set<String>): Set<String> = presenceTracker.sweep(seenPeers)

    // ── Route management ──────────────────────────────────────────

    fun addRoute(destination: String, nextHop: String, cost: Double, sequenceNumber: UInt) {
        routingTable.addRoute(destination, nextHop, cost, sequenceNumber)
    }

    fun resolveNextHop(destinationHex: String): NextHopResult {
        if (destinationHex in presenceTracker.allPeerIds()) {
            return NextHopResult.Direct(destinationHex)
        }
        val route = routingTable.bestRoute(destinationHex)
            ?: return NextHopResult.Unreachable
        return NextHopResult.ViaRoute(route.nextHop)
    }

    // ── Deduplication ─────────────────────────────────────────────

    fun isDuplicate(key: String): Boolean = !dedup.tryInsert(key)

    // ── Route learning ────────────────────────────────────────────

    fun learnRoutes(fromPeerHex: String, routes: List<LearnedRoute>): RouteLearnResult {
        val routeChanges = mutableListOf<RouteChange>()
        var significantChange = false

        for (route in routes) {
            if (route.destination == localPeerId) continue

            val oldBest = routingTable.bestRoute(route.destination)
            routingTable.addRoute(
                route.destination,
                fromPeerHex,
                route.cost + 1.0,
                route.sequenceNumber,
            )
            val newBest = routingTable.bestRoute(route.destination)

            if (oldBest != null && newBest != null && oldBest.nextHop != newBest.nextHop) {
                previousNextHop[route.destination] = oldBest.nextHop
                routeChanges.add(RouteChange(route.destination, oldBest.nextHop, newBest.nextHop))
                significantChange = true
            }

            if (!significantChange) {
                significantChange = isSignificantCostChange(oldBest?.cost, newBest?.cost)
            }
        }

        return if (significantChange) {
            RouteLearnResult.SignificantChange(routeChanges)
        } else {
            RouteLearnResult.NoSignificantChange
        }
    }

    // ── Gossip preparation ────────────────────────────────────────

    fun prepareGossipEntries(forPeerHex: String): List<GossipEntry> {
        return routingTable.allBestRoutes().mapNotNull { route ->
            when {
                route.nextHop == forPeerHex -> null // Split horizon
                previousNextHop[route.destination] == forPeerHex &&
                    route.nextHop != forPeerHex -> {
                    // Poison reverse: tell old next-hop the route is withdrawn
                    GossipEntry(route.destination, Double.MAX_VALUE, route.sequenceNumber)
                }
                else -> GossipEntry(route.destination, route.cost, route.sequenceNumber)
            }
        }
    }

    // ── Gossip timing ─────────────────────────────────────────────

    fun effectiveGossipInterval(powerMode: String): Long {
        val base = gossipIntervalMillis
        if (base <= 0) return 0
        val routeCount = routingTable.size()
        val routeMultiplied = when {
            routeCount > 200 -> base * 2
            routeCount > 100 -> base * 3 / 2
            else -> base
        }
        return when (powerMode) {
            "POWER_SAVER" -> routeMultiplied * 3
            "BALANCED" -> routeMultiplied * 2
            else -> routeMultiplied
        }
    }

    fun shouldSendTriggeredUpdate(peerHex: String, powerMode: String): Boolean {
        val interval = effectiveGossipInterval(powerMode)
        if (interval <= 0) return true
        val lastSent = lastTriggeredUpdateTime[peerHex] ?: return true
        return (clock() - lastSent) >= interval
    }

    fun recordTriggeredUpdate(peerHex: String) {
        lastTriggeredUpdateTime[peerHex] = clock()
    }

    fun recordGossipSent() {
        lastGossipSentMillis = clock()
    }

    fun timeSinceLastGossip(): Long = clock() - lastGossipSentMillis

    // ── Health ─────────────────────────────────────────────────────

    val routeCount: Int get() = routingTable.size()
    val peerCount: Int get() = presenceTracker.allPeerIds().size
    val connectedPeerCount: Int get() = presenceTracker.connectedPeerIds().size
    val dedupSize: Int get() = dedup.size()
    fun avgCost(): Double = routingTable.avgCost()

    // ── Next-hop reliability tracking ──────────────────────────────

    /** Record a delivery failure via a specific next-hop. */
    fun recordNextHopFailure(nextHopHex: String) {
        nextHopFailures[nextHopHex] = (nextHopFailures[nextHopHex] ?: 0) + 1
    }

    /** Record a delivery success via a specific next-hop. */
    fun recordNextHopSuccess(nextHopHex: String) {
        nextHopSuccesses[nextHopHex] = (nextHopSuccesses[nextHopHex] ?: 0) + 1
    }

    /** Failure rate for a next-hop (0.0–1.0), or 0.0 if no data. */
    fun nextHopFailureRate(nextHopHex: String): Double {
        val failures = nextHopFailures[nextHopHex] ?: 0
        val successes = nextHopSuccesses[nextHopHex] ?: 0
        val total = failures + successes
        if (total == 0) return 0.0
        return failures.toDouble() / total
    }

    /** Total recorded failures for a next-hop. */
    fun nextHopFailureCount(nextHopHex: String): Int = nextHopFailures[nextHopHex] ?: 0

    // ── Cleanup ────────────────────────────────────────────────────

    fun clear() {
        routingTable.clear()
        presenceTracker.clear()
        dedup.clear()
        previousNextHop.clear()
        lastTriggeredUpdateTime.clear()
        nextHopFailures.clear()
        nextHopSuccesses.clear()
        lastGossipSentMillis = 0L
    }

    fun clearDedup() {
        dedup.clear()
    }

    // ── Internal ───────────────────────────────────────────────────

    private fun isSignificantCostChange(oldCost: Double?, newCost: Double?): Boolean {
        if (oldCost == null && newCost != null) return true
        if (oldCost != null && newCost == null) return true
        if (oldCost == null || newCost == null) return false
        if (newCost == Double.MAX_VALUE || oldCost == Double.MAX_VALUE) return true
        if (oldCost == 0.0) return newCost > 0.0
        val ratio = kotlin.math.abs(newCost - oldCost) / oldCost
        return ratio > triggeredUpdateThreshold
    }
}
