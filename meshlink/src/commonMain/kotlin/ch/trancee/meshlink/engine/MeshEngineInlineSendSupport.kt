package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendFailureReason
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.crypto.MessageSealer
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.trust.TrustRecord
import ch.trancee.meshlink.wire.WireFrame
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal data class MeshEngineInlineConfig(
    val deliveryRetryDeadline: Duration,
    val inlineMessagePayloadBytes: Int,
)

internal data class MeshEngineInlineRoutingContext(
    val routeCoordinator: ch.trancee.meshlink.routing.RouteCoordinator,
    val routingSupport: MeshEngineRoutingSupport,
)

internal data class MeshEngineInlineDependencies(
    val deliveryRetryScheduler: DeliveryRetryScheduler,
    val ensureHopSession: suspend (PeerId) -> SessionEstablishmentOutcome,
    val sendEncryptedDirectWireFrame:
        suspend (PeerId, HopSession, WireFrame, String) -> TransportSendResult,
    val resolveRecipientTrust: suspend (PeerId) -> TrustRecord?,
    val scheduleRetryDiagnostic: (PeerId, DeliveryPriority) -> Unit,
    val setDiscoverySuspended: suspend (Boolean) -> Unit,
    val emitHopSessionFailed: (PeerId, String, DiagnosticReason, Map<String, String>) -> Unit,
)

internal data class MeshEngineInlineCallbacks(
    val emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit,
    val createMessageId: () -> String,
    val ttlMillisFor: (DeliveryPriority) -> Int,
)

internal data class InlineSessionResolution(
    val nextHopPeerId: PeerId,
    val session: HopSession?,
    val result: SendResult? = null,
)

internal class MeshEngineInlineSendSupport(
    private val localIdentity: LocalIdentity,
    private val config: MeshEngineInlineConfig,
    private val routingContext: MeshEngineInlineRoutingContext,
    private val dependencies: MeshEngineInlineDependencies,
    private val callbacks: MeshEngineInlineCallbacks,
) {
    suspend fun sendInlinePayload(
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
    ): SendResult {
        val startedAt = TimeSource.Monotonic.markNow()
        var retryState =
            InlineRetryWakeupState(
                attempt = 0,
                topologyVersion = routingContext.routeCoordinator.topologyVersion.value,
            )
        val suspendDiscoveryDuringSend = payload.size > config.inlineMessagePayloadBytes

        if (suspendDiscoveryDuringSend) {
            dependencies.setDiscoverySuspended(true)
        }

        try {
            while (startedAt.elapsedNow() < config.deliveryRetryDeadline) {
                val sendResult =
                    attemptInlineSend(peerId = peerId, payload = payload, priority = priority)
                if (!sendResult.isRetryableInlineFailure()) {
                    return sendResult
                }
                val nextRetryState =
                    awaitInlineRetryWakeup(
                        peerId = peerId,
                        retryState = retryState,
                        startedAt = startedAt,
                    ) ?: break
                retryState = nextRetryState
            }
            return unreachableInlineSendResult(peerId)
        } finally {
            if (suspendDiscoveryDuringSend) {
                dependencies.setDiscoverySuspended(false)
            }
        }
    }

    private suspend fun attemptInlineSend(
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
    ): SendResult {
        val sessionResolution = resolveInlineSession(peerId = peerId, priority = priority)
        val resolvedResult = sessionResolution.result
        val session = sessionResolution.session

        return if (resolvedResult != null) {
            resolvedResult
        } else {
            val establishedSession = session ?: error("inline session resolution is required")
            val recipientTrust = resolveInlineRecipientTrust(peerId)
            if (recipientTrust == null) {
                SendResult.NotSent(SendFailureReason.TRUST_FAILURE)
            } else {
                val routedMessage =
                    buildInlineRoutedMessage(
                        peerId = peerId,
                        payload = payload,
                        priority = priority,
                        recipientTrust = recipientTrust,
                    )
                if (routedMessage == null) {
                    SendResult.NotSent(SendFailureReason.TRUST_FAILURE)
                } else {
                    dispatchInlineMessage(
                        peerId = peerId,
                        nextHopPeerId = sessionResolution.nextHopPeerId,
                        session = establishedSession,
                        routedMessage = routedMessage,
                        priority = priority,
                    )
                }
            }
        }
    }

    private suspend fun awaitInlineRetryWakeup(
        peerId: PeerId,
        retryState: InlineRetryWakeupState,
        startedAt: TimeMark,
    ): InlineRetryWakeupState? {
        callbacks.emitDiagnostic(
            DiagnosticCode.DELIVERY_RETRY_SCHEDULED,
            DiagnosticSeverity.WARN,
            "delivery.retryScheduled",
            peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
            DiagnosticReason.DELIVERY_RETRY,
            routingContext.routingSupport.peerRouteMetadata(
                peerId,
                metadata = mapOf("attempt" to retryState.attempt.toString()),
            ),
        )

        val wakeupState =
            when (
                val wakeup =
                    dependencies.deliveryRetryScheduler.awaitRetry(
                        attempt = retryState.attempt,
                        remainingBudget = config.deliveryRetryDeadline - startedAt.elapsedNow(),
                        lastObservedTopologyVersion = retryState.topologyVersion,
                    )
            ) {
                is RetryWakeup.DeadlineExpired -> null
                is RetryWakeup.TimerElapsed ->
                    InlineRetryWakeupState(
                        attempt = retryState.attempt + 1,
                        topologyVersion = wakeup.topologyVersion,
                    )
                is RetryWakeup.TopologyChanged ->
                    InlineRetryWakeupState(attempt = 0, topologyVersion = wakeup.topologyVersion)
            }
        if (wakeupState != null) {
            callbacks.emitDiagnostic(
                DiagnosticCode.DELIVERY_RETRYING,
                DiagnosticSeverity.WARN,
                "delivery.retrying",
                peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                DiagnosticReason.DELIVERY_RETRY,
                routingContext.routingSupport.peerRouteMetadata(
                    peerId,
                    metadata = mapOf("attempt" to wakeupState.attempt.toString()),
                ),
            )
        }
        return wakeupState
    }

    private suspend fun resolveInlineSession(
        peerId: PeerId,
        priority: DeliveryPriority,
    ): InlineSessionResolution {
        val nextHopPeerId = routingContext.routeCoordinator.nextHopFor(peerId) ?: peerId
        return when (val sessionOutcome = dependencies.ensureHopSession(nextHopPeerId)) {
            is SessionEstablishmentOutcome.Established ->
                InlineSessionResolution(
                    nextHopPeerId = nextHopPeerId,
                    session = sessionOutcome.session,
                )
            SessionEstablishmentOutcome.TrustFailure ->
                InlineSessionResolution(
                    nextHopPeerId = nextHopPeerId,
                    session = null,
                    result = SendResult.NotSent(SendFailureReason.TRUST_FAILURE),
                )
            SessionEstablishmentOutcome.Unreachable -> {
                dependencies.scheduleRetryDiagnostic(peerId, priority)
                InlineSessionResolution(
                    nextHopPeerId = nextHopPeerId,
                    session = null,
                    result = SendResult.NotSent(SendFailureReason.UNREACHABLE),
                )
            }
        }
    }

    private suspend fun resolveInlineRecipientTrust(peerId: PeerId): TrustRecord? {
        val recipientTrust = dependencies.resolveRecipientTrust(peerId)
        if (recipientTrust == null) {
            callbacks.emitDiagnostic(
                DiagnosticCode.TRUST_FAILURE,
                DiagnosticSeverity.ERROR,
                "delivery.send.noTrust",
                peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                DiagnosticReason.TRUST_FAILURE,
                emptyMap(),
            )
        }
        return recipientTrust
    }

    private fun buildInlineRoutedMessage(
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
        recipientTrust: TrustRecord,
    ): WireFrame.Message? {
        val sealedPayload =
            runCatching {
                    MessageSealer.seal(
                        plaintext = payload,
                        senderIdentity = localIdentity,
                        recipientTrust = recipientTrust,
                    )
                }
                .getOrElse { exception ->
                    dependencies.emitHopSessionFailed(
                        peerId,
                        "delivery.send.encrypt",
                        DiagnosticReason.TRUST_FAILURE,
                        mapOf("cause" to exception::class.simpleName.orEmpty()),
                    )
                    return null
                }

        val innerEnvelope =
            DirectMessageEnvelope(
                    senderPeerId = localIdentity.peerId,
                    senderFingerprintBytes = localIdentity.identityFingerprintBytes,
                    senderEd25519PublicKey = localIdentity.ed25519PublicKey,
                    senderX25519PublicKey = localIdentity.x25519PublicKey,
                    ciphertext = sealedPayload,
                )
                .encode()
        return WireFrame.Message(
            messageId = callbacks.createMessageId(),
            originPeerId = localIdentity.peerId,
            destinationPeerId = peerId,
            priority = priority,
            ttlMillis = callbacks.ttlMillisFor(priority),
            encryptedPayload = innerEnvelope,
        )
    }

    private suspend fun dispatchInlineMessage(
        peerId: PeerId,
        nextHopPeerId: PeerId,
        session: HopSession,
        routedMessage: WireFrame.Message,
        priority: DeliveryPriority,
    ): SendResult {
        return when (
            runCatching {
                    dependencies.sendEncryptedDirectWireFrame(
                        nextHopPeerId,
                        session,
                        routedMessage,
                        "send.data",
                    )
                }
                .getOrElse { exception ->
                    dependencies.emitHopSessionFailed(
                        nextHopPeerId,
                        "delivery.send.transportEncrypt",
                        DiagnosticReason.DELIVERY_FAILURE,
                        mapOf("cause" to exception::class.simpleName.orEmpty()),
                    )
                    return SendResult.NotSent(SendFailureReason.UNREACHABLE)
                }
        ) {
            TransportSendResult.Delivered -> {
                callbacks.emitDiagnostic(
                    DiagnosticCode.DELIVERY_SUCCEEDED,
                    DiagnosticSeverity.INFO,
                    "delivery.send",
                    peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                    null,
                    routingContext.routingSupport.peerRouteMetadata(peerId),
                )
                SendResult.Sent
            }
            is TransportSendResult.Dropped -> {
                dependencies.scheduleRetryDiagnostic(peerId, priority)
                SendResult.NotSent(SendFailureReason.UNREACHABLE)
            }
        }
    }

    private fun unreachableInlineSendResult(peerId: PeerId): SendResult {
        callbacks.emitDiagnostic(
            DiagnosticCode.DELIVERY_UNREACHABLE,
            DiagnosticSeverity.ERROR,
            "delivery.retryExpired",
            peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
            DiagnosticReason.DELIVERY_FAILURE,
            routingContext.routingSupport.peerRouteMetadata(peerId),
        )
        return SendResult.NotSent(SendFailureReason.UNREACHABLE)
    }
}
