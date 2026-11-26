package ch.trancee.meshlink

import kotlin.time.Instant

/**
 * A node this node can reach, as [MeshLink.peers] lists it (ADR 0007 decision 10): a neighbor while
 * it has a link, or a node it sent to or heard from while a route found by route discovery leads to
 * it; in `Classical` and `PostQuantum` this node must also hold the node's valid certificate. A peer
 * leaves the list when its last link closes and no such route is left.
 */
public class Peer internal constructor(
    /** The NodeId the peer claimed when the link opened. */
    public val nodeId: NodeId,
    /** How this node reaches the peer. */
    public val reachability: Reachability,
    /**
     * Whether the peer is authenticated: by its link for a direct peer, by the certificate this node
     * holds for a routed one, whose messages are sealed to it end to end; `false` in `Unsecured`.
     */
    public val secured: Boolean,
    /** When this node last heard from the peer or a route to it, by the platform's wall clock. */
    public val lastSeen: Instant,
    /** How close the peer is by received signal strength, for a [Reachability.Direct] peer; else `null`. */
    public val proximity: Proximity?,
)

/**
 * How close a direct [Peer] is, from the strength of its signal (ADR 0007 decision 10): never a
 * distance, and never sent to other nodes.
 */
public class Proximity internal constructor(
    /** The smoothed received signal strength in dBm, or `null` before the first measurement. */
    public val rssi: Double?,
    /** The band [Proximity.rssi] falls in, or [ProximityBucket.Unknown] once it is 30 s old. */
    public val bucket: ProximityBucket,
    /** When the last measurement went into [Proximity.rssi], by the platform's wall clock; `null` before the first. */
    public val measuredAt: Instant?,
)

/** A coarse band of [Proximity.rssi]. */
public enum class ProximityBucket {
    /** At least −55 dBm: within arm's reach, typically. */
    Immediate,

    /** From −75 dBm to below −55 dBm: in the same room, typically. */
    Near,

    /** Below −75 dBm. */
    Far,

    /** No measurement in the last 30 s. */
    Unknown,
}

/** How a node reaches a [Peer]. */
public sealed interface Reachability {
    /** Over a link of its own: the peer is a neighbor. */
    public data object Direct : Reachability

    /**
     * Through other nodes, on a route of [hops] links. A routed peer leaves `peers` when its last
     * route expires, and in `Classical` and `PostQuantum` also when its certificate lapses.
     */
    public data class Routed(
        /** The route's hop count: the links between this node and the peer, at least 2. */
        public val hops: Int,
    ) : Reachability
}
