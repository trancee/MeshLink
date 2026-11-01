package ch.trancee.meshlink

import kotlin.time.Instant

/**
 * Another node as seen from the local node, published through [MeshLink.peers].
 *
 * @property nodeId installation identity of the peer.
 * @property reachability how the peer can currently be reached.
 * @property secured whether traffic with the peer is authenticated and encrypted.
 * @property lastSeen when the local node last had evidence the peer was reachable.
 * @property proximity coarse local estimate of how near a direct peer is.
 * @sample ch.trancee.meshlink.samples.listNearbyPeers
 */
public class Peer internal constructor(
    public val nodeId: NodeId,
    public val reachability: Reachability,
    public val secured: Boolean,
    public val lastSeen: Instant,
    public val proximity: Proximity,
) {
    override fun equals(other: Any?): Boolean = other is Peer && nodeId == other.nodeId &&
        reachability == other.reachability && secured == other.secured && lastSeen == other.lastSeen &&
        proximity == other.proximity

    override fun hashCode(): Int = nodeId.hashCode()

    override fun toString(): String = "Peer(${nodeId.redacted()}, $reachability, ${proximity.bucket})"
}

/** How a peer can currently be reached. */
public sealed class Reachability {
    /** Linked directly to the local node. */
    public data object Direct : Reachability()

    /** Reachable through other nodes; [hops] is at least 2. */
    public data class Routed(public val hops: Int) : Reachability()

    /** Seen before, currently not reachable. Messages may be held (store-and-forward). */
    public data object Unreachable : Reachability()
}

/**
 * Coarse proximity of a direct peer, measured locally and never sent to other nodes. Never a
 * distance in meters. Routed and unreachable peers have bucket [ProximityBucket.Unknown] and no
 * measurement.
 *
 * @property bucket coarse category.
 * @property smoothedRssiDbm smoothed received signal strength, `null` when not measured.
 * @property measuredAt time of the last measurement, `null` when not measured.
 */
public class Proximity internal constructor(
    public val bucket: ProximityBucket,
    public val smoothedRssiDbm: Int?,
    public val measuredAt: Instant?,
) {
    override fun equals(other: Any?): Boolean = other is Proximity && bucket == other.bucket &&
        smoothedRssiDbm == other.smoothedRssiDbm && measuredAt == other.measuredAt

    override fun hashCode(): Int = bucket.hashCode() * 31 + (smoothedRssiDbm ?: 0)

    override fun toString(): String = "Proximity($bucket, $smoothedRssiDbm dBm)"

    internal companion object {
        val Unknown = Proximity(ProximityBucket.Unknown, null, null)

        fun fromRssi(smoothedRssiDbm: Int, measuredAt: Instant): Proximity = Proximity(
            when {
                smoothedRssiDbm >= IMMEDIATE_MIN_DBM -> ProximityBucket.Immediate
                smoothedRssiDbm >= NEAR_MIN_DBM -> ProximityBucket.Near
                else -> ProximityBucket.Far
            },
            smoothedRssiDbm,
            measuredAt,
        )

        private const val IMMEDIATE_MIN_DBM = -55
        private const val NEAR_MIN_DBM = -75
    }
}

/** Coarse proximity categories; thresholds need hardware evidence (T9). */
public enum class ProximityBucket {
    /** Within roughly arm's reach. */
    Immediate,

    /** Same room. */
    Near,

    /** Detectable but distant. */
    Far,

    /** No measurement: routed or unreachable peers. */
    Unknown,
}
