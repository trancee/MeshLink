package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineUiState

internal fun runPassiveFullExportAutomationStep(
    snapshot: ReferenceControllerSnapshot,
    timelineUiState: TechnicalTimelineUiState,
    actions: LiveProofAutomationActions,
    progress: LiveProofAutomationProgress,
): Unit {
    val trustEstablished =
        hasTimelineEntry(snapshot, title = "TRUST_ESTABLISHED") || hasTrustedSelectedPeer(snapshot)
    val inboundCount = timelineEntryCount(snapshot, title = "Inbound message")
    val inboundReady = inboundCount >= 1
    announcePassiveObservationIfNeeded(snapshot = snapshot, actions = actions, progress = progress)

    if (!progress.fullExportRequested && trustEstablished && inboundReady) {
        actions.emitAutomationLog(
            "REFERENCE_AUTOMATION export.requested role=passive policy=full-payload"
        )
        actions.requestExportCurrentSession(ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN)
        progress.fullExportRequested = true
    }
    trackExportPath(
        currentExportPath = timelineUiState.lastExportPath,
        targetPolicy = ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN,
        actions = actions,
        progress = progress,
    )

    if (progress.fullExportPath != null && !progress.retainRequested) {
        actions.emitAutomationLog(
            "REFERENCE_AUTOMATION session.end.requested role=passive inboundCount=$inboundCount"
        )
        actions.requestEndCurrentSession()
        progress.retainRequested = true
    }
    if (
        progress.fullExportPath != null &&
            shouldExportPassiveLiveProof(
                retainRequested = progress.retainRequested,
                exportRequested = progress.exportRequested,
                retainedSessionCount = timelineUiState.retainedSessions.size,
            )
    ) {
        actions.emitAutomationLog(
            "REFERENCE_AUTOMATION export.requested role=passive policy=redacted-preview"
        )
        actions.requestExportCurrentSession(ExportPayloadPolicy.REDACTED_PREVIEW)
        progress.exportRequested = true
    }
    trackExportPath(
        currentExportPath = timelineUiState.lastExportPath,
        targetPolicy = ExportPayloadPolicy.REDACTED_PREVIEW,
        actions = actions,
        progress = progress,
    )

    if (
        !progress.completionLogged &&
            progress.fullExportPath != null &&
            progress.redactedExportPath != null
    ) {
        actions.emitAutomationLog(
            "REFERENCE_AUTOMATION proof.complete role=passive " +
                "inbound=$inboundReady " +
                "inboundCount=$inboundCount " +
                "trust=$trustEstablished " +
                "fullExport=${progress.fullExportPath} " +
                "export=${progress.redactedExportPath}"
        )
        progress.completionLogged = true
    }
}

internal fun trackExportPath(
    currentExportPath: String?,
    targetPolicy: ExportPayloadPolicy,
    actions: LiveProofAutomationActions,
    progress: LiveProofAutomationProgress,
): String? {
    if (currentExportPath == null) {
        return when (targetPolicy) {
            ExportPayloadPolicy.REDACTED_PREVIEW -> progress.redactedExportPath
            ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN -> progress.fullExportPath
        }
    }
    when (targetPolicy) {
        ExportPayloadPolicy.REDACTED_PREVIEW -> {
            if (
                progress.redactedExportPath != null || currentExportPath == progress.fullExportPath
            ) {
                return progress.redactedExportPath
            }
            progress.lastObservedExportPath = currentExportPath
            progress.redactedExportPath = currentExportPath
            actions.emitAutomationLog(
                "REFERENCE_AUTOMATION export.completed role=passive policy=redacted-preview path=$currentExportPath"
            )
            return progress.redactedExportPath
        }
        ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN -> {
            if (
                progress.fullExportPath != null || currentExportPath == progress.redactedExportPath
            ) {
                return progress.fullExportPath
            }
            progress.lastObservedExportPath = currentExportPath
            progress.fullExportPath = currentExportPath
            actions.emitAutomationLog(
                "REFERENCE_AUTOMATION export.completed role=passive policy=full-payload path=$currentExportPath"
            )
            return progress.fullExportPath
        }
    }
}
