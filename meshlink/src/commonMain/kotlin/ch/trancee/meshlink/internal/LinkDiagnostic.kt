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

    /** `ML3004`: a central's bearer was refused with `RESOURCE_LIMIT`, as the node holds [cap] links as peripheral. */
    public class LinkCapReached(
        /** The radio's peripheral link cap. */
        public val cap: Int,
    ) : LinkDiagnostic {
        override val code: String = "ML3004"
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
