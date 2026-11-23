@file:OptIn(ExperimentalObjCRefinement::class)

package ch.trancee.meshlink.internal

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/**
 * A typed event about a link or its handshake (ADR 0020). Internal until the diagnostics catalog lands;
 * its [code] stays the same when the event becomes public.
 */
@HiddenFromObjC
public sealed interface LinkDiagnostic {
    /** The catalog code, such as `ML4001`. */
    public val code: String

    /**
     * `ML3003`: a connect as central failed for [reason]; [attempt] counts from 1, and a
     * [RadioFailure.TRANSIENT] failure is retried up to `connect-retries` times.
     */
    public class ConnectFailed(
        /** Why the radio failed it. */
        public val reason: RadioFailure,
        /** The attempt that failed, from 1. */
        public val attempt: Int,
    ) : LinkDiagnostic {
        override val code: String = "ML3003"
    }

    /**
     * `ML3004`: a central's bearer was refused with `RESOURCE_LIMIT`, as the node holds [cap] links as
     * peripheral, or its link budget of [cap] bearers.
     */
    public class LinkCapReached(
        /** The radio's peripheral link cap, or the node's link budget. */
        public val cap: Int,
    ) : LinkDiagnostic {
        override val code: String = "ML3004"
    }

    /**
     * `ML3005`: a central's L2CAP CoC channel to a peer that offered one failed to open for [reason],
     * so the link stays on GATT (spec "Bearer switch").
     */
    public class BearerFallback(
        /** Why the radio failed to open the channel. */
        public val reason: RadioFailure,
    ) : LinkDiagnostic {
        override val code: String = "ML3005"
    }

    /**
     * `ML3007`: an open link dropped a link-local frame of [type] it doesn't handle: an unknown
     * type, or a `REKEY` on an `Unsecured` link (D-48). The link stays open.
     */
    public class UnknownLinkFrameDropped(
        /** The frame's type byte, `0x00`–`0x3F`. */
        public val type: Int,
    ) : LinkDiagnostic {
        override val code: String = "ML3007"
    }

    /** `ML4001`: a link's handshake failed, and the link closed. */
    public class HandshakeFailed(
        /** What failed. */
        public val reason: HandshakeFailure,
    ) : LinkDiagnostic {
        override val code: String = "ML4001"
    }

    /** `ML4002`: a handshake broke a denial-of-service bound, so the bearer closed without a reply (D-20). */
    public class HandshakeLimitDropped(
        /** The bound it broke. */
        public val limit: HandshakeLimit,
    ) : LinkDiagnostic {
        override val code: String = "ML4002"
    }

    /**
     * `ML7002`: the oldest delivered message awaiting a read receipt was forgotten, as more than 1,000
     * were tracked; its status stays [ch.trancee.meshlink.DeliveryStatus.Delivered].
     */
    public class ReadTrackingEvicted(
        messageId: ByteArray,
    ) : LinkDiagnostic {
        private val id = messageId.copyOf()

        /** A copy of the forgotten message's `message_id`. */
        public val messageId: ByteArray get() = id.copyOf()

        override val code: String = "ML7002"
    }
}

/** Why a link handshake failed. */
@HiddenFromObjC
public enum class HandshakeFailure {
    /** Not complete `handshake-timeout` after the bearer opened. */
    TIMEOUT,

    /**
     * A Noise message failed to decrypt or carried an unusable key. A different profile, mesh ID or
     * capability record (prologue) and a different mesh key (psk) all fail this way.
     */
    DECRYPT,

    /** The peer's certificate was missing, invalid, expired, or bound another static key. */
    CERTIFICATE,

    /** A frame other than the next `HANDSHAKE`, or one whose version changed or that is malformed. */
    PROTOCOL,
}

/** The handshake denial-of-service bounds (spec "Handshake limits", D-20). */
@HiddenFromObjC
public enum class HandshakeLimit {
    /** A second handshake on one bearer (`handshake-half-open-per-link`). */
    PER_BEARER,

    /** More than `handshake-half-open-global` handshakes at once. */
    GLOBAL,

    /** A new handshake from one remote address within `handshake-neighbor-interval`. */
    NEIGHBOR_INTERVAL,
}
