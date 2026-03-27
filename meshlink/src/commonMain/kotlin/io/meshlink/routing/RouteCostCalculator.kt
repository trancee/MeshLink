package io.meshlink.routing

import io.meshlink.routing.RoutingTable.Companion.MAX_ROUTE_COST

/**
 * Computes a composite route cost from signal strength, reliability,
 * freshness, and stability metrics.
 *
 * Formula: cost = RSSI_base × loss_multiplier × freshness_penalty + stability_penalty
 */
class RouteCostCalculator(
    val freshnessHalfLifeMs: Long = 60_000L,
    val stabilityPenaltyPerFlap: Double = 0.1,
) {
    init {
        require(freshnessHalfLifeMs > 0) { "freshnessHalfLifeMs must be positive" }
        require(stabilityPenaltyPerFlap >= 0.0) { "stabilityPenaltyPerFlap must be non-negative" }
    }

    /**
     * Calculate composite route cost.
     *
     * @param rssi           Received signal strength in dBm (clamped to [-100, -20])
     * @param packetLossRate Fraction of lost packets, 0.0–1.0 (clamped to [0.0, 0.99])
     * @param ageMs          Age of the route in milliseconds (clamped to >= 0)
     * @param flapCount      Number of route flaps (clamped to >= 0)
     * @return composite cost, always in (0.0, MAX_ROUTE_COST]
     */
    fun calculateCost(
        rssi: Int,
        packetLossRate: Double,
        ageMs: Long,
        flapCount: Int,
    ): Double {
        val clampedRssi = rssi.coerceIn(RSSI_MIN, RSSI_MAX)
        val clampedLoss = packetLossRate.coerceIn(0.0, MAX_LOSS_RATE)
        val clampedAge = ageMs.coerceAtLeast(0L)
        val clampedFlaps = flapCount.coerceAtLeast(0)

        // RSSI_base: normalize to 0.0-1.0 where weaker signal = higher cost
        // -20 dBm (strongest) → 0.0 base, -100 dBm (weakest) → 1.0 base
        // Shift so strongest is near-zero but still positive after multipliers
        val rssiBase = (clampedRssi - RSSI_MAX).toDouble() / (RSSI_MIN - RSSI_MAX).toDouble()

        // loss_multiplier: no loss = 1.0, 50% loss = 2.0, 99% loss = 100.0
        val lossMultiplier = 1.0 / (1.0 - clampedLoss)

        // freshness_penalty: brand-new = 1.0, one half-life old = 2.0
        val freshnessPenalty = 1.0 + (clampedAge.toDouble() / freshnessHalfLifeMs.toDouble())

        // stability_penalty: additive cost for each flap
        val stabilityPenalty = clampedFlaps * stabilityPenaltyPerFlap

        val raw = rssiBase * lossMultiplier * freshnessPenalty + stabilityPenalty

        // Guarantee positive, finite, and bounded
        return raw.coerceIn(MIN_COST, MAX_ROUTE_COST)
    }

    companion object {
        const val RSSI_MIN = -100
        const val RSSI_MAX = -20
        const val MAX_LOSS_RATE = 0.99
        private const val MIN_COST = 1e-9
    }
}
