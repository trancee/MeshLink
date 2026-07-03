package ch.trancee.meshlink.diagnostics

/** Stable diagnostic event codes emitted by MeshLink. */
public enum class DiagnosticCode {
    MESH_STARTED,
    MESH_PAUSED,
    MESH_RESUMED,
    MESH_STOPPED,
    TRUST_ESTABLISHED,
    TRUST_FAILURE,
    HOP_SESSION_ESTABLISHED,
    HOP_SESSION_FAILED,
    ROUTE_DISCOVERED,
    ROUTE_UPDATED,
    ROUTE_RETRACTED,
    ROUTE_EXPIRED,
    ROUTE_CONVERGED,
    NO_ROUTE_AVAILABLE,
    DELIVERY_QUEUED,
    DELIVERY_RETRY_SCHEDULED,
    DELIVERY_RETRYING,
    DELIVERY_SUCCEEDED,
    DELIVERY_UNREACHABLE,
    TRANSFER_STARTED,
    TRANSFER_PROGRESS,
    TRANSFER_COMPLETED,
    TRANSFER_FAILED,
    SIZE_LIMIT_REJECTED,
    TRANSPORT_MODE_CHANGED,
    POWER_MODE_CHANGED,
    TRANSPORT_FRAME_REJECTED,
    DISCOVERY_ADVERTISE_FAILED,
    DISCOVERY_SCAN_FAILED,
}

/** Operator-facing severity level assigned to a [DiagnosticEvent]. */
public enum class DiagnosticSeverity {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

/** High-level reason bucket attached to a [DiagnosticEvent]. */
public enum class DiagnosticReason {
    STATE_CHANGE,
    TRUST_FAILURE,
    ROUTE_CHANGE,
    DELIVERY_RETRY,
    DELIVERY_FAILURE,
    TRANSFER_FAILURE,
    POWER_CHANGE,
    SIZE_LIMIT,
    TRANSPORT_CHANGE,
}

/**
 * Structured diagnostic record emitted by MeshLink.
 *
 * [stage] identifies the emitting subsystem path. [metadata] carries machine-readable context for
 * operators and host-side tooling.
 */
public class DiagnosticEvent
public constructor(
    public val code: DiagnosticCode,
    public val severity: DiagnosticSeverity,
    public val stage: String,
    public val peerSuffix: String? = null,
    public val reason: DiagnosticReason? = null,
    metadata: Map<String, String> = emptyMap(),
) {
    /** Machine-readable context attached to this diagnostic event. */
    public val metadata: Map<String, String> = LinkedHashMap(metadata)

    override fun toString(): String {
        return "DiagnosticEvent(code=$code, severity=$severity, stage=$stage, peerSuffix=$peerSuffix, reason=$reason)"
    }
}

internal interface DiagnosticSink {
    public fun emit(event: DiagnosticEvent): Unit
}
