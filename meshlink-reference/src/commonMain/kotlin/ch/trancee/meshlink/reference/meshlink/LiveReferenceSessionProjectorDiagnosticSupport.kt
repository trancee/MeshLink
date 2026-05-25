package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticEvent
import ch.trancee.meshlink.reference.model.PeerTrustState
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity

internal fun recordProjectedDiagnostic(
    stateStore: ReferenceControllerStateStore,
    runtimeLogger: (String) -> Unit,
    event: DiagnosticEvent,
): Unit {
    val detail = diagnosticDetail(event)
    runtimeLogger(
        "REFERENCE_RUNTIME diagnostic " +
            "code=${event.code} " +
            "stage=${event.stage} " +
            "peer=${event.peerSuffix ?: "none"} " +
            "detail=$detail"
    )
    stateStore.appendEvent(
        ReferenceTimelineEvent(
            family = TimelineFamily.DIAGNOSTIC,
            severity = event.severity.toTimelineSeverity(),
            title = event.code.name,
            detail = detail,
            peerSuffix = event.peerSuffix,
        )
    )
    when (event.code) {
        DiagnosticCode.TRUST_ESTABLISHED ->
            updatePeerTrustState(
                stateStore = stateStore,
                peerSuffix = event.peerSuffix,
                trustState = PeerTrustState.TRUSTED,
            )
        DiagnosticCode.TRUST_FAILURE ->
            updatePeerTrustState(
                stateStore = stateStore,
                peerSuffix = event.peerSuffix,
                trustState = PeerTrustState.CHANGED,
            )
        DiagnosticCode.POWER_MODE_CHANGED -> {
            stateStore.updateActivePowerModeLabel(
                event.metadata["tier"] ?: stateStore.currentSnapshot.activePowerModeLabel
            )
        }

        else -> Unit
    }
}

private fun diagnosticDetail(event: DiagnosticEvent): String {
    return buildString {
        append(event.code)
        append(" @ ")
        append(event.stage)
        if (event.metadata.isNotEmpty()) {
            append(" ")
            append(
                event.metadata.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
                    "$key=$value"
                }
            )
        }
    }
}

private fun ch.trancee.meshlink.diagnostics.DiagnosticSeverity.toTimelineSeverity():
    TimelineSeverity {
    return when (this) {
        ch.trancee.meshlink.diagnostics.DiagnosticSeverity.DEBUG -> TimelineSeverity.DEBUG
        ch.trancee.meshlink.diagnostics.DiagnosticSeverity.INFO -> TimelineSeverity.INFO
        ch.trancee.meshlink.diagnostics.DiagnosticSeverity.WARN -> TimelineSeverity.WARNING
        ch.trancee.meshlink.diagnostics.DiagnosticSeverity.ERROR -> TimelineSeverity.ERROR
    }
}
