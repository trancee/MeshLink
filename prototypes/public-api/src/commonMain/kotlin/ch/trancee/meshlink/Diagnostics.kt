package ch.trancee.meshlink

import kotlin.time.Instant

/**
 * One diagnostic event from [MeshLink.diagnostics]. Fields never contain keys, payloads or full
 * node identities.
 *
 * @property code stable identifier with documented cause and remedy.
 * @property severity importance; equal to [DiagnosticCode.severity].
 * @property fields redacted key/value detail.
 * @property timestamp when the event happened.
 * @sample ch.trancee.meshlink.samples.watchDiagnostics
 */
public class DiagnosticEvent internal constructor(
    public val code: DiagnosticCode,
    public val fields: Map<String, String>,
    public val timestamp: Instant,
) {
    /** Importance; equal to [DiagnosticCode.severity]. */
    public val severity: Severity get() = code.severity

    override fun toString(): String = "DiagnosticEvent(${code.id} ${code.name}, $fields)"
}

/** Diagnostic importance. */
public enum class Severity {
    /** Debug detail; only emitted when [MeshLinkConfig.debugDiagnostics] is on. */
    Debug,

    /** Normal operation worth recording. */
    Info,

    /** Degraded behavior the application may want to act on. */
    Warning,
}

/**
 * Stable diagnostic codes. [id]s are in 1000–1999, disjoint from [RejectionReason] (100–199) and
 * [FailureReason] (200–299). Each code documents its cause, remedy and related setting.
 */
public enum class DiagnosticCode(public val id: Int, public val severity: Severity) {
    /**
     * Cause: the node started with [SecurityProfile.None]. Remedy: use a secured profile outside
     * development. Setting: `securityProfile`.
     */
    SecurityProfileNone(1001, Severity.Warning),

    /**
     * Cause: a direct peer uses a different mesh ID, security profile or mesh key. Remedy: align
     * the three on every node. Fields: `peer` (redacted), `mismatch`.
     */
    PeerRejectedMeshMismatch(1101, Severity.Warning),

    /**
     * Cause: one sender exceeded its quarter of the inbox; the message was dropped without
     * acknowledgement and the sender retries. Remedy: collect [MeshLink.messages] faster.
     * Setting: `inboxCapacity`. Fields: `sender` (redacted).
     */
    InboxSenderShareExceeded(1201, Severity.Warning),

    /**
     * Cause: the inbox is full; the message was dropped without acknowledgement. Remedy: collect
     * [MeshLink.messages]. Setting: `inboxCapacity`.
     */
    InboxFull(1202, Severity.Warning),

    /** Cause: a held message reached its lifetime. Setting: `messageLifetimeSeconds`. */
    MessageExpired(1301, Severity.Info),

    /** Cause: the node is blocked by missing requirements. Fields: `requirements`. */
    StartBlocked(1401, Severity.Warning),

    /**
     * Cause: the diagnostics buffer overflowed; older events were dropped. Remedy: collect
     * diagnostics continuously. Setting: `diagnosticsBufferCapacity`. Fields: `dropped`.
     */
    DiagnosticsOverflow(1901, Severity.Warning),
}
