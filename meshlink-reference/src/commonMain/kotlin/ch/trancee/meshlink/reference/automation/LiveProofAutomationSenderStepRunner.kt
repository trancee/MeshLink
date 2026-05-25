package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot

internal fun runSenderAutomationStep(
    snapshot: ReferenceControllerSnapshot,
    automationConfig: ReferenceAutomationConfig,
    actions: LiveProofAutomationActions,
    progress: LiveProofAutomationProgress,
): Unit {
    when (automationConfig.scenario) {
        ReferenceAutomationScenario.DIRECT_PAUSE_RESUME ->
            runPauseResumeSenderAutomationStep(snapshot, automationConfig, actions, progress)
        ReferenceAutomationScenario.DIRECT_TRUST_RESET_RECOVERY ->
            runTrustResetRecoverySenderAutomationStep(snapshot, automationConfig, actions, progress)
        ReferenceAutomationScenario.DIRECT_LARGE_TRANSFER ->
            runDirectSenderAutomationStep(
                snapshot = snapshot,
                automationConfig = automationConfig,
                actions = actions,
                progress = progress,
                payloadPlan = SenderPayloadPlan.LARGE_TRANSFER,
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
