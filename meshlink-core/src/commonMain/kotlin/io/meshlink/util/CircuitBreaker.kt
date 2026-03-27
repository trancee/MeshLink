package io.meshlink.util

class CircuitBreaker(
    private val maxFailures: Int,
    private val windowMs: Long,
    private val cooldownMs: Long,
    private val clock: () -> Long = { currentTimeMillis() },
) {
    private val failures = mutableListOf<Long>()
    private var trippedAt: Long? = null

    fun allowAttempt(): Boolean {
        val now = clock()
        val trip = trippedAt
        if (trip != null) {
            return if (now - trip >= cooldownMs) {
                trippedAt = null
                failures.clear()
                true
            } else false
        }
        return true
    }

    fun recordFailure() {
        val now = clock()
        failures.add(now)
        failures.removeAll { now - it > windowMs }
        if (failures.size >= maxFailures) {
            trippedAt = now
        }
    }
}
