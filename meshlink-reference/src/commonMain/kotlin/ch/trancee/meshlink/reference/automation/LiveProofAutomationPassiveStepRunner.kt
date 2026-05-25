package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.TimelineFamily
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
