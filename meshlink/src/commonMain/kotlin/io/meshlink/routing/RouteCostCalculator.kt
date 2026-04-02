package io.meshlink.routing

import io.meshlink.util.ByteArrayKey
import io.meshlink.util.currentTimeMillis
import kotlin.math.max
import kotlin.math.round

/**
 * Composite link quality metric inspired by BATMAN's Transmit Quality (TQ)
 * and Babel's Expected Transmission Count (ETX).
 *
 * Per-link cost formula:
 *   link_cost = RSSI_base_cost × loss_rate_multiplier × freshness_penalty + stability_penalty
 *
 * Cost range: ~1 (excellent/reliable/stable) to ~30 (marginal+loss+new).
 */
class RouteCostCalculator(
    private val clock: () -> Long = { currentTimeMillis() },
    private val keepaliveIntervalMillis: Long = 15_000L,
    private val routeCostChangeThreshold: Double = 0.30,
) {
    /**
     * Per-peer link quality state, updated via [recordMeasurement] and [recordStableInterval].
     */
    data class LinkState(
        var rssi: Int = -70,
        var lossRate: Double = 0.0,
        var lastMeasurementMillis: Long = 0L,
        var consecutiveStableIntervals: Int = 0,
        var lastCost: Double = Double.NaN,
    )

    private val links = mutableMapOf<ByteArrayKey, LinkState>()

    // ── Public API ────────────────────────────────────────────────

    /**
     * Record an RSSI measurement and loss rate for a peer link.
     * Loss rate is computed from chunk ACK retransmission ratio on active transfers.
     */
    fun recordMeasurement(peerId: ByteArrayKey, rssi: Int, lossRate: Double) {
        val state = links.getOrPut(peerId) { LinkState(lastMeasurementMillis = clock()) }
        state.rssi = rssi
        state.lossRate = lossRate.coerceIn(0.0, 1.0)
        state.lastMeasurementMillis = clock()
    }

    /**
     * Record that a keepalive interval passed without GATT disconnects.
     * After 3 consecutive stable intervals, the stability penalty decays to 0.
     */
    fun recordStableInterval(peerId: ByteArrayKey) {
        val state = links[peerId] ?: return
        state.consecutiveStableIntervals = (state.consecutiveStableIntervals + 1).coerceAtMost(3)
    }

    /**
     * Record a GATT disconnect (link flap). Resets stability counter.
     */
    fun recordDisconnect(peerId: ByteArrayKey) {
        val state = links[peerId] ?: return
        state.consecutiveStableIntervals = 0
    }

    /**
     * Compute the composite link cost for a peer.
     *
     * Components:
     *   1. RSSI base cost: max(1, round((-RSSI - 50) / 5))
     *   2. Loss rate multiplier: 1 + (lossRate × 10)
     *   3. Freshness penalty: 1.0 if recent, 1.5 if stale
     *   4. Stability penalty: max(0, 3 - consecutiveStableIntervals)
     *
     * Returns [DEFAULT_COST] if no measurement data is available.
     */
    fun computeCost(peerId: ByteArrayKey): Double {
        val state = links[peerId] ?: return DEFAULT_COST

        // 1. RSSI base cost (linear formula)
        val baseCost = max(1.0, round((-state.rssi - 50).toDouble() / 5.0))

        // 2. Loss rate multiplier
        val lossMultiplier = 1.0 + (state.lossRate * 10.0)

        // 3. Freshness penalty
        val age = clock() - state.lastMeasurementMillis
        val freshnessThreshold = keepaliveIntervalMillis * 2
        val freshnessPenalty = if (age <= freshnessThreshold) 1.0 else 1.5

        // 4. Stability penalty (new links start at +3, decays to 0)
        val stabilityPenalty = max(0, 3 - state.consecutiveStableIntervals).toDouble()

        val cost = baseCost * lossMultiplier * freshnessPenalty + stabilityPenalty

        // Apply change threshold dampening
        if (!state.lastCost.isNaN()) {
            val change = kotlin.math.abs(cost - state.lastCost) / state.lastCost
            if (change < routeCostChangeThreshold) {
                return state.lastCost
            }
        }
        state.lastCost = cost
        return cost
    }

    /**
     * Remove link state for a peer (gone from mesh).
     */
    fun removePeer(peerId: ByteArrayKey) {
        links.remove(peerId)
    }

    /**
     * Clear all link state.
     */
    fun clear() {
        links.clear()
    }

    /**
     * Returns true if we have any measurement data for this peer.
     */
    fun hasData(peerId: ByteArrayKey): Boolean = links.containsKey(peerId)

    companion object {
        /** Default cost when no link quality data is available (equivalent to hop count). */
        const val DEFAULT_COST = 1.0
    }
}
