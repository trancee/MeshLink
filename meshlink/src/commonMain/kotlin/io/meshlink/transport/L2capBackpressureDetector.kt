package io.meshlink.transport

/**
 * Detects back-pressure on an L2CAP channel by tracking write latency.
 *
 * If [slowThreshold] or more writes exceed [slowWriteMillis] within a
 * sliding [windowMillis]-millisecond window, the detector signals that the
 * channel is congested and the caller should fall back to GATT.
 */
class L2capBackpressureDetector(
    private val slowWriteMillis: Long = 100L,
    private val slowThreshold: Int = 3,
    private val windowMillis: Long = 7_000L,
    private val clock: () -> Long = { io.meshlink.util.currentTimeMillis() },
) {
    private val slowWrites = mutableListOf<Long>()

    /**
     * Record the [durationMillis] of a completed write.
     *
     * @return `true` if back-pressure is detected (too many slow writes
     *   within the sliding window), `false` otherwise.
     */
    fun recordWrite(durationMillis: Long): Boolean {
        val now = clock()
        if (durationMillis > slowWriteMillis) {
            slowWrites.add(now)
        }
        // Evict entries older than the window
        val cutoff = now - windowMillis
        slowWrites.removeAll { it <= cutoff }
        return slowWrites.size >= slowThreshold
    }

    /** Reset all recorded write history. */
    fun reset() {
        slowWrites.clear()
    }
}
