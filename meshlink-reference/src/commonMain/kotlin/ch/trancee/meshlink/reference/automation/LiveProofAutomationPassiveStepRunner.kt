package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineUiState

internal fun runPassiveAutomationStep(
    snapshot: ReferenceControllerSnapshot,
    timelineUiState: TechnicalTimelineUiState,
    automationConfig: ReferenceAutomationConfigView,
    actions: LiveProofAutomationActions,
    progress: LiveProofAutomationProgress,
): Unit {
    when (automationConfig.scenario) {
        AUTOMATION_SCENARIO_DIRECT_FULL_EXPORT ->
            runPassiveFullExportAutomationStep(snapshot, timelineUiState, actions, progress)
        AUTOMATION_SCENARIO_DIRECT_TRUST_RESET_RECOVERY ->
            runPassiveBaselineAutomationStep(
                snapshot = snapshot,
                timelineUiState = timelineUiState,
                actions = actions,
                progress = progress,
                requiredInboundCount = REQUIRED_RECOVERY_INBOUND_COUNT,
            )
        AUTOMATION_SCENARIO_DIRECT_LARGE_TRANSFER ->
            runPassiveBaselineAutomationStep(
                snapshot = snapshot,
                timelineUiState = timelineUiState,
                actions = actions,
                progress = progress,
                requiredInboundCount = 1,
                requiredLargestInboundBytes = largeTransferPayloadBytes(actions.platformName),
            )
        AUTOMATION_SCENARIO_DIRECT_PAUSE_RESUME ->
            runPassiveBaselineAutomationStep(
                snapshot = snapshot,
                timelineUiState = timelineUiState,
                actions = actions,
                progress = progress,
                requiredInboundCount = REQUIRED_PAUSE_RESUME_INBOUND_COUNT,
            )
        AUTOMATION_SCENARIO_DIRECT_RESTART_RECOVERY,
        AUTOMATION_SCENARIO_DIRECT_ISOLATION_RECOVERY,
        AUTOMATION_SCENARIO_DIRECT_ROUTE_BREAK_RECOVERY,
        AUTOMATION_SCENARIO_RELAY_CONSTRAINED,
        AUTOMATION_SCENARIO_DIRECT_GUIDED ->
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
