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
