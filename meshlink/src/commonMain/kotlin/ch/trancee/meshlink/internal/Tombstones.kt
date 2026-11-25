package ch.trancee.meshlink.internal

/**
 * The tombstones a node keeps (spec "Tombstones", D-42) and their effect on the frames in [holding]: a
 * tombstone whose `k` opens a held `MESSAGE`'s [commitment] deletes that copy; one that fails to open it
 * deletes nothing and is not kept. A node keeps the tombstones of the delivery receipts it relays until
 * the receipt's expiry, and those that opened a held copy until the copy's expiry; it ignores any other
 * from `CATCH_UP`. At most [STORE_MAX] are kept, the oldest dropped first; they are offered in each
 * `CATCH_UP`. Times are wall-clock milliseconds. Runs on the node's serial dispatcher.
 */
internal class Tombstones(
    val holding: Holding,
    private val commitment: (k: ByteArray) -> ByteArray,
) {
    private val kept = LinkedHashMap<String, Kept>()

    /** A relayed delivery receipt expiring at Unix second [expiry] carried [tombstones]. */
    fun relayed(
        tombstones: List<Tombstone>,
        expiry: Long,
        nowMillis: Long,
    ) {
        tombstones.forEach { tombstone ->
            when (val copy = holding.copy(tombstone.messageId, nowMillis)?.takeIf { it.commitment != null }) {
                null -> keep(tombstone, expiry, nowMillis)
                else -> if (opens(tombstone, copy)) bury(tombstone, copy, expiry, nowMillis)
            }
        }
    }

    /** A linked peer's `CATCH_UP` offered [tombstones]: only those that open a held copy count. */
    fun caughtUp(
        tombstones: List<Tombstone>,
        nowMillis: Long,
    ) {
        tombstones.forEach { tombstone ->
            holding
                .copy(tombstone.messageId, nowMillis)
                ?.takeIf { opens(tombstone, it) }
                ?.let { bury(tombstone, it, it.expiry, nowMillis) }
        }
    }

    /** The live tombstones, oldest first. */
    fun offered(nowMillis: Long): List<Tombstone> = kept.values.filter { it.liveAt(nowMillis) }.map { it.tombstone }

    /** Whether a live tombstone kept here opens [frame]'s commitment: its message was delivered. */
    fun buries(
        frame: HeldFrame,
        nowMillis: Long,
    ): Boolean =
        kept.values.any {
            it.liveAt(nowMillis) && it.tombstone.messageId.contentEquals(frame.messageId) && opens(it.tombstone, frame)
        }

    /** Drops every tombstone. */
    fun clear() {
        kept.clear()
    }

    private fun opens(
        tombstone: Tombstone,
        frame: HeldFrame,
    ): Boolean = frame.commitment?.contentEquals(commitment(tombstone.k)) == true

    private fun bury(
        tombstone: Tombstone,
        copy: HeldFrame,
        expiry: Long,
        nowMillis: Long,
    ) {
        holding.drop(copy)
        keep(tombstone, expiry, nowMillis)
    }

    private fun keep(
        tombstone: Tombstone,
        expiry: Long,
        nowMillis: Long,
    ) {
        kept.values.removeAll { !it.liveAt(nowMillis) }
        val key = tombstone.encode().toHexString()
        if (key in kept) return
        if (kept.size == STORE_MAX) kept.remove(kept.keys.first())
        kept[key] = Kept(tombstone, expiry)
    }

    private class Kept(
        val tombstone: Tombstone,
        val expiry: Long,
    ) {
        fun liveAt(nowMillis: Long): Boolean = nowMillis < expiry * MILLIS_PER_SECOND
    }

    companion object {
        /** `tombstone-store`: the most tombstones kept. */
        const val STORE_MAX: Int = 1_000

        private const val MILLIS_PER_SECOND = 1_000L
    }
}
