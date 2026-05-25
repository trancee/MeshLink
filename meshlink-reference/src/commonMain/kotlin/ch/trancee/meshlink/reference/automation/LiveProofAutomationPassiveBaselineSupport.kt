package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineUiState

internal fun runPassiveBaselineAutomationStep(
    snapshot: ReferenceControllerSnapshot,
    timelineUiState: TechnicalTimelineUiState,
    actions: LiveProofAutomationActions,
    progress: LiveProofAutomationProgress,
    requiredInboundCount: Int = 1,
    requiredLargestInboundBytes: Int? = null,
): Unit {
    val trustEstablished = hasTimelineEntry(snapshot, title = "TRUST_ESTABLISHED")
    val inboundCount = timelineEntryCount(snapshot, title = "Inbound message")
    val largestInboundBytes = largestInboundPayloadBytes(snapshot)
    val inboundReady =
        inboundCount >= requiredInboundCount &&
            (requiredLargestInboundBytes == null ||
                (largestInboundBytes ?: 0) >= requiredLargestInboundBytes)
    announcePassiveObservationIfNeeded(snapshot = snapshot, actions = actions, progress = progress)

    if (
        shouldRetainPassiveLiveProof(
            retainRequested = progress.retainRequested,
            hasTrustEstablished = trustEstablished,
            hasInboundMessage = inboundReady,
        )
    ) {
        actions.emitAutomationLog(
            "REFERENCE_AUTOMATION session.end.requested role=passive inboundCount=$inboundCount"
        )
        actions.requestEndCurrentSession()
        progress.retainRequested = true
    }

    if (
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

    val exportPath =
        trackExportPath(
            currentExportPath = timelineUiState.lastExportPath,
            targetPolicy = ExportPayloadPolicy.REDACTED_PREVIEW,
            actions = actions,
            progress = progress,
        )
    if (!progress.completionLogged && exportPath != null) {
        val largestInboundText =
            largestInboundBytes?.let { bytes -> " largestInboundBytes=$bytes" }.orEmpty()
        actions.emitAutomationLog(
            "REFERENCE_AUTOMATION proof.complete role=passive " +
                "inbound=$inboundReady " +
                "inboundCount=$inboundCount " +
                "trust=$trustEstablished " +
                "export=$exportPath$largestInboundText"
        )
        progress.completionLogged = true
    }
}

internal fun announcePassiveObservationIfNeeded(
    snapshot: ReferenceControllerSnapshot,
    actions: LiveProofAutomationActions,
    progress: LiveProofAutomationProgress,
): Unit {
    val observation = latestAutomationObservation(snapshot = snapshot) ?: return
    if (progress.lastPassiveObservationEntryId == observation.entryId) {
        return
    }
    actions.emitAutomationLog(
        "REFERENCE_AUTOMATION passive.observed role=passive " +
            "family=${observation.family} " +
            "title=${observation.title} " +
            "peer=${observation.peerSuffix ?: "none"} " +
            "detail=${observation.detail}"
    )
    progress.lastPassiveObservationEntryId = observation.entryId
}
