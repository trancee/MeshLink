package io.meshlink.routing

import kotlin.math.min

/**
 * Computes adaptive gossip interval based on routing table size.
 * When the routing table exceeds a threshold, the gossip interval scales
 * linearly with table size, capped at a maximum multiplier.
 */
class GossipThrottle(
    private val baseIntervalMs: Long,
    private val scaleThreshold: Int = 100,
    private val maxMultiplier: Double = 5.0,
) {
    /**
     * Compute the effective gossip interval for the given route count.
     * - Below threshold: returns baseIntervalMs
     * - Above threshold: returns baseIntervalMs × (routeCount / threshold), capped at maxMultiplier × baseIntervalMs
     * - If baseIntervalMs <= 0: returns 0 (gossip disabled)
     */
    fun effectiveInterval(routeCount: Int): Long {
        if (baseIntervalMs <= 0L) return 0L
        return (baseIntervalMs * multiplier(routeCount)).toLong()
    }

    /**
     * Current multiplier for the given route count (1.0 when below threshold).
     */
    fun multiplier(routeCount: Int): Double {
        if (routeCount <= scaleThreshold) return 1.0
        val raw = routeCount.toDouble() / scaleThreshold.toDouble()
        return min(raw, maxMultiplier)
    }
}
