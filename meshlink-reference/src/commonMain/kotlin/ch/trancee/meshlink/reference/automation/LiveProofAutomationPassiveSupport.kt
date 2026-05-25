package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineUiState

internal fun runPassiveAutomationStep(
    snapshot: ReferenceControllerSnapshot,
    timelineUiState: TechnicalTimelineUiState,
    automationConfig: ReferenceAutomationConfig,
    actions: LiveProofAutomationActions,
    progress: LiveProofAutomationProgress,
): Unit {
    when (automationConfig.scenario) {
        ReferenceAutomationScenario.DIRECT_FULL_EXPORT ->
            runPassiveFullExportAutomationStep(snapshot, timelineUiState, actions, progress)
        ReferenceAutomationScenario.DIRECT_TRUST_RESET_RECOVERY ->
            runPassiveBaselineAutomationStep(
                snapshot = snapshot,
                timelineUiState = timelineUiState,
                actions = actions,
                progress = progress,
                requiredInboundCount = REQUIRED_RECOVERY_INBOUND_COUNT,
            )
        ReferenceAutomationScenario.DIRECT_LARGE_TRANSFER ->
            runPassiveBaselineAutomationStep(
                snapshot = snapshot,
                timelineUiState = timelineUiState,
                actions = actions,
                progress = progress,
                requiredInboundCount = 1,
                requiredLargestInboundBytes = largeTransferPayloadBytes(actions.platformName),
            )
        ReferenceAutomationScenario.DIRECT_PAUSE_RESUME ->
            runPassiveBaselineAutomationStep(
                snapshot = snapshot,
                timelineUiState = timelineUiState,
                actions = actions,
                progress = progress,
                requiredInboundCount = REQUIRED_PAUSE_RESUME_INBOUND_COUNT,
            )
        ReferenceAutomationScenario.RELAY_CONSTRAINED,
        ReferenceAutomationScenario.DIRECT_GUIDED ->
            runPassiveBaselineAutomationStep(
                snapshot = snapshot,
                timelineUiState = timelineUiState,
                actions = actions,
                progress = progress,
            )
    }
}

internal fun runRelayAutomationStep(
    snapshot: ReferenceControllerSnapshot,
    actions: LiveProofAutomationActions,
    progress: LiveProofAutomationProgress,
): Unit {
    val observation =
        latestAutomationObservation(
            snapshot = snapshot,
            families = setOf(TimelineFamily.DIAGNOSTIC, TimelineFamily.TRANSFER),
        ) ?: return
    if (progress.lastRelayObservationEntryId == observation.entryId) {
        return
    }
    actions.emitAutomationLog(
        "REFERENCE_AUTOMATION relay.observed role=relay " +
            "family=${observation.family} " +
            "title=${observation.title} " +
            "peer=${observation.peerSuffix ?: "none"} " +
            "detail=${observation.detail}"
    )
    progress.lastRelayObservationEntryId = observation.entryId
}

private fun runPassiveBaselineAutomationStep(
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

private fun runPassiveFullExportAutomationStep(
    snapshot: ReferenceControllerSnapshot,
    timelineUiState: TechnicalTimelineUiState,
    actions: LiveProofAutomationActions,
    progress: LiveProofAutomationProgress,
): Unit {
    val trustEstablished = hasTimelineEntry(snapshot, title = "TRUST_ESTABLISHED")
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

private fun trackExportPath(
    currentExportPath: String?,
    targetPolicy: ExportPayloadPolicy,
    actions: LiveProofAutomationActions,
    progress: LiveProofAutomationProgress,
): String? {
    if (currentExportPath == null || currentExportPath == progress.lastObservedExportPath) {
        return when (targetPolicy) {
            ExportPayloadPolicy.REDACTED_PREVIEW -> progress.redactedExportPath
            ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN -> progress.fullExportPath
        }
    }
    progress.lastObservedExportPath = currentExportPath
    when (targetPolicy) {
        ExportPayloadPolicy.REDACTED_PREVIEW -> {
            if (currentExportPath == progress.fullExportPath) {
                return progress.redactedExportPath
            }
            progress.redactedExportPath = currentExportPath
            actions.emitAutomationLog(
                "REFERENCE_AUTOMATION export.completed role=passive policy=redacted-preview path=$currentExportPath"
            )
            return progress.redactedExportPath
        }
        ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN -> {
            if (currentExportPath == progress.redactedExportPath) {
                return progress.fullExportPath
            }
            progress.fullExportPath = currentExportPath
            actions.emitAutomationLog(
                "REFERENCE_AUTOMATION export.completed role=passive policy=full-payload path=$currentExportPath"
            )
            return progress.fullExportPath
        }
    }
}

private fun announcePassiveObservationIfNeeded(
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
