package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot

internal fun runTrustResetRecoverySenderAutomationStep(
    snapshot: ReferenceControllerSnapshot,
    automationConfig: ReferenceAutomationConfigView,
    actions: LiveProofAutomationActions,
    progress: LiveProofAutomationProgress,
): Unit {
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
            payloadPlan = SenderPayloadPlan.GUIDED_HELLO,
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

    val deliveryCount =
        timelineEntryCount(snapshot, title = "DELIVERY_SUCCEEDED", peerSuffix = targetPeerSuffix)
    if (
        progress.sendRequested &&
            !progress.trustResetRequested &&
            snapshot.session.lastOutcomeSummary == "SendResult.Sent" &&
            deliveryCount >= 1
    ) {
        actions.emitAutomationLog(
            "REFERENCE_AUTOMATION trust.reset.requested role=sender window=open peer=$targetPeerSuffix"
        )
        progress.injectionRequested = true
        actions.requestTopologyDisruption(targetPeer.peerId)
        progress.trustResetRequested = true
        return
    }
    if (
        progress.trustResetRequested &&
            !progress.trustResetObserved &&
            hasTrustResetRecoveryReady(snapshot, peerSuffix = targetPeerSuffix)
    ) {
        actions.emitAutomationLog(
            "REFERENCE_AUTOMATION trust.reset.observed role=sender window=open peer=$targetPeerSuffix"
        )
        progress.trustResetObserved = true
        progress.injectionObserved = true
        progress.recoveryWindowOpened =
            hasTrustResetRecoveryWindowOpened(snapshot = snapshot, peerSuffix = targetPeerSuffix)
    }
    if (progress.trustResetObserved && !progress.recoverySendRequested) {
        announceTrustResetRecoveryIfNeeded(
            targetPeerSuffix = targetPeerSuffix,
            actions = actions,
            progress = progress,
        )
        requestSenderPayload(
            phase = "recovery",
            targetPeer = targetPeer,
            payloadPlan = SenderPayloadPlan.RECOVERY_HELLO,
            automationConfig = automationConfig,
            actions = actions,
        )
        progress.recoverySendRequested = true
    }

    val deliveryDetail =
        latestSenderDeliveryDetail(snapshot = snapshot, peerSuffix = targetPeerSuffix)
    if (
        progress.recoverySendRequested &&
            !progress.completionLogged &&
            snapshot.session.lastOutcomeSummary == "SendResult.Sent" &&
            deliveryCount >= REQUIRED_RECOVERY_DELIVERY_COUNT &&
            deliveryDetail != null
    ) {
        actions.emitAutomationLog(
            "REFERENCE_AUTOMATION proof.complete role=sender " +
                "outcome=${snapshot.session.lastOutcomeSummary} " +
                "peer=$targetPeerSuffix " +
                "delivery=$deliveryDetail " +
                "deliveries=$deliveryCount"
        )
        progress.completionLogged = true
    }
    if (
        progress.recoverySendRequested &&
            !progress.completionLogged &&
            snapshot.session.lastOutcomeSummary != null &&
            isTerminalSenderFailureOutcome(snapshot.session.lastOutcomeSummary)
    ) {
        emitSenderFailure(
            snapshot = snapshot,
            targetPeerSuffix = targetPeerSuffix,
            actions = actions,
            progress = progress,
        )
    }
}
