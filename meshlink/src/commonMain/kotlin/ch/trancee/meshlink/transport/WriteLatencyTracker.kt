package ch.trancee.meshlink.transport

/**
 * Sliding-window backpressure tracker for GATT write latency.
 *
 * Records timestamps of slow writes (duration > [latencyThresholdMs]) within a rolling
 * [windowMs]-wide window. When [threshold] or more slow writes accumulate in the window,
 * [recordWrite] returns `true` signalling that write backpressure should be applied.
 *
 * @param windowMs Width of the sliding window in milliseconds (default 7 s).
 * @param threshold Number of slow writes in the window that triggers backpressure (default 5).
 * @param latencyThresholdMs Writes with duration strictly above this value count as slow (default
 *   200 ms).
 * @param clock Monotonic time source returning epoch milliseconds; injected for testability.
 */
internal class WriteLatencyTracker(
    private val windowMs: Long = 7_000L,
    private val threshold: Int = 5,
    private val latencyThresholdMs: Long = 200L,
    private val clock: () -> Long,
) {
    /** Timestamps (in ms) of slow writes still within the current window. */
    private val entries = mutableListOf<Long>()

    /**
     * Records a completed write and returns whether backpressure should be applied.
     *
     * @param durationMs How long the write took.
     * @return `true` if ≥ [threshold] slow writes have occurred within the last [windowMs] ms.
     */
    fun recordWrite(durationMs: Long): Boolean {
        val nowMs = clock()
        entries.removeAll { nowMs - it > windowMs }
        if (durationMs > latencyThresholdMs) entries.add(nowMs)
        return entries.size >= threshold
    }

    /** Clears all accumulated entries, resetting the backpressure state. */
    fun reset() {
        entries.clear()
    }
}
