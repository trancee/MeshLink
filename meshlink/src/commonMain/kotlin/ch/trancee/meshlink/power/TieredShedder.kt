package ch.trancee.meshlink.power

import ch.trancee.meshlink.transfer.Priority

sealed interface EvictionDecision {
    object Accept : EvictionDecision

    object Reject : EvictionDecision

    class EvictAndAccept(val evictPeerId: ByteArray) : EvictionDecision {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || other !is EvictAndAccept) return false
            return evictPeerId.contentEquals(other.evictPeerId)
        }

        override fun hashCode(): Int = evictPeerId.contentHashCode()
    }
}

class TieredShedder {

    fun evaluate(
        newPeerId: ByteArray,
        newPriority: Priority,
        currentConnections: List<ManagedConnection>,
        maxConnections: Int,
        minThroughputBytesPerSec: Float,
    ): EvictionDecision {
        // Below the connection limit: accept unconditionally.
        if (currentConnections.size < maxConnections) return EvictionDecision.Accept

        // Find connections that could be evicted (lower priority than the new peer).
        val candidates = currentConnections.filter { it.priority < newPriority }
        if (candidates.isEmpty()) return EvictionDecision.Reject

        // Prefer idle/stalled connections, then lower priority within each category.
        val sorted =
            candidates.sortedWith(
                compareBy(
                    { if (isIdleOrStalled(it, minThroughputBytesPerSec)) 0 else 1 },
                    { it.priority.ordinal },
                )
            )
        return EvictionDecision.EvictAndAccept(sorted[0].peerId)
    }

    private fun isIdleOrStalled(conn: ManagedConnection, minThroughputBytesPerSec: Float): Boolean {
        val ts = conn.transferStatus ?: return true
        return ts.bytesPerSecond < minThroughputBytesPerSec
    }
}
