package ch.trancee.meshlink.transport

/**
 * Finite state machine for L2CAP channel open retry scheduling.
 *
 * Provides up to 3 retry delays (60 s, 120 s, 300 s) for failed L2CAP open attempts. Once all
 * retries are exhausted [nextDelayMillis] returns `null` and [isExhausted] becomes `true`. Call
 * [reset] to restart the sequence after a successful connection.
 */
internal class L2capRetryScheduler {

    private val delays = longArrayOf(60_000L, 120_000L, 300_000L)
    private var index = 0

    /**
     * Returns the next retry delay in milliseconds, or `null` when all retries are exhausted.
     *
     * Sequence: 60_000 → 120_000 → 300_000 → null.
     */
    fun nextDelayMillis(): Long? {
        if (index >= delays.size) return null
        return delays[index++]
    }

    /** Resets the retry index so the sequence restarts from 60 s. */
    fun reset() {
        index = 0
    }

    /**
     * `true` when all three retries have been consumed and the next call to [nextDelayMillis] would
     * return `null`.
     */
    val isExhausted: Boolean
        get() = index >= delays.size
}
