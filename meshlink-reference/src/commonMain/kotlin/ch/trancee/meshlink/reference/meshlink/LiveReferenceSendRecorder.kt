package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.DeliveryPriority
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
