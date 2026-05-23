package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendFailureReason
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity

internal data class MeshEngineSendConfig(
    val maxSupportedPayloadBytes: Int,
    val inlineMessagePayloadBytes: Int,
)

internal data class MeshEngineSendCallbacks(
    val currentLifecycleState: () -> MeshLinkState,
    val captureHardRunToken: () -> MeshEngineHardRunToken,
    val hasTransport: () -> Boolean,
    val shouldAttemptLargeInlineSend: (PeerId) -> Boolean,
    val sendInlinePayload:
        suspend (PeerId, ByteArray, DeliveryPriority, MeshEngineHardRunToken) -> SendResult,
    val sendLargePayload:
        suspend (PeerId, ByteArray, DeliveryPriority, MeshEngineHardRunToken) -> SendResult,
    val scheduleRetryDiagnostic: (PeerId, DeliveryPriority) -> Unit,
    val emitDiagnostic:
        (
            code: DiagnosticCode,
            severity: DiagnosticSeverity,
            stage: String,
            peerSuffix: String?,
            reason: DiagnosticReason?,
            metadata: Map<String, String>,
        ) -> Unit,
)

internal class MeshEngineSendSupport(
    private val config: MeshEngineSendConfig,
    private val callbacks: MeshEngineSendCallbacks,
) {
    internal suspend fun send(
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
    ): SendResult {
        val currentState = callbacks.currentLifecycleState()
        if (currentState !== MeshLinkState.Running) {
            throw MeshLinkException.InvalidStateTransition(
                message = "send() requires MeshLinkState.Running but was $currentState"
            )
        }

        val isPayloadTooLarge = payload.size > config.maxSupportedPayloadBytes
        val transportUnavailable = !callbacks.hasTransport()
        val shouldUseInlineDelivery =
            payload.size <= config.inlineMessagePayloadBytes ||
                callbacks.shouldAttemptLargeInlineSend(peerId)

        return when {
            isPayloadTooLarge -> {
                callbacks.emitDiagnostic(
                    DiagnosticCode.SIZE_LIMIT_REJECTED,
                    DiagnosticSeverity.WARN,
                    "delivery.send",
                    peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                    DiagnosticReason.SIZE_LIMIT,
                    mapOf("payloadBytes" to payload.size.toString()),
                )
                SendResult.NotSent(SendFailureReason.PAYLOAD_TOO_LARGE)
            }

            transportUnavailable -> {
                callbacks.scheduleRetryDiagnostic(peerId, priority)
                SendResult.NotSent(SendFailureReason.UNREACHABLE)
            }

            else -> {
                val hardRunToken = callbacks.captureHardRunToken()
                if (shouldUseInlineDelivery) {
                    callbacks.sendInlinePayload(peerId, payload, priority, hardRunToken)
                } else {
                    callbacks.sendLargePayload(peerId, payload, priority, hardRunToken)
                }
            }
        }
    }
}
