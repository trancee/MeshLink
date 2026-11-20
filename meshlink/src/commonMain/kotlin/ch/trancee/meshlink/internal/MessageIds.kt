package ch.trancee.meshlink.internal

/**
 * `message_id`s, each remembered until a time on one clock: at most [CAPACITY], the oldest
 * forgotten first. Runs on the node's serial dispatcher.
 */
internal class MessageIds {
    private val until = LinkedHashMap<String, Long>()

    /** Whether [id] is remembered past [nowMillis]. */
    fun has(
        id: ByteArray,
        nowMillis: Long,
    ): Boolean = (until[id.toHexString()] ?: Long.MIN_VALUE) > nowMillis

    /** Remembers [id] until [untilMillis], as the newest. */
    fun put(
        id: ByteArray,
        untilMillis: Long,
    ) {
        val key = id.toHexString()
        until -= key
        until[key] = untilMillis
        if (until.size > CAPACITY) until -= until.keys.first()
    }

    companion object {
        /** The most `message_id`s remembered. */
        const val CAPACITY: Int = 4_096
    }
}
