package io.meshlink.routing

import io.meshlink.util.ByteArrayKey

/**
 * Bounded deduplication set with time-windowed TTL expiry.
 * Returns true if the ID is new (accepted), false if duplicate (rejected).
 *
 * Entries expire after [ttlMillis] milliseconds to prevent targeted dedup-eviction attacks.
 * A hard [capacity] cap is still enforced as a safety net.
 */
class DedupSet(
    private val capacity: Int = 100_000,
    private val ttlMillis: Long = 300_000L,
    private val clock: () -> Long = { 0L },
) {

    private val seen = LinkedHashMap<ByteArrayKey, Long>()

    fun tryInsert(id: ByteArrayKey): Boolean {
        sweepExpired()
        val existingTs = seen[id]
        if (existingTs != null) {
            // Move to end (most recently used)
            seen.remove(id)
            seen[id] = clock()
            return false
        }
        if (seen.size >= capacity) {
            seen.remove(seen.keys.first())
        }
        seen[id] = clock()
        return true
    }

    fun size(): Int = seen.size

    fun clear() = seen.clear()

    private fun sweepExpired() {
        val now = clock()
        val iter = seen.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if ((now - entry.value) >= ttlMillis) {
                iter.remove()
            } else {
                break // LinkedHashMap is insertion-ordered; older entries are first
            }
        }
    }
}
