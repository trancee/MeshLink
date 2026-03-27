package io.meshlink.util

/**
 * Time-bounded set that tracks message IDs for a configurable window.
 * Used to detect late ACKs arriving after a delivery has been resolved.
 */
class TombstoneSet(
    private val windowMs: Long = 120_000L,
    private val clock: () -> Long = { currentTimeMillis() },
) {
    private val entries = mutableMapOf<String, Long>()

    /** Add a message ID with expiry at now + windowMs. */
    fun add(messageId: String) {
        entries[messageId] = clock() + windowMs
    }

    /** Returns true if the message ID is tombstoned and not yet expired. */
    fun contains(messageId: String): Boolean {
        val expiry = entries[messageId] ?: return false
        if (clock() >= expiry) {
            entries.remove(messageId)
            return false
        }
        return true
    }

    /** Remove all expired entries. Returns the number removed. */
    fun sweep(): Int {
        val now = clock()
        val expired = entries.entries.filter { now >= it.value }.map { it.key }
        for (key in expired) entries.remove(key)
        return expired.size
    }

    /** Number of active (non-expired) tombstones. */
    val size: Int get() = entries.size

    fun clear() {
        entries.clear()
    }
}
