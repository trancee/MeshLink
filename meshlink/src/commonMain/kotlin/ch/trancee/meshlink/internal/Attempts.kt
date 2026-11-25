package ch.trancee.meshlink.internal

/**
 * The origin's send count per `message_id` (spec "Origin behavior", D-61): at most
 * [MessageIds.CAPACITY] ids, the least recently counted forgotten first. Runs on the node's serial
 * dispatcher.
 */
internal class Attempts {
    private val counts = LinkedHashMap<String, Int>()

    /** The `attempt` of this send of [id]: 0 for the first, then one more modulo 256. */
    fun next(id: ByteArray): Int {
        val key = id.toHexString()
        val attempt = counts.remove(key)?.let { (it + 1) and BYTE } ?: 0
        counts[key] = attempt
        if (counts.size > MessageIds.CAPACITY) counts -= counts.keys.first()
        return attempt
    }

    private companion object {
        const val BYTE = 0xff
    }
}
