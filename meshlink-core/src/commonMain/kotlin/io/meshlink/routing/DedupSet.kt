package io.meshlink.routing

/**
 * Bounded deduplication set with LRU eviction.
 * Returns true if the ID is new (accepted), false if duplicate (rejected).
 */
class DedupSet(private val capacity: Int = 10_000) {

    // LinkedHashMap with accessOrder=true gives LRU eviction for free
    private val seen = object : LinkedHashMap<String, Boolean>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean {
            return size > capacity
        }
    }

    fun tryInsert(id: String): Boolean {
        if (seen.containsKey(id)) {
            seen[id] = true // refresh access order
            return false
        }
        seen[id] = true
        return true
    }
}
