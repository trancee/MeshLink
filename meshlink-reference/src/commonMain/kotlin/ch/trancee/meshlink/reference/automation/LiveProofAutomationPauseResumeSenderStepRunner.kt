package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.redactedSuffix

internal fun runPauseResumeSenderAutomationStep(
    snapshot: ReferenceControllerSnapshot,
    automationConfig: ReferenceAutomationConfig,
    actions: LiveProofAutomationActions,
    progress: LiveProofAutomationProgress,
): Unit {
    val resolvedTargetPeer =
        autoSendTargetPeer(
            snapshot = snapshot,
            requiredPeerCount = automationConfig.requiredPeerCount,
            targetPeerIndex = automationConfig.targetPeerIndex,
            targetPeerId = automationConfig.targetPeerId,
        )
    if (progress.pauseResumeTargetPeerId == null && resolvedTargetPeer != null) {
        progress.pauseResumeTargetPeerId = resolvedTargetPeer.peerId
        progress.pauseResumeTargetPeerSuffix = resolvedTargetPeer.peerSuffix
    }
    val targetPeer =
        resolvedTargetPeer
            ?: progress.pauseResumeTargetPeerId?.let { peerId ->
                AutoSendTargetPeer(
                    peerId = peerId,
                    peerSuffix = progress.pauseResumeTargetPeerSuffix ?: redactedSuffix(peerId),
                )
            }
            ?: return
    if (!progress.pauseRequested && !isMeshRunning(snapshot.session.meshStateLabel)) {
        return
    }

    if (!progress.sendRequested) {
        requestSenderPayload(
            phase = "warmup",
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
        shouldRequestPauseForPauseResume(
            pauseRequested = progress.pauseRequested,
            snapshot = snapshot,
            targetPeerId = targetPeer.peerId,
        ) && snapshot.session.lastOutcomeSummary == "SendResult.Sent" && deliveryCount >= 1
    ) {
        actions.emitAutomationLog("REFERENCE_AUTOMATION pause.requested role=sender")
        progress.injectionRequested = true
        actions.requestMeshPause()
        progress.pauseRequested = true
        return
    }
    if (progress.pauseRequested && !progress.pauseObserved && hasPauseObserved(snapshot)) {
        actions.emitAutomationLog(
            "REFERENCE_AUTOMATION pause.observed role=sender window=open peer=${targetPeer.peerSuffix}"
        )
        progress.pauseObserved = true
        progress.injectionObserved = true
        progress.recoveryWindowOpened = hasPauseResumeRecoveryWindowOpened(snapshot)
    }
    if (progress.pauseObserved && !progress.resumeRequested) {
        announcePauseRecoveryIfNeeded(
            targetPeerSuffix = targetPeer.peerSuffix,
            actions = actions,
            progress = progress,
        )
        actions.emitAutomationLog("REFERENCE_AUTOMATION resume.requested role=sender")
        actions.requestMeshResume()
        progress.resumeRequested = true
        return
    }
    if (progress.resumeRequested && !progress.resumeObserved && hasResumeObserved(snapshot)) {
        actions.emitAutomationLog(
            "REFERENCE_AUTOMATION resume.observed role=sender window=closed peer=${targetPeer.peerSuffix}"
        )
        progress.resumeObserved = true
    }
    if (
        !progress.recoverySendRequested &&
            shouldSendAfterPauseResumeRecovery(
                resumeObserved = progress.resumeObserved,
                snapshot = snapshot,
            )
    ) {
        requestSenderPayload(
            phase = "recovery",
            targetPeer = targetPeer,
            payloadPlan = SenderPayloadPlan.GUIDED_HELLO,
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
            deliveryCount >= REQUIRED_PAUSE_RESUME_DELIVERY_COUNT &&
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
