package ch.trancee.meshlink.routing

class DedupSet(
    private val capacity: Int,
    private val ttlMs: Long,
    private val getCurrentTimeMs: () -> Long,
) {
    private val entries: LinkedHashMap<List<Byte>, Long> = LinkedHashMap()

    val size: Int get() = entries.size

    fun isDuplicate(messageId: ByteArray): Boolean {
        val key = messageId.asList()
        val storedTimestamp = entries[key] ?: return false
        if (getCurrentTimeMs() - storedTimestamp > ttlMs) {
            entries.remove(key)
            return false
        }
        // LRU: remove and re-insert to move to end of LinkedHashMap
        entries.remove(key)
        entries[key] = storedTimestamp
        return true
    }

    fun add(messageId: ByteArray) {
        val key = messageId.asList()
        entries[key] = getCurrentTimeMs()
        while (entries.size > capacity) {
            entries.remove(entries.keys.first())
        }
    }
}
