package ch.trancee.meshlink

/**
 * An event on [MeshLink.diagnostics] (ADR 0020): a stable [code], a [severity] and typed fields.
 *
 * Fields never hold keys, payloads, routing or conversation tags, advertisement identifiers, lookup
 * salts, certificates or Bluetooth addresses. A peer appears as a [PeerRef] and a link whose peer is
 * not yet known as a [LinkRef]; both change on every [MeshLink.start]. `Debug` events appear only
 * with [DiagnosticsBuilder.debug] on.
 *
 * Repeats of one code and reference within 10 s are coalesced: the first is emitted at once, and
 * the window's repeats, if any, as one event with the latest fields and their [count]. The diagnostic
 * reference (`docs/reference/diagnostics.md`) lists every code with its likely cause and fix.
 */
public sealed class DiagnosticEvent(
    /** The catalog code: `ML` and four digits, never reused for another event. */
    public val code: String,
    /** How serious the event is. */
    public val severity: Severity,
) {
    /**
     * How many occurrences this event stands for: 1, or the number of repeats of its code and
     * reference coalesced in the 10 s after the previous event emitted for them.
     */
    public var count: Int = 1
        internal set

    /** The peer or link that, with the code, makes a repeat; `null` for an event about neither. */
    internal open val reference: Any? get() = null

    /** Each field's name and value, in declaration order; the only content an event shows. */
    internal abstract val fields: List<Pair<String, Any>>

    /** The code, the event's name, its fields and its count, for logs. */
    override fun toString(): String {
        val shown = fields.joinToString(", ") { (name, value) -> "$name=$value" }
        return "$code ${this::class.simpleName}($shown) x$count"
    }

    /** How serious a [DiagnosticEvent] is (ADR 0020 decision 2). */
    public enum class Severity {
        /** Detail for investigations; emitted only with [DiagnosticsBuilder.debug] on. */
        Debug,

        /** A state change. */
        Info,

        /** Degraded, but working. */
        Warning,

        /** A feature is not working. */
        Error,
    }

    /**
     * `ML1001`: the node started with [profile] and [preset].
     *
     * - Cause: the app called [MeshLink.start] on a stopped node.
     * - Fix: none needed.
     * - Related: [MeshLinkConfig.securityProfile], [MeshLinkConfig.preset].
     * - Symptom: none.
     */
    public class Started internal constructor(
        /** The node's security profile. */
        public val profile: SecurityProfile,
        /** The node's preset. */
        public val preset: Preset,
    ) : DiagnosticEvent("ML1001", Severity.Info) {
        override val fields: List<Pair<String, Any>> get() = listOf("profile" to profile, "preset" to preset)
    }

    /**
     * `ML1002`: the node stopped for [reason].
     *
     * - Cause: the app called [MeshLink.stop] or [MeshLink.resetIdentity] on a started node.
     * - Fix: none needed.
     * - Related: [MeshLink.state].
     * - Symptom: none.
     */
    public class Stopped internal constructor(
        /** Why the node stopped. */
        public val reason: Reason,
    ) : DiagnosticEvent("ML1002", Severity.Info) {
        override val fields: List<Pair<String, Any>> get() = listOf("reason" to reason)

        /** Why a node stopped. */
        public enum class Reason {
            /** The app called [MeshLink.stop]. */
            Requested,

            /** The app called [MeshLink.resetIdentity]. */
            IdentityReset,
        }
    }

    /**
     * `ML1003`: the node started in [SecurityProfile.Unsecured], on every start: anyone in radio
     * range can read, forge and replay its traffic.
     *
     * - Cause: the configuration chose [SecurityProfile.Unsecured].
     * - Fix: choose [SecurityProfile.Classical] or [SecurityProfile.PostQuantum] unless every
     *   payload is public anyway.
     * - Related: [MeshLinkConfig.securityProfile].
     * - Symptom: none.
     */
    public class UnsecuredProfile internal constructor() : DiagnosticEvent("ML1003", Severity.Warning) {
        override val fields: List<Pair<String, Any>> get() = emptyList()
    }

    /**
     * `ML1004`: the node renewed its certificate and static keys, as it does every 12 h (spec
     * "Static-key rotation"); a message sealed to its earlier certificate still opens until that
     * certificate expires.
     *
     * - Cause: the rotation interval passed.
     * - Fix: none needed.
     * - Related: spec "Static-key rotation".
     * - Symptom: none.
     */
    public class OwnCertificateRenewed internal constructor() : DiagnosticEvent("ML1004", Severity.Info) {
        override val fields: List<Pair<String, Any>> get() = emptyList()
    }

    /**
     * `ML2001`: a scan window started or stopped, as [phase] tells. A central scans in windows of 4 s
     * and rests between them as long as its density class and [MeshLinkConfig.power] set; every window
     * starts a fresh scan.
     *
     * - Cause: the node scans as central.
     * - Fix: none needed.
     * - Related: [MeshLinkConfig.power], [MeshLinkConfig.density].
     * - Symptom: high battery use.
     */
    public class ScanWindow internal constructor(
        /** Whether the window started or stopped. */
        public val phase: Phase,
    ) : DiagnosticEvent("ML2001", Severity.Debug) {
        override val fields: List<Pair<String, Any>> get() = listOf("phase" to phase)

        /** A scan window's boundary. */
        public enum class Phase {
            /** The window started: the node scans. */
            Started,

            /** The window stopped: the node rests. */
            Stopped,
        }
    }

    /**
     * `ML2006`: an address sighted without an advertisement, as a backgrounded iPhone advertises,
     * failed vetting: the node couldn't connect to it or read its capability record, or the record's
     * advertisement belongs to no peer of this mesh. Its sightings add no candidate until it goes
     * unseen for 10 min.
     *
     * - Cause: a phone of another mesh or app nearby, or a MeshLink peer that moved out of range
     *   during the dial.
     * - Fix: none needed. If a peer of this mesh stays unfound, check that both use the same mesh ID
     *   and security profile.
     * - Related: [MeshLinkConfig.meshId].
     * - Symptom: peers not found.
     */
    public class StaleAddressMarked internal constructor() : DiagnosticEvent("ML2006", Severity.Debug) {
        override val fields: List<Pair<String, Any>> get() = emptyList()
    }

    /**
     * `ML2007`: the node's density class switched [from] one class [to] another, which sets its link
     * budget and scan schedule. Each start begins in [DensityClass.Low]; a pinned class never
     * switches.
     *
     * - Cause: more or fewer peers in range than before.
     * - Fix: none needed. If the class flaps in a stable crowd, pin it with [Density.Pinned].
     * - Related: [MeshLinkConfig.density].
     * - Symptom: high battery use.
     */
    public class DensityClassChanged internal constructor(
        /** The class before the switch. */
        public val from: DensityClass,
        /** The class after the switch. */
        public val to: DensityClass,
    ) : DiagnosticEvent("ML2007", Severity.Info) {
        override val fields: List<Pair<String, Any>> get() = listOf("from" to from, "to" to to)
    }

    /**
     * `ML3001`: a link to [peer] opened as [role], with an ATT MTU of [mtu] bytes: the hellos or the
     * handshake completed, and the peer is listed in [MeshLink.peers].
     *
     * - Cause: a peer in range linked.
     * - Fix: none needed.
     * - Related: [MeshLink.peers].
     * - Symptom: none.
     */
    public class LinkOpened internal constructor(
        /** The peer of the link. */
        public val peer: PeerRef,
        /** This node's role on the link. */
        public val role: Role,
        /** The negotiated ATT MTU in bytes; each GATT write carries at most `mtu - 3` bytes. */
        public val mtu: Int,
    ) : DiagnosticEvent("ML3001", Severity.Info) {
        override val reference: Any get() = peer
        override val fields: List<Pair<String, Any>>
            get() = listOf("peer" to peer, "role" to role, "mtu" to mtu)

        /** This node's role on a link. */
        public enum class Role {
            /** This node connected to the peer. */
            Central,

            /** The peer connected to this node. */
            Peripheral,
        }
    }

    /**
     * `ML3002`: an open link to [peer] closed for [reason]; [byPeer] tells whether the peer closed
     * it. A link that closes before it opens reports why as `ML3003`, `ML3008` or `ML4001` instead.
     *
     * - Cause: [Reason.Normal] or [Reason.ShuttingDown]: a node stopped. [Reason.DuplicateLink]: the
     *   two nodes linked both ways and kept one link. [Reason.ResourceLimit]: the closing node holds
     *   its link budget. [Reason.ProtocolError]: an incompatible or faulty peer, or a corrupted
     *   record. [Reason.ConnectionLost]: the peer moved out of range or its radio failed.
     * - Fix: none needed for a stop, a duplicate or a lost connection; the node links again when the
     *   peer is back. If [Reason.ProtocolError] repeats, update both apps to the same MeshLink
     *   version.
     * - Related: spec "Close".
     * - Symptom: messages not delivered.
     */
    public class LinkClosed internal constructor(
        /** The peer of the link. */
        public val peer: PeerRef,
        /** Why the link closed. */
        public val reason: Reason,
        /** Whether the peer closed the link; `false` if this node did or the connection was lost. */
        public val byPeer: Boolean,
    ) : DiagnosticEvent("ML3002", Severity.Info) {
        override val reference: Any get() = peer
        override val fields: List<Pair<String, Any>>
            get() = listOf("peer" to peer, "reason" to reason, "byPeer" to byPeer)

        /** Why a link closed: the spec's close reasons, and a lost connection. */
        public enum class Reason {
            /** A node closed the link without a fault. */
            Normal,

            /** The nodes linked both ways and closed one link. */
            DuplicateLink,

            /** A node received a frame or record it could not accept. */
            ProtocolError,

            /** The handshake failed. */
            HandshakeFailed,

            /** The nodes share no protocol version. */
            UnsupportedVersion,

            /** The nodes belong to different meshes. */
            WrongMesh,

            /** The closing node holds its link budget. */
            ResourceLimit,

            /** A node stopped. */
            ShuttingDown,

            /** The connection ended without a close, as when the peer moves out of range. */
            ConnectionLost,
        }
    }

    /**
     * `ML3003`: a connect as central on [link] failed for [reason]. [attempt] counts from 1; a
     * [Reason.Transient] failure is retried up to 3 times (spec `connect-retries`).
     *
     * - Cause: the peer moved out of range or stopped advertising, or the platform failed the
     *   connect; Android reports GATT status 133 or 147 as [Reason.Transient], often on a first
     *   connect.
     * - Fix: transient failures retry by themselves. If every connect fails, move the phones closer
     *   and turn Bluetooth off and on.
     * - Related: spec `connect-retries`.
     * - Symptom: peers not found.
     */
    public class ConnectFailed internal constructor(
        /** The link the connect was for. */
        public val link: LinkRef,
        /** Why the connect failed. */
        public val reason: Reason,
        /** The attempt that failed, from 1. */
        public val attempt: Int,
    ) : DiagnosticEvent("ML3003", Severity.Warning) {
        override val reference: Any get() = link
        override val fields: List<Pair<String, Any>>
            get() = listOf("link" to link, "reason" to reason, "attempt" to attempt)

        /** Why a connect failed. */
        public enum class Reason {
            /** The platform rejected an argument; a MeshLink defect. */
            InvalidArgument,

            /** The peer is out of range or no longer advertising. */
            PeerUnreachable,

            /** The peer serves no MeshLink capability, so it is not a MeshLink node. */
            CapabilityMissing,

            /** The link closed before the connect finished. */
            LinkClosed,

            /** The platform has no radio MeshLink can use. */
            Unsupported,

            /** The platform radio refused or failed the connect, for example out of resources. */
            RadioError,

            /** A cause that often clears on retry, such as Android GATT status 133 or 147. */
            Transient,
        }
    }

    /**
     * `ML3004`: the node refused a bearer, as it holds [cap] links: its link budget, or the radio's
     * cap on links as peripheral.
     *
     * - Cause: many peers in range; expected in a dense mesh.
     * - Fix: none needed; the node links to other peers as its links close.
     * - Related: [MeshLinkConfig.density].
     * - Symptom: peers not found.
     */
    public class LinkCapReached internal constructor(
        /** The cap the node holds. */
        public val cap: Int,
    ) : DiagnosticEvent("ML3004", Severity.Debug) {
        override val fields: List<Pair<String, Any>> get() = listOf("cap" to cap)
    }

    /**
     * `ML3005`: an L2CAP CoC channel to [peer] failed to open for [reason], so the link stays on
     * GATT (spec "Bearer switch").
     *
     * - Cause: the platform or the peer refused the channel.
     * - Fix: none needed; GATT carries the same traffic more slowly. If it happens with every peer,
     *   turn [MeshLinkConfigBuilder.l2capUpgrade] off.
     * - Related: [MeshLinkConfig.l2capUpgrade].
     * - Symptom: messages not delivered.
     */
    public class BearerFallback internal constructor(
        /** The peer of the link. */
        public val peer: PeerRef,
        /** Why the channel failed to open. */
        public val reason: Reason,
    ) : DiagnosticEvent("ML3005", Severity.Info) {
        override val reference: Any get() = peer
        override val fields: List<Pair<String, Any>> get() = listOf("peer" to peer, "reason" to reason)

        /** Why an L2CAP CoC channel failed to open. */
        public enum class Reason {
            /** The platform rejected an argument; a MeshLink defect. */
            InvalidArgument,

            /** The peer is out of range. */
            PeerUnreachable,

            /** The link closed before the channel opened. */
            LinkClosed,

            /** The platform radio refused or failed the channel. */
            RadioError,

            /** A cause that often clears on retry. */
            Transient,
        }
    }

    /**
     * `ML3007`: an open link dropped a link-local frame of [type] it doesn't handle: an unknown type,
     * or a `REKEY` on an `Unsecured` link. The link stays open.
     *
     * - Cause: the peer runs a newer MeshLink version, or is faulty.
     * - Fix: none needed. If it repeats, update both apps to the same MeshLink version.
     * - Related: spec "Link frames".
     * - Symptom: none.
     */
    public class UnknownLinkFrameDropped internal constructor(
        /** The frame's type byte, `0x00`–`0x3F`. */
        public val type: Int,
    ) : DiagnosticEvent("ML3007", Severity.Debug) {
        override val fields: List<Pair<String, Any>> get() = listOf("type" to type)
    }

    /**
     * `ML3008`: a link closed with no protocol version in common: the peer offered at most
     * [theirVersion].
     *
     * - Cause: the peer runs a MeshLink version with an incompatible protocol.
     * - Fix: update both apps to the same MeshLink version.
     * - Related: spec "Version selection".
     * - Symptom: peers not found.
     */
    public class ProtocolVersionRejected internal constructor(
        /** The highest protocol version the peer offered. */
        public val theirVersion: Int,
    ) : DiagnosticEvent("ML3008", Severity.Warning) {
        override val fields: List<Pair<String, Any>> get() = listOf("theirVersion" to theirVersion)
    }

    /**
     * `ML3009`: a node refused a fragmented frame with `BUSY` for [reason]; [direction] tells which
     * node. The link stays open, and the sender retries the message later (spec "Busy reply").
     *
     * - Cause: [Reason.Slots]: the link reassembles its maximum number of frames, or its one large
     *   frame. [Reason.Budget]: the refusing node reassembles as many large frames as it can hold.
     * - Fix: none needed. If it repeats, send fewer large messages at once.
     * - Related: spec "Busy reply".
     * - Symptom: messages not delivered.
     */
    public class BusyReply internal constructor(
        /** Whether this node sent the `BUSY` or received it. */
        public val direction: Direction,
        /** Why the frame was refused. */
        public val reason: Reason,
    ) : DiagnosticEvent("ML3009", Severity.Debug) {
        override val fields: List<Pair<String, Any>>
            get() = listOf("direction" to direction, "reason" to reason)

        /** Which node refused the frame. */
        public enum class Direction {
            /** This node refused a frame of the peer. */
            Sent,

            /** The peer refused a frame of this node. */
            Received,
        }

        /** Why a frame was refused. */
        public enum class Reason {
            /** The link reassembles its maximum number of frames, or its one large frame. */
            Slots,

            /** The refusing node's budget for large frames is spent. */
            Budget,
        }
    }

    /**
     * `ML3010`: the link to [peer] moved from GATT to an L2CAP CoC channel (spec "Bearer switch").
     *
     * - Cause: both nodes support L2CAP CoC and have [MeshLinkConfig.l2capUpgrade] on.
     * - Fix: none needed.
     * - Related: [MeshLinkConfig.l2capUpgrade].
     * - Symptom: none.
     */
    public class BearerSwitched internal constructor(
        /** The peer of the link. */
        public val peer: PeerRef,
    ) : DiagnosticEvent("ML3010", Severity.Debug) {
        override val reference: Any get() = peer
        override val fields: List<Pair<String, Any>> get() = listOf("peer" to peer)
    }

    /**
     * `ML4001`: the handshake on [link] failed for [reason], and the link closed.
     *
     * - Cause: [Reason.Timeout]: the peer moved away or is overloaded. [Reason.Decrypt]: the peer
     *   uses another security profile, mesh ID or mesh key. [Reason.Certificate]: the peer's
     *   certificate is invalid or expired, often a wrong clock. [Reason.Protocol]: an incompatible
     *   or faulty peer.
     * - Fix: give every phone of the mesh the same security profile, mesh ID and mesh key, and a
     *   correct clock.
     * - Related: [MeshLinkConfig.securityProfile], [MeshLinkConfig.meshId], [MeshLinkConfig.meshKey].
     * - Symptom: peers not found.
     */
    public class HandshakeFailed internal constructor(
        /** The link whose handshake failed. */
        public val link: LinkRef,
        /** What failed. */
        public val reason: Reason,
    ) : DiagnosticEvent("ML4001", Severity.Warning) {
        override val reference: Any get() = link
        override val fields: List<Pair<String, Any>> get() = listOf("link" to link, "reason" to reason)

        /** Why a link handshake failed. */
        public enum class Reason {
            /** Not complete within `handshake-timeout` after the bearer opened. */
            Timeout,

            /**
             * A handshake message failed to decrypt or carried an unusable key. Another security
             * profile, mesh ID or mesh key all fail this way.
             */
            Decrypt,

            /** The peer's certificate was missing, invalid, expired, or bound another key. */
            Certificate,

            /** An unexpected, malformed or version-changing handshake frame. */
            Protocol,
        }
    }

    /**
     * `ML4002`: a handshake broke a denial-of-service bound, [limit], so the bearer closed without a
     * reply (spec "Handshake limits").
     *
     * - Cause: many phones connecting at once, or a peer opening handshakes faster than allowed.
     * - Fix: none needed; honest peers succeed on a later attempt.
     * - Related: spec "Handshake limits".
     * - Symptom: peers not found.
     */
    public class HandshakeLimitDropped internal constructor(
        /** The bound the handshake broke. */
        public val limit: Limit,
    ) : DiagnosticEvent("ML4002", Severity.Warning) {
        override val fields: List<Pair<String, Any>> get() = listOf("limit" to limit)

        /** A handshake denial-of-service bound. */
        public enum class Limit {
            /** A second handshake on one bearer (`handshake-half-open-per-link`). */
            PerBearer,

            /** More than `handshake-half-open-global` handshakes at once. */
            Global,

            /** A new handshake from one address within `handshake-neighbor-interval`. */
            NeighborInterval,
        }
    }

    /**
     * `ML4004`: a signed broadcast reached the node whose signature did not verify, so the node
     * neither delivered nor relayed it (spec "BROADCAST").
     *
     * - Cause: a faulty or malicious peer, or bytes corrupted in transit.
     * - Fix: none needed; if it repeats, look for a peer that forges broadcasts.
     * - Related: [MeshLink.broadcast].
     * - Symptom: none.
     */
    public class BroadcastSignatureInvalid internal constructor() : DiagnosticEvent("ML4004", Severity.Debug) {
        override val fields: List<Pair<String, Any>> get() = emptyList()
    }

    /**
     * `ML5001`: route discovery found a route to [peer], [hops] hops away, so [MeshLink.peers] lists
     * it as [Reachability.Routed].
     *
     * - Cause: the node sent to or heard from a peer beyond its links.
     * - Fix: none needed.
     * - Related: [MeshLink.peers].
     * - Symptom: none.
     */
    public class RouteFound internal constructor(
        /** The peer the route leads to. */
        public val peer: PeerRef,
        /** The route's hop count. */
        public val hops: Int,
    ) : DiagnosticEvent("ML5001", Severity.Debug) {
        override val reference: Any get() = peer
        override val fields: List<Pair<String, Any>> get() = listOf("peer" to peer, "hops" to hops)
    }

    /**
     * `ML5002`: [MeshLink.peers] no longer lists [peer] as [Reachability.Routed]: its route expired,
     * or the key its routing tags derive from lapsed.
     *
     * - Cause: the peer moved away or stopped, or no message used its route.
     * - Fix: none needed; the next message to the peer searches for a route anew.
     * - Related: [MeshLink.peers].
     * - Symptom: messages not delivered.
     */
    public class RouteLost internal constructor(
        /** The peer the route led to. */
        public val peer: PeerRef,
    ) : DiagnosticEvent("ML5002", Severity.Debug) {
        override val reference: Any get() = peer
        override val fields: List<Pair<String, Any>> get() = listOf("peer" to peer)
    }

    /**
     * `ML5003`: the node relayed a routed frame of [type], a type this version does not know, by its
     * header alone (spec "Unicast forwarding", "Flood forwarding").
     *
     * - Cause: a peer runs a newer version of MeshLink.
     * - Fix: none needed; update the app to use the newer type.
     * - Related: spec "Routed frame summary".
     * - Symptom: none.
     */
    public class UnknownRoutedFrameRelayed internal constructor(
        /** The frame's type byte. */
        public val type: Int,
    ) : DiagnosticEvent("ML5003", Severity.Debug) {
        override val fields: List<Pair<String, Any>> get() = listOf("type" to type)
    }

    /**
     * `ML5004`: the node dropped a routed frame whose expiry lies outside what its clock accepts
     * (spec "Routing headers"): the sender's clock and this phone's differ by at least [skewSeconds],
     * positive if the sender's runs ahead, negative if it runs behind.
     *
     * - Cause: a phone whose clock is set by hand, or wrong.
     * - Fix: set every phone of the mesh to the network-provided time.
     * - Related: spec "Routing headers".
     * - Symptom: messages not delivered.
     */
    public class ExpiryFarFromLocalTime internal constructor(
        /** The least the clocks differ by, in seconds. */
        public val skewSeconds: Long,
    ) : DiagnosticEvent("ML5004", Severity.Warning) {
        override val fields: List<Pair<String, Any>> get() = listOf("skewSeconds" to skewSeconds)
    }

    /**
     * `ML6001`: the node gave up a frame it held for others to make room for a new one (spec
     * "Holding"), for [reason].
     *
     * - Cause: [Reason.LinkShare]: one link sent more than its share of the frames held.
     *   [Reason.ClosestToExpiry]: the hold is full and no link is over its share.
     * - Fix: none needed. If it repeats, peers that come back later may miss messages; send fewer
     *   messages to absent peers.
     * - Related: spec "Holding".
     * - Symptom: messages not delivered.
     */
    public class HeldMessageEvicted internal constructor(
        /** Why the frame gave way. */
        public val reason: Reason,
    ) : DiagnosticEvent("ML6001", Severity.Info) {
        override val fields: List<Pair<String, Any>> get() = listOf("reason" to reason)

        /** Why a held frame gave way. */
        public enum class Reason {
            /** It came from a link over its share of the hold; that link's frame closest to expiry gave way. */
            LinkShare,

            /** The hold is full and no link is over its share; the frame closest to expiry gave way. */
            ClosestToExpiry,
        }
    }

    /**
     * `ML6002`: a certificate lookup brought [peer]'s certificate after [attempts] requests (spec
     * "Certificate lookup"), so the node can seal messages to it.
     *
     * - Cause: the node sent to a peer it has never linked with.
     * - Fix: none needed.
     * - Related: spec "Certificate lookup".
     * - Symptom: none.
     */
    public class CertificateLookup internal constructor(
        /** The peer whose certificate arrived. */
        public val peer: PeerRef,
        /** The requests the lookup sent. */
        public val attempts: Int,
    ) : DiagnosticEvent("ML6002", Severity.Debug) {
        override val reference: Any get() = peer
        override val fields: List<Pair<String, Any>> get() = listOf("peer" to peer, "attempts" to attempts)
    }

    /**
     * `ML6003`: the lookup of [peer]'s certificate got no valid reply by its deadline, after [attempts]
     * requests (spec "Certificate lookup"). A message that waited for it was never sealed to [peer].
     *
     * - Cause: the peer is out of reach of the mesh, or stopped.
     * - Fix: bring the phones closer together, or send again once the peer is listed in [MeshLink.peers].
     * - Related: spec "Certificate lookup".
     * - Symptom: messages not delivered.
     */
    public class CertificateLookupUnanswered internal constructor(
        /** The peer looked up. */
        public val peer: PeerRef,
        /** The requests the lookup sent. */
        public val attempts: Int,
    ) : DiagnosticEvent("ML6003", Severity.Warning) {
        override val reference: Any get() = peer
        override val fields: List<Pair<String, Any>> get() = listOf("peer" to peer, "attempts" to attempts)
    }

    /**
     * `ML6004`: a reply to this node's certificate lookup failed to open, or held no valid certificate
     * of the peer looked up (spec "Certificate lookup"). The node drops it and waits for another.
     *
     * - Cause: a faulty or hostile node answered, or the peer's certificate has expired.
     * - Fix: none needed if a valid reply follows; otherwise check the peer's clock.
     * - Related: spec "Certificate lookup".
     * - Symptom: messages not delivered.
     */
    public class InvalidCertificateReply internal constructor() : DiagnosticEvent("ML6004", Severity.Debug) {
        override val fields: List<Pair<String, Any>> get() = emptyList()
    }

    /**
     * `ML7001`: this collector fell [dropped] events behind, so its oldest events were dropped. Each
     * collector buffers 256 events.
     *
     * - Cause: the collector does slow work for each event.
     * - Fix: collect on a fast path and move slow work elsewhere.
     * - Related: [MeshLink.diagnostics].
     * - Symptom: none.
     */
    public class DiagnosticsDropped internal constructor(
        /** How many events this collector lost. */
        public val dropped: Int,
    ) : DiagnosticEvent("ML7001", Severity.Warning) {
        override val fields: List<Pair<String, Any>> get() = listOf("dropped" to dropped)
    }

    /**
     * `ML7002`: the oldest delivered message awaiting a read receipt was forgotten, as more than
     * 1,000 were tracked; its status stays [DeliveryStatus.Delivered].
     *
     * - Cause: the app sends many messages that request read receipts, and their recipients don't
     *   read them.
     * - Fix: request read receipts only for messages whose reading matters.
     * - Related: [ReadReceiptsBuilder.requestByDefault].
     * - Symptom: messages not delivered.
     */
    public class ReadTrackingEvicted internal constructor(
        messageId: ByteArray,
    ) : DiagnosticEvent("ML7002", Severity.Info) {
        private val id = messageId.copyOf()

        /** A copy of the forgotten message's `message_id`. */
        public val messageId: ByteArray get() = id.copyOf()

        override val fields: List<Pair<String, Any>> get() = listOf("messageId" to id.toHexString())
    }

    /**
     * `ML7003`: a received message was dropped because the messages waiting for the app reached
     * [bound] (ADR 0007 decision 9). Its sender gets no delivery receipt and retries.
     *
     * - Cause: the app does not collect [MeshLink.messages], or collects it slowly.
     * - Fix: collect [MeshLink.messages] for as long as the node runs.
     * - Related: [MeshLink.messages].
     * - Symptom: messages not delivered.
     */
    public class InboxFull internal constructor(
        /** The bound the message would have crossed. */
        public val bound: Bound,
    ) : DiagnosticEvent("ML7003", Severity.Warning) {
        override val fields: List<Pair<String, Any>> get() = listOf("bound" to bound)

        /** A bound of the messages waiting for the app. */
        public enum class Bound {
            /** At most 1,024 messages wait. */
            Count,

            /** At most 1 MiB of payload waits. */
            Bytes,

            /** One sender's messages hold at most a quarter of the payload bytes. */
            Sender,
        }
    }

    /**
     * `ML7004`: this node refused a large fragmented frame with `BUSY`, as the frames it reassembles
     * hold its budget of 512 KiB for large frames across links.
     *
     * - Cause: several peers send large messages through this node at once.
     * - Fix: none needed; the senders retry later. If it repeats, send fewer large messages at once.
     * - Related: spec "Busy reply".
     * - Symptom: messages not delivered.
     */
    public class ReassemblyBudgetExhausted internal constructor() : DiagnosticEvent("ML7004", Severity.Debug) {
        override val fields: List<Pair<String, Any>> get() = emptyList()
    }
}

/**
 * A peer in a [DiagnosticEvent]: 4 bytes of a keyed hash of its NodeId under a key drawn on each
 * [MeshLink.start] (ADR 0020 decision 3). Events of one run name a peer alike; two runs never do,
 * so an exported report doesn't link a phone's sessions.
 */
public class PeerRef internal constructor(
    private val value: Int,
) {
    /** `true` if [other] names the same peer in the same run. */
    override fun equals(other: Any?): Boolean = other is PeerRef && other.value == value

    /** A hash consistent with [equals]. */
    override fun hashCode(): Int = value

    /** `peer-` and 8 lowercase hex digits. */
    override fun toString(): String = "peer-${value.toHexString()}"
}

/**
 * A link whose peer is not yet known in a [DiagnosticEvent]: a counter from 1 on each
 * [MeshLink.start] (ADR 0020 decision 3).
 */
public class LinkRef internal constructor(
    private val number: Int,
) {
    /** `true` if [other] names the same link in the same run. */
    override fun equals(other: Any?): Boolean = other is LinkRef && other.number == number

    /** A hash consistent with [equals]. */
    override fun hashCode(): Int = number

    /** `link-` and the counter. */
    override fun toString(): String = "link-$number"
}
