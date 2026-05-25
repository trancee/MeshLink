package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.redactedSuffix

internal fun runRelaySenderAutomationStep(
    snapshot: ReferenceControllerSnapshot,
    automationConfig: ReferenceAutomationConfig,
    actions: LiveProofAutomationActions,
    progress: LiveProofAutomationProgress,
): Unit {
    val targetPeer =
        autoSendTargetPeer(
            snapshot = snapshot,
            requiredPeerCount = automationConfig.requiredPeerCount,
            targetPeerIndex = automationConfig.targetPeerIndex,
            targetPeerId = automationConfig.targetPeerId,
        )
    val bootstrapPeer =
        bootstrapTargetPeer(snapshot = snapshot, targetPeerId = automationConfig.targetPeerId)
    if (!isMeshRunning(snapshot.session.meshStateLabel)) {
        return
    }
    if (
        !progress.bootstrapRequested &&
            !progress.sendRequested &&
            targetPeer == null &&
            bootstrapPeer != null
    ) {
        actions.emitAutomationLog(
            "REFERENCE_AUTOMATION bootstrap.requested role=sender " +
                "peer=${bootstrapPeer.peerSuffix} " +
                "targetPeerId=${automationConfig.targetPeerId ?: "auto"}"
        )
        requestSenderPayload(
            phase = "bootstrap",
            targetPeer = bootstrapPeer,
            payloadPlan = SenderPayloadPlan.GUIDED_HELLO,
            automationConfig = automationConfig,
            actions = actions,
        )
        progress.bootstrapRequested = true
    }
    announceBootstrapObservationIfNeeded(
        snapshot = snapshot,
        bootstrapPeer = bootstrapPeer,
        actions = actions,
        progress = progress,
    )
    if (
        !progress.sendRequested &&
            targetPeer != null &&
            shouldAutoSendGuidedHello(
                snapshot = snapshot,
                requiredPeerCount = automationConfig.requiredPeerCount,
                targetPeerIndex = automationConfig.targetPeerIndex,
                targetPeerId = automationConfig.targetPeerId,
            )
    ) {
        requestSenderPayload(
            phase = "primary",
            targetPeer = targetPeer,
            payloadPlan = SenderPayloadPlan.GUIDED_HELLO,
            automationConfig = automationConfig,
            actions = actions,
        )
        progress.sendRequested = true
    }

    val targetPeerSuffix =
        targetPeer?.peerSuffix ?: automationConfig.targetPeerId?.let(::redactedSuffix)
    announceSenderObservationIfNeeded(
        snapshot = snapshot,
        targetPeerSuffix = targetPeerSuffix,
        actions = actions,
        progress = progress,
    )
    announceSenderOutcomeIfNeeded(snapshot = snapshot, actions = actions, progress = progress)

    val lastOutcomeSummary = snapshot.session.lastOutcomeSummary
    val deliveryDetail =
        latestSenderDeliveryDetail(snapshot = snapshot, peerSuffix = targetPeerSuffix)
    if (
        progress.sendRequested &&
            !progress.completionLogged &&
            lastOutcomeSummary == "SendResult.Sent" &&
            deliveryDetail != null
    ) {
        actions.emitAutomationLog(
            "REFERENCE_AUTOMATION proof.complete role=sender " +
                "outcome=$lastOutcomeSummary " +
                "peer=${targetPeerSuffix ?: "none"} " +
                "delivery=$deliveryDetail"
        )
        progress.completionLogged = true
    }
    if (
        progress.sendRequested &&
            !progress.completionLogged &&
            lastOutcomeSummary != null &&
            isTerminalSenderFailureOutcome(lastOutcomeSummary)
    ) {
        emitSenderFailure(
            snapshot = snapshot,
            targetPeerSuffix = targetPeerSuffix,
            actions = actions,
            progress = progress,
        )
    }
}
