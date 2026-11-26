package ch.trancee.meshlink

/**
 * A node's redacted state at one moment, from [MeshDiagnostics.snapshot] (ADR 0020 decision 5).
 * It holds no time series: [counters] add up since the last [MeshLink.start], and everything else is
 * the state now. Like a [DiagnosticEvent], it never holds keys, payloads, tags, certificates or
 * Bluetooth addresses, and names peers by [PeerRef].
 */
public class DiagnosticsSnapshot internal constructor(
    /**
     * The configuration, one `name = value (source)` line each as [MeshLinkConfig.describe] prints it,
     * but with the mesh ID shown as `<redacted>`.
     */
    public val configuration: List<String>,
    /** The security profile. */
    public val profile: SecurityProfile,
    /** The density class the node runs in, or `null` while it isn't running. */
    public val densityClass: DensityClass?,
    /** The open links, past their hellos, in no particular order; empty while the node isn't running. */
    public val links: List<LinkSnapshot>,
    /** How many messages, frames and certificates the node holds. */
    public val queues: QueueCounts,
    /** What the node counted since its last [MeshLink.start]. */
    public val counters: DiagnosticCounters,
)

/** One open link in a [DiagnosticsSnapshot]. */
public class LinkSnapshot internal constructor(
    /** The peer of the link. */
    public val peer: PeerRef,
    /** What carries the link's records now. */
    public val bearer: Bearer,
    /** The PHY the link transmits on, or `null` where the platform doesn't tell, as on iOS. */
    public val phy: Phy?,
    /** The negotiated ATT MTU in bytes. */
    public val mtu: Int,
    /** This node's role on the link. */
    public val role: DiagnosticEvent.LinkOpened.Role,
) {
    /** What carries a link's records. */
    public enum class Bearer {
        /** GATT writes and notifications. */
        Gatt,

        /** An L2CAP connection-oriented channel, after the link switched to it. */
        L2capCoc,
    }

    /** A Bluetooth LE PHY. */
    public enum class Phy {
        /** LE 1M. */
        Le1M,

        /** LE 2M. */
        Le2M,

        /** LE Coded, for long range. */
        LeCoded,
    }
}

/** How many messages, frames and certificates a node holds, in a [DiagnosticsSnapshot]. */
public class QueueCounts internal constructor(
    /** This node's messages not yet finished: queued, sending or waiting for their receipt. */
    public val outgoing: Int,
    /** The frames held for other nodes to carry on later (store-and-forward). */
    public val held: Int,
    /** The received messages waiting for the app to collect them. */
    public val inbox: Int,
    /** The other nodes' certificates held; always 0 in [SecurityProfile.Unsecured]. */
    public val certificates: Int,
)

/** What a node counted since its last [MeshLink.start], in a [DiagnosticsSnapshot]; all 0 before one. */
public class DiagnosticCounters internal constructor(
    /**
     * How many times each [DiagnosticEvent.code] was reported, including `Debug` events with
     * [DiagnosticsBuilder.debug] off and repeats that coalesced into one event.
     */
    public val events: Map<String, Long>,
    /** The mesh frames written in full on a link: this node's own and relayed ones. */
    public val framesSent: Long,
    /** The mesh frames of other nodes written in full on a link. */
    public val framesRelayed: Long,
    /** The link handshakes that proved the peer; always 0 in [SecurityProfile.Unsecured]. */
    public val handshakesSucceeded: Long,
)
