package io.meshlink.routing

/**
 * Bounded deduplication set with LRU eviction.
 * Returns true if the ID is new (accepted), false if duplicate (rejected).
 */
class DedupSet(private val capacity: Int = 10_000) {

    // KMP-compatible LRU: remove+re-insert to move accessed keys to the end
    private val seen = LinkedHashMap<String, Boolean>()

    fun tryInsert(id: String): Boolean {
        if (seen.containsKey(id)) {
            // Move to end (most recently used)
            seen.remove(id)
            seen[id] = true
            return false
        }
        if (seen.size >= capacity) {
            seen.remove(seen.keys.first())
        }
        seen[id] = true
        return true
    }

    fun size(): Int = seen.size

    fun clear() = seen.clear()
}
