package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot

internal fun runSenderAutomationStep(
    snapshot: ReferenceControllerSnapshot,
    automationConfig: ReferenceAutomationConfigView,
    actions: LiveProofAutomationActions,
    progress: LiveProofAutomationProgress,
): Unit {
    if (!progress.meshStartRequested) {
        actions.emitAutomationLog(
            "REFERENCE_AUTOMATION mesh.start.requested role=${automationConfig.role} meshState=${snapshot.session.meshStateLabel} readinessBlockers=${actions.readinessBlockers.joinToString(separator = "|")}"
        )
        actions.requestMeshStart()
        progress.meshStartRequested = true
    }
    when (automationConfig.scenario) {
        AUTOMATION_SCENARIO_DIRECT_PAUSE_RESUME ->
            runPauseResumeSenderAutomationStep(snapshot, automationConfig, actions, progress)
        AUTOMATION_SCENARIO_DIRECT_TRUST_RESET_RECOVERY ->
            runTrustResetRecoverySenderAutomationStep(snapshot, automationConfig, actions, progress)
        AUTOMATION_SCENARIO_DIRECT_RESTART_RECOVERY,
        AUTOMATION_SCENARIO_DIRECT_ISOLATION_RECOVERY,
        AUTOMATION_SCENARIO_DIRECT_ROUTE_BREAK_RECOVERY,
        AUTOMATION_SCENARIO_DIRECT_LARGE_TRANSFER ->
            runDirectSenderAutomationStep(
                snapshot = snapshot,
                automationConfig = automationConfig,
                actions = actions,
                progress = progress,
                payloadPlan =
                    if (automationConfig.scenario == AUTOMATION_SCENARIO_DIRECT_LARGE_TRANSFER) {
                        SenderPayloadPlan.LARGE_TRANSFER
                    } else {
                        SenderPayloadPlan.GUIDED_HELLO
                    },
            )
        AUTOMATION_SCENARIO_RELAY_CONSTRAINED ->
            runRelaySenderAutomationStep(snapshot, automationConfig, actions, progress)
        AUTOMATION_SCENARIO_DIRECT_FULL_EXPORT,
        AUTOMATION_SCENARIO_DIRECT_GUIDED ->
            runDirectSenderAutomationStep(
                snapshot = snapshot,
                automationConfig = automationConfig,
                actions = actions,
                progress = progress,
                payloadPlan = SenderPayloadPlan.GUIDED_HELLO,
            )
    }
}
