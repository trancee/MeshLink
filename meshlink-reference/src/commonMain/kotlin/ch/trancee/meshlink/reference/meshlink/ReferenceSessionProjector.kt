package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.ForgetPeerResult
import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.PauseResult
import ch.trancee.meshlink.api.ResumeResult
import ch.trancee.meshlink.api.StartResult
import ch.trancee.meshlink.api.StopResult
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticEvent
import ch.trancee.meshlink.reference.model.PeerTrustState
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import ch.trancee.meshlink.reference.model.redactedPayloadPreview

internal class LiveReferenceSessionProjector(
    private val stateStore: ReferenceControllerStateStore,
    private val runtimeLogger: (String) -> Unit = {},
) {
    internal fun <T : Any> recordMeshCall(
        result: Result<T>,
        successTitle: String,
        successDetail: (T) -> String,
        errorTitle: String,
    ): Unit {
        result
            .onSuccess { value ->
                stateStore.appendEvent(
                    ReferenceTimelineEvent(
                        family = TimelineFamily.LIFECYCLE,
                        severity = TimelineSeverity.SUCCESS,
                        title = successTitle,
                        detail = successDetail(value),
                    )
                )
                if (shouldTrackLifecycleOutcome(value)) {
                    stateStore.updateSession(lastOutcomeSummary = value.toString())
                }
            }
            .onFailure { error ->
                stateStore.appendEvent(
                    ReferenceTimelineEvent(
                        family = TimelineFamily.LIFECYCLE,
                        severity = TimelineSeverity.ERROR,
                        title = errorTitle,
                        detail = error.message ?: error.toString(),
                    )
                )
                stateStore.updateSession(lastOutcomeSummary = errorTitle)
            }
    }

    internal fun recordPeerTrustReset(peerId: String, result: ForgetPeerResult): Unit {
        val trustState =
            if (result == ForgetPeerResult.Forgotten) {
                PeerTrustState.FORGOTTEN
            } else {
                PeerTrustState.UNKNOWN
            }
        stateStore.updatePeers { peers ->
            peers.map { peer ->
                if (peer.peerId == peerId) {
                    peer.copy(trustState = trustState)
                } else {
                    peer
                }
            }
        }
        stateStore.appendEvent(
            ReferenceTimelineEvent(
                family = TimelineFamily.PEER,
                severity = TimelineSeverity.INFO,
                title = "Peer trust reset",
                detail = "forgetPeer(${redactedSuffix(peerId)}) -> $result",
                peerSuffix = redactedSuffix(peerId),
            )
        )
    }

    internal fun recordPeerTrustResetFailure(peerId: String, error: Throwable): Unit {
        stateStore.appendEvent(
            ReferenceTimelineEvent(
                family = TimelineFamily.PEER,
                severity = TimelineSeverity.ERROR,
                title = "Peer trust reset failed",
                detail = error.message ?: error.toString(),
                peerSuffix = redactedSuffix(peerId),
            )
        )
    }

    internal fun recordDiagnostic(event: DiagnosticEvent): Unit {
        val detail = diagnosticDetail(event)
        runtimeLogger(
            "REFERENCE_RUNTIME diagnostic " +
                "code=${event.code} " +
                "stage=${event.stage} " +
                "peer=${event.peerSuffix ?: "none"} " +
                "detail=$detail"
        )
        stateStore.appendEvent(
            ReferenceTimelineEvent(
                family = TimelineFamily.DIAGNOSTIC,
                severity = event.severity.toTimelineSeverity(),
                title = event.code.name,
                detail = detail,
                peerSuffix = event.peerSuffix,
            )
        )
        when (event.code) {
            DiagnosticCode.TRUST_ESTABLISHED ->
                updatePeerTrustState(
                    stateStore = stateStore,
                    peerSuffix = event.peerSuffix,
                    trustState = PeerTrustState.TRUSTED,
                )
            DiagnosticCode.TRUST_FAILURE ->
                updatePeerTrustState(
                    stateStore = stateStore,
                    peerSuffix = event.peerSuffix,
                    trustState = PeerTrustState.CHANGED,
                )
            DiagnosticCode.POWER_MODE_CHANGED -> {
                stateStore.updateActivePowerModeLabel(
                    event.metadata["tier"] ?: stateStore.currentSnapshot.activePowerModeLabel
                )
            }

            else -> Unit
        }
    }

    internal fun recordInboundMessage(message: InboundMessage): Unit {
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
}

private fun diagnosticDetail(event: DiagnosticEvent): String {
    return buildString {
        append(event.code)
        append(" @ ")
        append(event.stage)
        if (event.metadata.isNotEmpty()) {
            append(" ")
            append(
                event.metadata.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
                    "$key=$value"
                }
            )
        }
    }
}

private fun shouldTrackLifecycleOutcome(value: Any): Boolean {
    return value is StartResult ||
        value is ResumeResult ||
        value is PauseResult ||
        value is StopResult
}

private fun ch.trancee.meshlink.diagnostics.DiagnosticSeverity.toTimelineSeverity():
    TimelineSeverity {
    return when (this) {
        ch.trancee.meshlink.diagnostics.DiagnosticSeverity.DEBUG -> TimelineSeverity.DEBUG
        ch.trancee.meshlink.diagnostics.DiagnosticSeverity.INFO -> TimelineSeverity.INFO
        ch.trancee.meshlink.diagnostics.DiagnosticSeverity.WARN -> TimelineSeverity.WARNING
        ch.trancee.meshlink.diagnostics.DiagnosticSeverity.ERROR -> TimelineSeverity.ERROR
    }
}

private const val INBOUND_MESSAGE_PREVIEW_LENGTH: Int = 80
