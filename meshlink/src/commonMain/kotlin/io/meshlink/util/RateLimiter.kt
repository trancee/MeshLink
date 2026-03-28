package io.meshlink.util

class RateLimiter(
    private val maxEvents: Int,
    private val windowMs: Long,
    private val clock: () -> Long = { currentTimeMillis() },
) {
    // key → list of timestamps within window
    // Uses replace-on-write to avoid concurrent mutation crashes on Kotlin/Native
    private val events = mutableMapOf<String, List<Long>>()

    fun tryAcquire(key: String): Boolean {
        val now = clock()
        val pruned = (events[key] ?: emptyList()).filter { now - it <= windowMs }
        if (pruned.size >= maxEvents) {
            events[key] = pruned
            return false
        }
        events[key] = pruned + now
        return true
    }
}
