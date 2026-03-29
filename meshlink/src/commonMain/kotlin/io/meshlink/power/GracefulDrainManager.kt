package io.meshlink.power

import io.meshlink.util.currentTimeMillis

/**
 * Manages graceful drain during power mode transitions.
 * Tracks peers with active transfers and provides a grace period
 * before disconnecting them when connection limits decrease.
 */
class GracefulDrainManager(
    private val graceMillis: Long = 30_000L,
    private val clock: () -> Long = { currentTimeMillis() },
) {
    data class DrainEntry(
        val peerIdHex: String,
        val startedAtMillis: Long,
        val reason: String,
    )

    private val draining = mutableMapOf<String, DrainEntry>()

    /** Mark a peer as draining (has active transfers, needs grace period). */
    fun startDrain(peerIdHex: String, reason: String = "power_mode_downgrade") {
        if (peerIdHex !in draining) {
            draining[peerIdHex] = DrainEntry(peerIdHex, clock(), reason)
        }
    }

    /** Check if a peer is in the grace period (should NOT be disconnected yet). */
    fun isInGracePeriod(peerIdHex: String): Boolean {
        val entry = draining[peerIdHex] ?: return false
        return clock() - entry.startedAtMillis < graceMillis
    }

    /** Get all peers whose grace period has expired (ready to disconnect). */
    fun expiredPeers(): List<String> {
        val now = clock()
        return draining.values
            .filter { now - it.startedAtMillis >= graceMillis }
            .map { it.peerIdHex }
    }

    /** Remove a peer from drain tracking (transfer completed or peer disconnected). */
    fun complete(peerIdHex: String) {
        draining.remove(peerIdHex)
    }

    /** Get all peers currently in drain state. */
    fun drainingPeers(): List<DrainEntry> = draining.values.toList()

    /** Clear all drain state. */
    fun clear() {
        draining.clear()
    }

    /** Number of peers in drain state. */
    fun size(): Int = draining.size
}
