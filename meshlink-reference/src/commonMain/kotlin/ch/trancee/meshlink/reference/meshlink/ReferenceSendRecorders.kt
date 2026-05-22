package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import ch.trancee.meshlink.reference.model.redactedPayloadPreview

internal class LiveReferenceSendRecorder(private val stateStore: ReferenceControllerStateStore) {
    internal fun recordOutcome(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
        result: SendResult,
    ): Unit {
        when (result) {
            SendResult.Sent -> {
                stateStore.appendEvent(
                    ReferenceTimelineEvent(
                        family = TimelineFamily.MESSAGE,
                        severity = TimelineSeverity.SUCCESS,
                        title = "Guided message sent",
                        detail =
                            "First guided payload reached ${redactedSuffix(peerId)} with $priority priority.",
                        peerSuffix = redactedSuffix(peerId),
                        payloadPreview = redactedPayloadPreview(payloadText),
                        payloadSizeBytes = payloadText.encodeToByteArray().size,
                        fullPayload = payloadText,
                    )
                )
                stateStore.updateSession(
                    lastOutcomeSummary = "SendResult.Sent",
                    selectedPeerId = peerId,
                )
            }

            is SendResult.NotSent -> {
                stateStore.appendEvent(
                    ReferenceTimelineEvent(
                        family = TimelineFamily.MESSAGE,
                        severity = TimelineSeverity.ERROR,
                        title = "Guided message not sent",
                        detail =
                            "First guided payload failed for ${redactedSuffix(peerId)} with ${result.reason}.",
                        peerSuffix = redactedSuffix(peerId),
                        payloadPreview = redactedPayloadPreview(payloadText),
                        payloadSizeBytes = payloadText.encodeToByteArray().size,
                        fullPayload = payloadText,
                    )
                )
                stateStore.updateSession(
                    lastOutcomeSummary = "SendResult.NotSent(${result.reason})",
                    selectedPeerId = peerId,
                )
            }
        }
    }

    internal fun recordFailure(peerId: String, payloadText: String, error: Throwable): Unit {
        stateStore.appendEvent(
            ReferenceTimelineEvent(
                family = TimelineFamily.MESSAGE,
                severity = TimelineSeverity.ERROR,
                title = "Guided message failed",
                detail = error.message ?: error.toString(),
                peerSuffix = redactedSuffix(peerId),
                payloadPreview = redactedPayloadPreview(payloadText),
                payloadSizeBytes = payloadText.encodeToByteArray().size,
                fullPayload = payloadText,
            )
        )
    }
}

internal enum class ScriptedSendBlocker {
    UNKNOWN_PEER,
    MESH_NOT_RUNNING,
}

internal class ScriptedReferenceSendRecorder(
    private val stateStore: ReferenceControllerStateStore,
    private val scriptedPeerId: String,
    private val scriptedPeerSuffix: String,
    private val updatePeerOutcome: (String) -> Unit,
) {
    internal fun blockerFor(peerId: String, meshStateLabel: String): ScriptedSendBlocker? {
        return when {
            peerId != scriptedPeerId -> ScriptedSendBlocker.UNKNOWN_PEER
            meshStateLabel != MeshLinkState.Running.toString() ->
                ScriptedSendBlocker.MESH_NOT_RUNNING
            else -> null
        }
    }

    internal fun recordBlockedSend(peerId: String, blocker: ScriptedSendBlocker): Unit {
        when (blocker) {
            ScriptedSendBlocker.UNKNOWN_PEER ->
                stateStore.appendEvent(
                    ReferenceTimelineEvent(
                        family = TimelineFamily.MESSAGE,
                        severity = TimelineSeverity.ERROR,
                        title = "Send failed",
                        detail =
                            "The scripted controller could not find ${redactedSuffix(peerId)}.",
                        peerSuffix = redactedSuffix(peerId),
                    )
                )

            ScriptedSendBlocker.MESH_NOT_RUNNING ->
                stateStore.appendEvent(
                    ReferenceTimelineEvent(
                        family = TimelineFamily.MESSAGE,
                        severity = TimelineSeverity.ERROR,
                        title = "Send blocked",
                        detail =
                            "The scripted automation mesh must be Running before a send can proceed.",
                        peerSuffix = scriptedPeerSuffix,
                    )
                )
        }
    }

    internal fun recordCompletion(
        payloadText: String,
        priority: DeliveryPriority,
        largeTransferThresholdBytes: Int,
        payloadPreviewCharacters: Int,
    ): Unit {
        val payloadBytes = payloadText.encodeToByteArray().size
        if (payloadBytes > largeTransferThresholdBytes) {
            stateStore.appendEvent(
                ReferenceTimelineEvent(
                    family = TimelineFamily.TRANSFER,
                    severity = TimelineSeverity.SUCCESS,
                    title = "Large transfer completed",
                    detail =
                        "Transferred $payloadBytes bytes to $scriptedPeerSuffix with $priority priority.",
                    peerSuffix = scriptedPeerSuffix,
                    payloadPreview =
                        redactedPayloadPreview(payloadText.take(payloadPreviewCharacters)),
                    payloadSizeBytes = payloadBytes,
                    fullPayload = payloadText,
                )
            )
            updatePeerOutcome("Large transfer complete ($payloadBytes bytes)")
            stateStore.updateSession(
                meshStateLabel = MeshLinkState.Running.toString(),
                lastOutcomeSummary = "Large transfer complete",
                selectedPeerId = scriptedPeerId,
            )
            return
        }

        stateStore.appendEvent(
            ReferenceTimelineEvent(
                family = TimelineFamily.MESSAGE,
                severity = TimelineSeverity.SUCCESS,
                title = "Guided message sent",
                detail = "Sent $payloadBytes bytes to $scriptedPeerSuffix with $priority priority.",
                peerSuffix = scriptedPeerSuffix,
                payloadPreview = redactedPayloadPreview(payloadText.take(payloadPreviewCharacters)),
                payloadSizeBytes = payloadBytes,
                fullPayload = payloadText,
            )
        )
        stateStore.appendEvent(
            ReferenceTimelineEvent(
                family = TimelineFamily.MESSAGE,
                severity = TimelineSeverity.SUCCESS,
                title = "Delivery confirmed",
                detail = "The scripted peer acknowledged the latest guided payload.",
                peerSuffix = scriptedPeerSuffix,
                payloadPreview = redactedPayloadPreview(payloadText.take(payloadPreviewCharacters)),
                payloadSizeBytes = payloadBytes,
                fullPayload = payloadText,
            )
        )
        updatePeerOutcome("Delivery confirmed")
        stateStore.updateSession(
            meshStateLabel = MeshLinkState.Running.toString(),
            lastOutcomeSummary = "SendResult.Sent",
            selectedPeerId = scriptedPeerId,
        )
    }
}
