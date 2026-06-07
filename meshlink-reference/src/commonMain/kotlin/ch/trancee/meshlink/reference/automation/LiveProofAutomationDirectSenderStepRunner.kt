package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot

internal fun runDirectSenderAutomationStep(
    snapshot: ReferenceControllerSnapshot,
    automationConfig: ReferenceAutomationConfig,
    actions: LiveProofAutomationActions,
    progress: LiveProofAutomationProgress,
    payloadPlan: SenderPayloadPlan,
): Unit {
    if (snapshot.peers.isEmpty()) {
        if (!progress.senderPeerWaitLogged) {
            actions.emitAutomationLog(
                "REFERENCE_AUTOMATION sender.waiting role=sender reason=no-peers"
            )
            progress.senderPeerWaitLogged = true
        }
        return
    }
    val targetPeer =
        autoSendTargetPeer(
            snapshot = snapshot,
            requiredPeerCount = automationConfig.requiredPeerCount,
            targetPeerIndex = automationConfig.targetPeerIndex,
            targetPeerId = automationConfig.targetPeerId,
        ) ?: return
    if (!isMeshRunning(snapshot.session.meshStateLabel)) {
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
            lastOutcomeSummary == "SendResult.Sent" &&
            deliveryDetail != null
    ) {
        val completionSuffix =
            when (payloadPlan) {
                SenderPayloadPlan.GUIDED_HELLO,
                SenderPayloadPlan.RECOVERY_HELLO -> ""
                SenderPayloadPlan.LARGE_TRANSFER ->
                    " bytes=${largeTransferPayloadBytes(actions.platformName)}"
            }
        actions.emitAutomationLog(
            "REFERENCE_AUTOMATION proof.complete role=sender " +
                "outcome=$lastOutcomeSummary " +
                "peer=$targetPeerSuffix " +
                "delivery=$deliveryDetail$completionSuffix"
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
