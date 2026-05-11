package ch.trancee.meshlink.diagnostics

internal object DiagnosticCatalog {
    internal fun severityFor(code: DiagnosticCode): DiagnosticSeverity {
        return when (code) {
            DiagnosticCode.MESH_STARTED,
            DiagnosticCode.MESH_PAUSED,
            DiagnosticCode.MESH_RESUMED,
            DiagnosticCode.MESH_STOPPED,
            DiagnosticCode.TRUST_ESTABLISHED,
            DiagnosticCode.ROUTE_DISCOVERED,
            DiagnosticCode.ROUTE_CONVERGED,
            DiagnosticCode.DELIVERY_QUEUED,
            DiagnosticCode.DELIVERY_SUCCEEDED,
            DiagnosticCode.TRANSFER_STARTED,
            DiagnosticCode.TRANSFER_COMPLETED,
            DiagnosticCode.TRANSPORT_MODE_CHANGED,
            DiagnosticCode.POWER_MODE_CHANGED,
            -> DiagnosticSeverity.INFO

            DiagnosticCode.HOP_SESSION_ESTABLISHED,
            DiagnosticCode.ROUTE_UPDATED,
            DiagnosticCode.TRANSFER_PROGRESS,
            -> DiagnosticSeverity.DEBUG

            DiagnosticCode.HOP_SESSION_FAILED,
            DiagnosticCode.ROUTE_RETRACTED,
            DiagnosticCode.ROUTE_EXPIRED,
            DiagnosticCode.NO_ROUTE_AVAILABLE,
            DiagnosticCode.DELIVERY_RETRY_SCHEDULED,
            DiagnosticCode.DELIVERY_RETRYING,
            DiagnosticCode.SIZE_LIMIT_REJECTED,
            -> DiagnosticSeverity.WARN

            DiagnosticCode.TRUST_FAILURE,
            DiagnosticCode.DELIVERY_UNREACHABLE,
            DiagnosticCode.TRANSFER_FAILED,
            -> DiagnosticSeverity.ERROR
        }
    }
}
