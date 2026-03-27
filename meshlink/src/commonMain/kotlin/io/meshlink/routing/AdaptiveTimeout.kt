package io.meshlink.routing

import io.meshlink.util.currentTimeMillis

/**
 * Adaptive per-peer timeout based on observed advertisement intervals.
 *
 * Each peer advertises at its own cadence. [AdaptiveTimeout] tracks sighting
 * timestamps, computes an EWMA of inter-sighting intervals, and derives a
 * timeout = clamp(observedInterval × [multiplier], [minTimeoutMs], [maxTimeoutMs]).
 */
class AdaptiveTimeout(
    private val minTimeoutMs: Long = 5_000L,
    private val maxTimeoutMs: Long = 120_000L,
    private val multiplier: Double = 3.0,
    private val alpha: Double = 0.3,
    private val defaultTimeoutMs: Long = 30_000L,
) {
    private class PeerState(
        var lastSightingMs: Long,
        var ewmaIntervalMs: Double? = null,
    )

    private val peers = mutableMapOf<String, PeerState>()

    /**
     * Record a sighting of [peerId] at [timestampMs].
     * Updates the EWMA interval when a previous sighting exists.
     */
    fun recordSighting(peerId: String, timestampMs: Long = currentTimeMillis()) {
        val state = peers[peerId]
        if (state == null) {
            peers[peerId] = PeerState(lastSightingMs = timestampMs)
        } else {
            val delta = timestampMs - state.lastSightingMs
            if (delta > 0) {
                val prev = state.ewmaIntervalMs
                state.ewmaIntervalMs = if (prev == null) {
                    delta.toDouble()
                } else {
                    alpha * delta + (1 - alpha) * prev
                }
            }
            state.lastSightingMs = timestampMs
        }
    }

    /**
     * Returns the adaptive timeout for [peerId] in milliseconds.
     * Returns [defaultTimeoutMs] if no interval has been observed yet.
     */
    fun getTimeout(peerId: String): Long {
        val ewma = peers[peerId]?.ewmaIntervalMs
            ?: return defaultTimeoutMs.coerceIn(minTimeoutMs, maxTimeoutMs)
        val raw = (ewma * multiplier).toLong()
        return raw.coerceIn(minTimeoutMs, maxTimeoutMs)
    }

    /** Remove all state for [peerId]. */
    fun evict(peerId: String) {
        peers.remove(peerId)
    }
}
