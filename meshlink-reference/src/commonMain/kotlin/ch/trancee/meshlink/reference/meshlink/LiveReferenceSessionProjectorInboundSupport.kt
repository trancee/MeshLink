package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import ch.trancee.meshlink.reference.model.redactedPayloadPreview

internal fun recordProjectedInboundMessage(
    stateStore: ReferenceControllerStateStore,
    runtimeLogger: (String) -> Unit,
    message: InboundMessage,
): Unit {
    val payloadText = message.payload.decodeToString()
    val preview = redactedPayloadPreview(payloadText.take(INBOUND_MESSAGE_PREVIEW_LENGTH))
    runtimeLogger(
        "REFERENCE_RUNTIME inbound " +
            "origin=${message.originPeerId.value} " +
            "bytes=${message.payload.size} " +
            "priority=${message.priority}"
    )
    stateStore.appendEvent(
        ReferenceTimelineEvent(
            family = TimelineFamily.MESSAGE,
            severity = TimelineSeverity.SUCCESS,
            title = "Inbound message",
            detail =
                "Received ${message.payload.size} bytes from ${redactedSuffix(message.originPeerId.value)}.",
            peerSuffix = redactedSuffix(message.originPeerId.value),
            payloadPreview = preview,
            payloadSizeBytes = message.payload.size,
            fullPayload = payloadText,
        )
    )
    stateStore.updateSession(
        lastOutcomeSummary = "Inbound message received",
        selectedPeerId = message.originPeerId.value,
    )
    stateStore.updatePeers { peers ->
        peers.map { peer ->
            if (peer.peerId == message.originPeerId.value) {
                peer.copy(lastDeliveryOutcome = "Inbound ${message.payload.size} bytes")
            } else {
                peer
            }
        }
    }
}

private const val INBOUND_MESSAGE_PREVIEW_LENGTH: Int = 80
