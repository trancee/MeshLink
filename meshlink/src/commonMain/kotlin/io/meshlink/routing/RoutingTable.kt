package io.meshlink.routing

import io.meshlink.util.currentTimeMillis

/**
 * Enhanced DSDV routing table.
 * Stores routes to mesh destinations with composite cost metric,
 * primary + backup route support, and expiry.
 */
class RoutingTable(
    private val expiryMillis: Long = Long.MAX_VALUE,
    private val neighborCapFraction: Double = 1.0,
    private val maxDestinations: Int = Int.MAX_VALUE,
    private val settlingMillis: Long = 0,
    private val holddownMillis: Long = 0,
) {

    data class Route(
        val destination: String,
        val nextHop: String,
        val cost: Double,
        val sequenceNumber: UInt,
        val timestamp: Long = currentTime(),
    )

    // destination → (nextHop → Route)
    private val routes = mutableMapOf<String, MutableMap<String, Route>>()

    // Track when a destination entered holddown (route withdrawn with infinity cost)
    private val holddownUntil = mutableMapOf<String, Long>()

    // Track when a destination last had a route change (for settling)
    private val lastRouteChange = mutableMapOf<String, Long>()

    fun addRoute(destination: String, nextHop: String, cost: Double, sequenceNumber: UInt) {
        // Sanity validation
        if (cost < 0.0 || cost.isNaN()) return
        if (cost.isInfinite()) return // Use Double.MAX_VALUE for withdrawals, not Infinity
        if (cost > MAX_ROUTE_COST && cost != Double.MAX_VALUE) return

        val now = currentTime()
        val existing = routes[destination]
        if (existing != null) {
            val maxSeqNum = existing.values.maxOf { it.sequenceNumber }
            if (sequenceNumber < maxSeqNum) return
            if (sequenceNumber > maxSeqNum) {
                existing.clear()
                lastRouteChange[destination] = now
            }
        }

        // Per-neighbor cap: count distinct destinations this neighbor announces
        if (existing?.containsKey(nextHop) != true) {
            val cap = (maxDestinations * neighborCapFraction).toInt().coerceAtLeast(1)
            val neighborDestCount = routes.count { (_, nextHops) -> nextHops.containsKey(nextHop) }
            if (neighborDestCount >= cap) return // reject excess
        }

        val route = Route(destination, nextHop, cost, sequenceNumber, now)
        routes.getOrPut(destination) { mutableMapOf() }[nextHop] = route

        // If this is a withdrawal (infinity cost), start holddown
        if (cost == Double.MAX_VALUE && holddownMillis > 0) {
            holddownUntil[destination] = now + holddownMillis
        }

        // Track settling time
        if (settlingMillis > 0) {
            lastRouteChange[destination] = now
        }
    }

    fun bestRoute(destination: String): Route? {
        val now = currentTime()
        val destRoutes = routes[destination] ?: return null
        // Remove expired routes
        destRoutes.values.removeAll { now - it.timestamp > expiryMillis }
        if (destRoutes.isEmpty()) {
            routes.remove(destination)
            return null
        }
        return destRoutes.values.minByOrNull { it.cost }
    }

    fun removeRoute(destination: String, nextHop: String) {
        val destRoutes = routes[destination] ?: return
        destRoutes.remove(nextHop)
        if (destRoutes.isEmpty()) routes.remove(destination)
    }

    fun avgCost(): Double {
        val allRoutes = routes.values.flatMap { it.values }
        if (allRoutes.isEmpty()) return 0.0
        return allRoutes.map { it.cost }.average()
    }

    fun allBestRoutes(): List<Route> {
        return routes.keys.mapNotNull { bestRoute(it) }
    }

    fun clear() {
        routes.clear()
    }

    fun size(): Int = routes.size

    companion object {
        const val MAX_ROUTE_COST = 1_000_000.0
        internal var currentTime: () -> Long = { currentTimeMillis() }
    }
}
