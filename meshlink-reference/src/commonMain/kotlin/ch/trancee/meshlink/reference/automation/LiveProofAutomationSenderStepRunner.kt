package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot

internal fun runSenderAutomationStep(
    snapshot: ReferenceControllerSnapshot,
    automationConfig: ReferenceAutomationConfig,
    actions: LiveProofAutomationActions,
    progress: LiveProofAutomationProgress,
): Unit {
    if (
        !progress.meshStartRequested &&
            automationConfig.benchmarkTransport.equals("meshlink", ignoreCase = true)
    ) {
        actions.emitAutomationLog(
            "REFERENCE_AUTOMATION mesh.start.requested role=${automationConfig.role} meshState=${snapshot.session.meshStateLabel} readinessBlockers=${actions.readinessBlockers.joinToString(separator = "|")}"
        )
        actions.requestMeshStart()
        progress.meshStartRequested = true
    }
    when (automationConfig.scenario) {
        ReferenceAutomationScenario.DIRECT_PAUSE_RESUME ->
            runPauseResumeSenderAutomationStep(snapshot, automationConfig, actions, progress)
        ReferenceAutomationScenario.DIRECT_TRUST_RESET_RECOVERY ->
            runTrustResetRecoverySenderAutomationStep(snapshot, automationConfig, actions, progress)
        ReferenceAutomationScenario.DIRECT_RESTART_RECOVERY,
        ReferenceAutomationScenario.DIRECT_ISOLATION_RECOVERY,
        ReferenceAutomationScenario.DIRECT_ROUTE_BREAK_RECOVERY,
        ReferenceAutomationScenario.DIRECT_LARGE_TRANSFER ->
            runDirectSenderAutomationStep(
                snapshot = snapshot,
                automationConfig = automationConfig,
                actions = actions,
                progress = progress,
                payloadPlan =
                    if (
                        automationConfig.scenario ==
                            ReferenceAutomationScenario.DIRECT_LARGE_TRANSFER
                    ) {
                        SenderPayloadPlan.LARGE_TRANSFER
                    } else {
                        SenderPayloadPlan.GUIDED_HELLO
                    },
            )
        ReferenceAutomationScenario.RELAY_CONSTRAINED ->
            runRelaySenderAutomationStep(snapshot, automationConfig, actions, progress)
        ReferenceAutomationScenario.DIRECT_FULL_EXPORT,
        ReferenceAutomationScenario.DIRECT_GUIDED ->
            runDirectSenderAutomationStep(
                snapshot = snapshot,
                automationConfig = automationConfig,
                actions = actions,
                progress = progress,
                payloadPlan = SenderPayloadPlan.GUIDED_HELLO,
            )
    }
}
