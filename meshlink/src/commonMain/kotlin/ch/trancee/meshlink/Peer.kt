package ch.trancee.meshlink

import kotlin.time.Instant

/**
 * A node this node can reach, as [MeshLink.peers] lists it (ADR 0007 decision 10). A peer whose
 * last link closes leaves the list.
 */
public class Peer internal constructor(
    /** The NodeId the peer claimed when the link opened. */
    public val nodeId: NodeId,
    /** How this node reaches the peer. */
    public val reachability: Reachability,
    /** Whether the link to the peer is encrypted and the peer authenticated; `false` in `Unsecured`. */
    public val secured: Boolean,
    /** When this node last heard from the peer, by the platform's wall clock. */
    public val lastSeen: Instant,
)

/** How a node reaches a [Peer]. */
public sealed interface Reachability {
    /** Over a link of its own: the peer is a neighbor. */
    public data object Direct : Reachability
}
