package ch.trancee.meshlink.api

/**
 * A point-in-time snapshot of a single route in the mesh routing table.
 *
 * All [ByteArray] fields use content equality in [equals]/[hashCode].
 *
 * @param destination Key hash (12 bytes) of the destination peer.
 * @param nextHop Key hash (12 bytes) of the next-hop peer toward [destination].
 * @param cost Babel link-state metric to [destination] via [nextHop].
 * @param seqNo Babel sequence number for this route (used for loop-avoidance feasibility check).
 * @param ageMs Milliseconds since this route was last refreshed.
 */
public data class RoutingEntry(
    val destination: ByteArray,
    val nextHop: ByteArray,
    val cost: Int,
    val seqNo: Int,
    val ageMs: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is RoutingEntry &&
            destination.contentEquals(other.destination) &&
            nextHop.contentEquals(other.nextHop) &&
            cost == other.cost &&
            seqNo == other.seqNo &&
            ageMs == other.ageMs

    override fun hashCode(): Int {
        var result = destination.contentHashCode()
        result = 31 * result + nextHop.contentHashCode()
        result = 31 * result + cost
        result = 31 * result + seqNo
        result = 31 * result + ageMs.hashCode()
        return result
    }

    override fun toString(): String =
        "RoutingEntry(destination=${destination.contentToString()}, " +
            "nextHop=${nextHop.contentToString()}, cost=$cost, seqNo=$seqNo, ageMs=$ageMs)"
}

/**
 * A read-only, point-in-time snapshot of the mesh routing table. Not a live view — call
 * [MeshLinkApi.routingSnapshot] again for updated data.
 *
 * @param capturedAtMs Monotonic timestamp (ms) when the snapshot was taken.
 * @param routes All currently known routes, ordered by ascending [RoutingEntry.cost].
 */
public data class RoutingSnapshot(val capturedAtMs: Long, val routes: List<RoutingEntry>)
