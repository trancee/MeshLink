package ch.trancee.meshlink.util

/**
 * Fixed-capacity map with LRU eviction. On overflow the least-recently-used entry is removed.
 *
 * Used for per-peer rate limiters, circuit breakers, and other maps that grow linearly with unique
 * peers observed over time. Without eviction these maps leak memory indefinitely.
 *
 * Implementation uses a [LinkedHashMap] with access-order iteration. On each [getOrPut] call the
 * accessed entry is moved to the tail (most-recently-used). When size exceeds [maxSize] the head
 * (least-recently-used) is evicted.
 *
 * @param maxSize Maximum entries before the eldest is evicted.
 */
internal class LruMap<K, V>(private val maxSize: Int) {

    // accessOrder = true not available in Kotlin common; we simulate by remove-and-reinsert.
    private val map = LinkedHashMap<K, V>()

    val size: Int
        get() = map.size

    operator fun get(key: K): V? {
        val value = map[key] ?: return null
        // Move to end (most recently used)
        map.remove(key)
        map[key] = value
        return value
    }

    operator fun set(key: K, value: V) {
        map.remove(key) // ensure re-insert moves to end
        map[key] = value
        evictIfNeeded()
    }

    fun getOrPut(key: K, defaultValue: () -> V): V {
        val existing = map[key]
        if (existing != null) {
            // Move to end (most recently used)
            map.remove(key)
            map[key] = existing
            return existing
        }
        val value = defaultValue()
        map[key] = value
        evictIfNeeded()
        return value
    }

    fun remove(key: K): V? = map.remove(key)

    fun containsKey(key: K): Boolean = map.containsKey(key)

    private fun evictIfNeeded() {
        while (map.size > maxSize) {
            val eldest = map.keys.iterator().next()
            map.remove(eldest)
        }
    }
}
