package ch.trancee.meshlink.power

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class GracefulDrainManager(
    private val clock: () -> Long,
    private val config: PowerConfig,
    private val scope: CoroutineScope,
) {
    private class GraceEntry(
        val deadline: Long,
        val job: Job,
        val managedConnection: ManagedConnection,
        val onEvict: (ByteArray) -> Unit,
    )

    private val graceTimers = mutableMapOf<PeerKey, GraceEntry>()

    /**
     * Evicts each connection in [connectionsToEvict]. Idle/stalled connections are evicted
     * immediately via [onEvict]. Active connections receive a grace period before eviction.
     *
     * If a grace timer already exists for a peer it is cancelled and replaced.
     */
    fun drain(connectionsToEvict: List<ManagedConnection>, onEvict: (ByteArray) -> Unit) {
        for (connection in connectionsToEvict) {
            val ts = connection.transferStatus
            if (ts == null || ts.bytesPerSecond < config.minThroughputBytesPerSec) {
                // Idle or stalled: evict immediately.
                val key = PeerKey(connection.peerId)
                graceTimers.remove(key)?.job?.cancel()
                onEvict(connection.peerId)
            } else {
                // Active transfer: compute a grace period proportional to the estimated
                // remaining transfer time, clamped to [evictionGracePeriodMs,
                // maxEvictionGracePeriodMs].
                val estimatedRemainingMs =
                    ((connection.transferStatus.remainingBytes.toDouble() /
                            connection.transferStatus.bytesPerSecond) * 1000.0)
                        .toLong()
                val graceMs =
                    minOf(
                        config.maxEvictionGracePeriodMs,
                        maxOf(config.evictionGracePeriodMs, estimatedRemainingMs),
                    )
                val deadline = clock() + graceMs
                val key = PeerKey(connection.peerId)
                // Cancel any existing timer for this peer before starting a new one.
                graceTimers.remove(key)?.job?.cancel()
                val job = scope.launch {
                    delay(graceMs)
                    graceTimers.remove(key)
                    onEvict(connection.peerId)
                }
                graceTimers[key] =
                    GraceEntry(
                        deadline = deadline,
                        job = job,
                        managedConnection = connection,
                        onEvict = onEvict,
                    )
            }
        }
    }

    /**
     * Cancels all active grace timers and immediately evicts every in-grace peer via their stored
     * [onEvict] callback.
     */
    fun cancelAllGrace() {
        val entries = graceTimers.values.toList()
        graceTimers.clear()
        for (entry in entries) {
            entry.job.cancel()
            entry.onEvict(entry.managedConnection.peerId)
        }
    }

    /** Returns the number of connections currently waiting in a grace period. */
    fun activeGraceCount(): Int = graceTimers.size
}
