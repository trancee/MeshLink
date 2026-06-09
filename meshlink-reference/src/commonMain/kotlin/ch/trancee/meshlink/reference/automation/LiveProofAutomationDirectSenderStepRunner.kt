package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot

internal fun runDirectSenderAutomationStep(
    snapshot: ReferenceControllerSnapshot,
    automationConfig: ReferenceAutomationConfig,
    actions: LiveProofAutomationActions,
    progress: LiveProofAutomationProgress,
    payloadPlan: SenderPayloadPlan,
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

    if (targetPeer == null) {
        if (!progress.senderPeerWaitLogged) {
            actions.emitAutomationLog(
                "REFERENCE_AUTOMATION sender.waiting role=sender reason=no-peers"
            )
            progress.senderPeerWaitLogged = true
        }
        if (!progress.bootstrapRequested && bootstrapPeer != null) {
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
        return
    }

    if (!progress.sendRequested) {
        requestSenderPayload(
            phase = "primary",
            targetPeer = targetPeer,
            payloadPlan = payloadPlan,
            automationConfig = automationConfig,
            actions = actions,
        )
        progress.sendRequested = true
    }

    val targetPeerSuffix = targetPeer.peerSuffix
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
            lastOutcomeSummary == "SendResult.Sent"
    ) {
        val completionSuffix =
            when (payloadPlan) {
                SenderPayloadPlan.GUIDED_HELLO,
                SenderPayloadPlan.RECOVERY_HELLO -> ""
                SenderPayloadPlan.LARGE_TRANSFER ->
                    " bytes=${largeTransferPayloadBytes(actions.platformName)}"
            }
        val deliveryText = deliveryDetail?.let { " delivery=$it" }.orEmpty()
        actions.emitAutomationLog(
            "REFERENCE_AUTOMATION proof.complete role=sender " +
                "outcome=$lastOutcomeSummary " +
                "peer=$targetPeerSuffix$deliveryText$completionSuffix"
        )
        progress.completionLogged = true
    }
    if (
        progress.sendRequested &&
            !progress.completionLogged &&
            lastOutcomeSummary == "SendResult.NotSent(UNREACHABLE)"
    ) {
        return
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
