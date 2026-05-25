package ch.trancee.meshlink.reference.automation

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
