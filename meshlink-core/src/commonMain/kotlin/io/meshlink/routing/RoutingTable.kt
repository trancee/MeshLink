package io.meshlink.routing

/**
 * Enhanced DSDV routing table.
 * Stores routes to mesh destinations with composite cost metric,
 * primary + backup route support, and expiry.
 */
class RoutingTable(
    private val expiryMs: Long = Long.MAX_VALUE,
    private val neighborCapFraction: Double = 1.0,
    private val maxDestinations: Int = Int.MAX_VALUE,
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

    fun addRoute(destination: String, nextHop: String, cost: Double, sequenceNumber: UInt) {
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

        val route = Route(destination, nextHop, cost, sequenceNumber)
        routes.getOrPut(destination) { mutableMapOf() }[nextHop] = route
    }

    fun bestRoute(destination: String): Route? {
        val now = currentTime()
        val destRoutes = routes[destination] ?: return null
        // Remove expired routes
        destRoutes.values.removeAll { now - it.timestamp > expiryMs }
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

    companion object {
        internal var currentTime: () -> Long = { System.currentTimeMillis() }
    }
}
