package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot

internal fun requestSenderPayload(
    phase: String,
    targetPeer: AutoSendTargetPeer,
    payloadPlan: SenderPayloadPlan,
    automationConfig: ReferenceAutomationConfig,
    actions: LiveProofAutomationActions,
): Unit {
    val payloadText = payloadPlan.payload(actions.platformName)
    val priority = payloadPlan.priority
    val payloadBytes = payloadText.encodeToByteArray().size
    actions.emitAutomationLog(
        "REFERENCE_AUTOMATION send.requested role=sender " +
            "phase=$phase " +
            "peer=${targetPeer.peerSuffix} " +
            "priority=$priority " +
            "bytes=$payloadBytes " +
            "payload=${payloadPlan.label} " +
            "targetIndex=${automationConfig.targetPeerIndex} " +
            "requiredPeerCount=${automationConfig.requiredPeerCount} " +
            "targetPeerId=${automationConfig.targetPeerId ?: "auto"}"
    )
    actions.requestSendPayload(
        peerId = targetPeer.peerId,
        payloadText = payloadText,
        priority = priority,
    )
}

internal fun emitSenderFailure(
    snapshot: ReferenceControllerSnapshot,
    targetPeerSuffix: String?,
    actions: LiveProofAutomationActions,
    progress: LiveProofAutomationProgress,
): Unit {
    val latestObservation =
        latestAutomationObservation(snapshot = snapshot, peerSuffix = targetPeerSuffix)
    actions.emitAutomationLog(
        "REFERENCE_AUTOMATION proof.failed role=sender " +
            "outcome=${snapshot.session.lastOutcomeSummary.orEmpty()} " +
            "peer=${targetPeerSuffix ?: "none"} " +
            "observation=${latestObservation?.title ?: "none"} " +
            "detail=${latestObservation?.detail ?: "none"}"
    )
    progress.completionLogged = true
}

internal fun announceBootstrapObservationIfNeeded(
    snapshot: ReferenceControllerSnapshot,
    bootstrapPeer: AutoSendTargetPeer?,
    actions: LiveProofAutomationActions,
    progress: LiveProofAutomationProgress,
): Unit {
    if (!progress.bootstrapRequested || progress.sendRequested || bootstrapPeer == null) {
        return
    }
    val observation =
        latestAutomationObservation(snapshot = snapshot, peerSuffix = bootstrapPeer.peerSuffix)
            ?: return
    if (progress.lastBootstrapObservationEntryId == observation.entryId) {
        return
    }
    actions.emitAutomationLog(
        "REFERENCE_AUTOMATION bootstrap.observed role=sender " +
            "family=${observation.family} " +
            "title=${observation.title} " +
            "peer=${bootstrapPeer.peerSuffix} " +
            "detail=${observation.detail}"
    )
    progress.lastBootstrapObservationEntryId = observation.entryId
}

internal fun announceSenderObservationIfNeeded(
    snapshot: ReferenceControllerSnapshot,
    targetPeerSuffix: String?,
    actions: LiveProofAutomationActions,
    progress: LiveProofAutomationProgress,
): Unit {
    if ((!progress.sendRequested && !progress.recoverySendRequested) || targetPeerSuffix == null) {
        return
    }
    val observation =
        latestAutomationObservation(snapshot = snapshot, peerSuffix = targetPeerSuffix) ?: return
    if (progress.lastSenderObservationEntryId == observation.entryId) {
        return
    }
    actions.emitAutomationLog(
        "REFERENCE_AUTOMATION sender.observed role=sender " +
            "family=${observation.family} " +
            "title=${observation.title} " +
            "peer=$targetPeerSuffix " +
            "detail=${observation.detail}"
    )
    progress.lastSenderObservationEntryId = observation.entryId
}

internal fun announceSenderOutcomeIfNeeded(
    snapshot: ReferenceControllerSnapshot,
    actions: LiveProofAutomationActions,
    progress: LiveProofAutomationProgress,
): Unit {
    if (
        !progress.bootstrapRequested && !progress.sendRequested && !progress.recoverySendRequested
    ) {
        return
    }
    val lastOutcomeSummary = snapshot.session.lastOutcomeSummary ?: return
    if (
        lastOutcomeSummary == "Peer found" ||
            progress.lastSenderOutcomeSummary == lastOutcomeSummary
    ) {
        return
    }
    actions.emitAutomationLog(
        "REFERENCE_AUTOMATION sender.outcome role=sender summary=$lastOutcomeSummary"
    )
    progress.lastSenderOutcomeSummary = lastOutcomeSummary
}
