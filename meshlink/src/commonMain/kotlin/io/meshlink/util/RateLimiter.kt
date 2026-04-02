package io.meshlink.util

class RateLimiter(
    private val maxEvents: Int,
    private val windowMillis: Long,
    private val clock: () -> Long = { currentTimeMillis() },
) {
    private val events = mutableMapOf<ByteArrayKey, ArrayDeque<Long>>()

    fun tryAcquire(key: ByteArrayKey): Boolean {
        val now = clock()
        val timestamps = events.getOrPut(key) { ArrayDeque() }
        while (timestamps.isNotEmpty() && now - timestamps.first() > windowMillis) {
            timestamps.removeFirst()
        }
        if (timestamps.size >= maxEvents) return false
        timestamps.addLast(now)
        return true
    }
}
