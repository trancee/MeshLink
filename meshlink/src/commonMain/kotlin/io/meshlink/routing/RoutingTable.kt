package io.meshlink.routing

import io.meshlink.util.ByteArrayKey
import io.meshlink.util.currentTimeMillis

/**
 * AODV route cache.
 * Stores routes to mesh destinations with composite cost metric,
 * primary + backup route support, and TTL-based expiry.
 */
class RoutingTable(
    private val expiryMillis: Long = Long.MAX_VALUE,
    private val neighborCapFraction: Double = 1.0,
    private val maxDestinations: Int = Int.MAX_VALUE,
) {

    data class Route(
        val destination: ByteArrayKey,
        val nextHop: ByteArrayKey,
        val cost: Double,
        val sequenceNumber: UInt,
        val timestamp: Long = currentTime(),
    )

    // destination → (nextHop → Route)
    private val routes = mutableMapOf<ByteArrayKey, MutableMap<ByteArrayKey, Route>>()

    fun addRoute(destination: ByteArrayKey, nextHop: ByteArrayKey, cost: Double, sequenceNumber: UInt) {
        // Sanity validation
        if (cost < 0.0 || cost.isNaN()) return
        if (cost.isInfinite()) return // Use Double.MAX_VALUE for withdrawals, not Infinity
        if (cost > MAX_ROUTE_COST && cost != Double.MAX_VALUE) return

        val existing = routes[destination]
        if (existing != null) {
            val maxSeqNum = existing.values.maxOf { it.sequenceNumber }
            if (sequenceNumber < maxSeqNum) return
            if (sequenceNumber > maxSeqNum) {
                existing.clear()
            }
        }

        // Per-neighbor cap: count distinct destinations this neighbor announces
        if (existing?.containsKey(nextHop) != true) {
            val cap = (maxDestinations * neighborCapFraction).toInt().coerceAtLeast(1)
            val neighborDestCount = routes.count { (_, nextHops) -> nextHops.containsKey(nextHop) }
            if (neighborDestCount >= cap) return // reject excess
        }

        val now = currentTime()
        val route = Route(destination, nextHop, cost, sequenceNumber, now)
        routes.getOrPut(destination) { mutableMapOf() }[nextHop] = route
    }

    fun bestRoute(destination: ByteArrayKey): Route? {
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

    fun removeRoute(destination: ByteArrayKey, nextHop: ByteArrayKey) {
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
