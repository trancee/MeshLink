package io.meshlink.util

class CircuitBreaker(
    private val maxFailures: Int,
    private val windowMs: Long,
    private val cooldownMs: Long,
    private val clock: () -> Long = { currentTimeMillis() },
) {
    private var failures = listOf<Long>()
    private var trippedAt: Long? = null

    fun allowAttempt(): Boolean {
        val now = clock()
        val trip = trippedAt
        if (trip != null) {
            return if (now - trip >= cooldownMs) {
                trippedAt = null
                failures = emptyList()
                true
            } else false
        }
        return true
    }

    fun recordFailure() {
        val now = clock()
        val pruned = failures.filter { now - it <= windowMs } + now
        failures = pruned
        if (pruned.size >= maxFailures) {
            trippedAt = now
        }
    }
}
