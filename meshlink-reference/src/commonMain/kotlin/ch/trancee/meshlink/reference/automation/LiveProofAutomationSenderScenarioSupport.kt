package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.redactedSuffix

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
        actions.requestMeshPause()
        progress.pauseRequested = true
        return
    }
    if (progress.pauseRequested && !progress.pauseObserved && hasPauseObserved(snapshot)) {
        actions.emitAutomationLog("REFERENCE_AUTOMATION pause.observed role=sender")
        progress.pauseObserved = true
    }
    if (progress.pauseObserved && !progress.resumeRequested) {
        actions.emitAutomationLog("REFERENCE_AUTOMATION resume.requested role=sender")
        actions.requestMeshResume()
        progress.resumeRequested = true
        return
    }
    if (progress.resumeRequested && !progress.resumeObserved && hasResumeObserved(snapshot)) {
        actions.emitAutomationLog("REFERENCE_AUTOMATION resume.observed role=sender")
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

internal fun runTrustResetRecoverySenderAutomationStep(
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
            "REFERENCE_AUTOMATION trust.reset.requested role=sender peer=$targetPeerSuffix"
        )
        actions.requestForgetPeer(targetPeer.peerId)
        progress.trustResetRequested = true
        return
    }
    if (
        progress.trustResetRequested &&
            !progress.trustResetObserved &&
            hasTrustResetRecoveryReady(snapshot, peerSuffix = targetPeerSuffix)
    ) {
        actions.emitAutomationLog(
            "REFERENCE_AUTOMATION trust.reset.observed role=sender peer=$targetPeerSuffix"
        )
        progress.trustResetObserved = true
    }
    if (progress.trustResetObserved && !progress.recoverySendRequested) {
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
