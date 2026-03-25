package io.meshlink.util

class RateLimiter(
    private val maxEvents: Int,
    private val windowMs: Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    // key → list of timestamps within window
    private val events = mutableMapOf<String, MutableList<Long>>()

    fun tryAcquire(key: String): Boolean {
        val now = clock()
        val timestamps = events.getOrPut(key) { mutableListOf() }
        // Prune expired events
        timestamps.removeAll { now - it > windowMs }
        if (timestamps.size >= maxEvents) return false
        timestamps.add(now)
        return true
    }
}
