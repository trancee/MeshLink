package ch.trancee.meshlink.transfer

/** Identifies a transfer session by the message ID (a 16-byte ByteArray). */
typealias TransferSessionId = ByteArray

/**
 * Weighted round-robin scheduler for concurrent transfer sessions.
 *
 * Priority weights:
 * - [Priority.HIGH]   — 3 slots per cycle (scheduled every call)
 * - [Priority.NORMAL] — 1 slot per cycle (scheduled every call)
 * - [Priority.LOW]    — 1 slot, skipped on every even-numbered call (skip-alternate)
 *
 * [nextBatch] returns up to [maxConcurrent] unique session IDs per call, honouring
 * the weighted ordering and the alternating LOW exclusion.
 */
class TransferScheduler(maxConcurrent: Int = 4) {
    private var _maxConcurrent = maxConcurrent
    val maxConcurrent: Int get() = _maxConcurrent

    private var callCount = 0
    private val sessionMap = LinkedHashMap<List<Byte>, Priority>()

    /** Adds or replaces the registration for [id] with the given [priority]. */
    fun register(id: TransferSessionId, priority: Priority) {
        sessionMap[id.asList()] = priority
    }

    /** Removes the session identified by [id] from the scheduler. */
    fun deregister(id: TransferSessionId) {
        sessionMap.remove(id.asList())
    }

    /** Updates the maximum number of sessions returned per [nextBatch] call. */
    fun updateMaxConcurrent(n: Int) {
        _maxConcurrent = n
    }

    /**
     * Returns the next batch of up to [maxConcurrent] session IDs, weighted by priority.
     *
     * HIGH sessions appear three times in the candidate list before deduplication, ensuring
     * they occupy the first slots when [maxConcurrent] < total sessions.
     * LOW sessions are omitted on every even call (skip-alternate).
     */
    fun nextBatch(): List<TransferSessionId> {
        callCount++
        val high = sessionMap.entries.filter { it.value == Priority.HIGH }.map { it.key }
        val normal = sessionMap.entries.filter { it.value == Priority.NORMAL }.map { it.key }
        val low = sessionMap.entries.filter { it.value == Priority.LOW }.map { it.key }

        val includeLow = callCount % 2 == 1

        val candidates = buildList {
            repeat(3) { addAll(high) }
            addAll(normal)
            if (includeLow) addAll(low)
        }

        val seen = mutableSetOf<List<Byte>>()
        return candidates.filter { seen.add(it) }.take(_maxConcurrent).map { it.toByteArray() }
    }
}
