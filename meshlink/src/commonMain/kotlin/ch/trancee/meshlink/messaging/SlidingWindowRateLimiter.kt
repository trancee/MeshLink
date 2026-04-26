package ch.trancee.meshlink.messaging

/**
 * True sliding-window rate limiter backed by a [LongArray] circular buffer of event timestamps.
 * O(1) amortised per [tryAcquire] call; no heap allocations after construction.
 *
 * @param limit Maximum events allowed within [windowMillis]. A limit of 0 always rejects.
 * @param windowMillis Width of the sliding window in milliseconds. A window of 0 ms means every
 *   previously recorded entry is immediately expired, so [tryAcquire] effectively allows every call
 *   (up to [limit] per "instant").
 * @param clock Monotonic timestamp source; must be non-decreasing. Defaults to the platform wall
 *   clock but should be injected for deterministic tests.
 */
class SlidingWindowRateLimiter(
    private val limit: Int,
    private val windowMillis: Long,
    private val clock: () -> Long,
) {
    // Sized to at least 1 so the modulo arithmetic never divides by zero.
    // When limit == 0 the array is never indexed because the capacity check
    // (count >= limit, i.e. 0 >= 0) short-circuits to false immediately.
    private val timestamps = LongArray(maxOf(limit, 1))
    private var head = 0
    private var count = 0

    /**
     * Attempts to record one event.
     *
     * Returns `true` if the event is within the rate limit (and records it); `false` if the limit
     * has been reached.
     */
    fun tryAcquire(): Boolean {
        val now = clock()

        // Evict entries that have fallen outside the sliding window.
        while (count > 0 && now - timestamps[head] >= windowMillis) {
            head = (head + 1) % timestamps.size
            count--
        }

        if (count >= limit) return false

        val tail = (head + count) % timestamps.size
        timestamps[tail] = now
        count++
        return true
    }
}
