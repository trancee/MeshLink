package ch.trancee.meshlink.transport

/**
 * Sliding-window backpressure tracker for GATT write latency.
 *
 * Records timestamps of slow writes (duration > [latencyThresholdMillis]) within a rolling
 * [windowMillis]-wide window. When [threshold] or more slow writes accumulate in the window,
 * [recordWrite] returns `true` signalling that write backpressure should be applied.
 *
 * @param windowMillis Width of the sliding window in milliseconds (default 7 s).
 * @param threshold Number of slow writes in the window that triggers backpressure (default 5).
 * @param latencyThresholdMillis Writes with duration strictly above this value count as slow
 *   (default 200 ms).
 * @param clock Monotonic time source returning epoch milliseconds; injected for testability.
 */
internal class WriteLatencyTracker(
    private val windowMillis: Long = 7_000L,
    private val threshold: Int = 5,
    private val latencyThresholdMillis: Long = 200L,
    private val clock: () -> Long,
) {
    /** Timestamps (in ms) of slow writes still within the current window. */
    private val entries = mutableListOf<Long>()

    /**
     * Records a completed write and returns whether backpressure should be applied.
     *
     * @param durationMillis How long the write took.
     * @return `true` if ≥ [threshold] slow writes have occurred within the last [windowMillis] ms.
     */
    fun recordWrite(durationMillis: Long): Boolean {
        val nowMillis = clock()
        entries.removeAll { nowMillis - it > windowMillis }
        if (durationMillis > latencyThresholdMillis) entries.add(nowMillis)
        return entries.size >= threshold
    }

    /** Clears all accumulated entries, resetting the backpressure state. */
    fun reset() {
        entries.clear()
    }
}
