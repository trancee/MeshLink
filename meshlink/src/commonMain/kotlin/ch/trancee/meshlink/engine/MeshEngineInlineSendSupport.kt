package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendFailureReason
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.transport.TransportSendResult
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
    val deliveryRetrySupport: MeshEngineDeliveryRetrySupport,
    val discoverySuspensionSupport: MeshEngineDiscoverySuspensionSupport,
    val ensureHopSession: suspend (PeerId, MeshEngineHardRunToken) -> SessionEstablishmentOutcome,
    val sendEncryptedDirectWireFrame:
        suspend (PeerId, HopSession, WireFrame, String) -> TransportSendResult,
    val prepareOutboundInlineMessage:
        suspend (
            PeerId, ByteArray, DeliveryPriority, Int,
        ) -> MeshEngineOutboundInlineMessagePreparation,
    val scheduleRetryDiagnostic: (PeerId, DeliveryPriority) -> Unit,
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
    val ttlMillisFor: (DeliveryPriority) -> Int,
)

internal data class InlineSessionResolution(
    val nextHopPeerId: PeerId,
    val session: HopSession?,
    val result: SendResult? = null,
)

internal class MeshEngineInlineSendSupport(
    private val config: MeshEngineInlineConfig,
    private val routingContext: MeshEngineInlineRoutingContext,
    private val dependencies: MeshEngineInlineDependencies,
    private val callbacks: MeshEngineInlineCallbacks,
) {
    suspend fun sendInlinePayload(
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
        hardRunToken: MeshEngineHardRunToken,
    ): SendResult {
        val startedAt = TimeSource.Monotonic.markNow()
        var retryState =
            MeshEngineDeliveryRetryState(
                attempt = 0,
                topologyVersion = routingContext.routeCoordinator.topologyVersion.value,
            )
        val suspendDiscoveryDuringSend = payload.size > config.inlineMessagePayloadBytes

        return dependencies.discoverySuspensionSupport.withDiscoverySuspended(
            shouldSuspend = suspendDiscoveryDuringSend
        ) {
            while (startedAt.elapsedNow() < config.deliveryRetryDeadline) {
                val sendResult =
                    attemptInlineSend(
                        peerId = peerId,
                        payload = payload,
                        priority = priority,
                        hardRunToken = hardRunToken,
                    )
                if (!sendResult.isRetryableInlineFailure()) {
                    return@withDiscoverySuspended sendResult
                }
                when (
                    val nextRetryState =
                        awaitInlineRetryWakeup(
                            peerId = peerId,
                            retryState = retryState,
                            startedAt = startedAt,
                            hardRunToken = hardRunToken,
                        )
                ) {
                    is MeshEngineDeliveryRetryResult.Woke -> retryState = nextRetryState.state
                    MeshEngineDeliveryRetryResult.DeadlineExpired -> break
                    MeshEngineDeliveryRetryResult.HardRunEnded -> {
                        return@withDiscoverySuspended abortedInlineSendResult()
                    }
                }
            }
            unreachableInlineSendResult(peerId)
        }
    }

    private suspend fun attemptInlineSend(
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
        hardRunToken: MeshEngineHardRunToken,
    ): SendResult {
        val sessionResolution =
            resolveInlineSession(peerId = peerId, priority = priority, hardRunToken = hardRunToken)
        val resolvedResult = sessionResolution.result
        val session = sessionResolution.session

        return if (resolvedResult != null) {
            resolvedResult
        } else {
            val establishedSession = session ?: error("inline session resolution is required")
            when (
                val preparedMessage =
                    dependencies.prepareOutboundInlineMessage(
                        peerId,
                        payload,
                        priority,
                        callbacks.ttlMillisFor(priority),
                    )
            ) {
                MeshEngineOutboundInlineMessagePreparation.MissingTrust -> {
                    callbacks.emitDiagnostic(
                        DiagnosticCode.TRUST_FAILURE,
                        DiagnosticSeverity.ERROR,
                        "delivery.send.noTrust",
                        peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                        DiagnosticReason.TRUST_FAILURE,
                        emptyMap(),
                    )
                    SendResult.NotSent(SendFailureReason.TRUST_FAILURE)
                }
                MeshEngineOutboundInlineMessagePreparation.EncryptFailure -> {
                    SendResult.NotSent(SendFailureReason.TRUST_FAILURE)
                }
                is MeshEngineOutboundInlineMessagePreparation.Ready -> {
                    dispatchInlineMessage(
                        peerId = peerId,
                        nextHopPeerId = sessionResolution.nextHopPeerId,
                        session = establishedSession,
                        routedMessage = preparedMessage.message,
                        priority = priority,
                    )
                }
            }
        }
    }

    private suspend fun awaitInlineRetryWakeup(
        peerId: PeerId,
        retryState: MeshEngineDeliveryRetryState,
        startedAt: TimeMark,
        hardRunToken: MeshEngineHardRunToken,
    ): MeshEngineDeliveryRetryResult {
        return dependencies.deliveryRetrySupport.awaitRetry(
            peerId = peerId,
            state = retryState,
            remainingBudget = config.deliveryRetryDeadline - startedAt.elapsedNow(),
            hardRunToken = hardRunToken,
            profile = INLINE_DELIVERY_RETRY_PROFILE,
        )
    }

    private suspend fun resolveInlineSession(
        peerId: PeerId,
        priority: DeliveryPriority,
        hardRunToken: MeshEngineHardRunToken,
    ): InlineSessionResolution {
        val nextHopPeerId = routingContext.routeCoordinator.nextHopFor(peerId) ?: peerId
        return when (
            val sessionOutcome = dependencies.ensureHopSession(nextHopPeerId, hardRunToken)
        ) {
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

    private fun abortedInlineSendResult(): SendResult {
        return SendResult.NotSent(SendFailureReason.TRANSFER_ABORTED)
    }
}

internal fun buildMeshEngineRuntimeInlineSendSupport(
    deliveryRetryDeadline: Duration,
    inlineMessagePayloadBytes: Int,
    routeCoordinator: ch.trancee.meshlink.routing.RouteCoordinator,
    routingSupport: MeshEngineRoutingSupport,
    sessionSupport: MeshEngineSessionSupport,
    hopTransportSupport: MeshEngineHopTransportSupport,
    outboundPreparationSupport: MeshEngineOutboundPreparationSupport,
    deliveryRetrySupport: MeshEngineDeliveryRetrySupport,
    discoverySuspensionSupport: MeshEngineDiscoverySuspensionSupport,
    ttlMillisFor: (DeliveryPriority) -> Int,
    scheduleRetryDiagnostic: (PeerId, DeliveryPriority) -> Unit,
    emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit,
): MeshEngineInlineSendSupport {
    return MeshEngineInlineSendSupport(
        config =
            MeshEngineInlineConfig(
                deliveryRetryDeadline = deliveryRetryDeadline,
                inlineMessagePayloadBytes = inlineMessagePayloadBytes,
            ),
        routingContext =
            MeshEngineInlineRoutingContext(
                routeCoordinator = routeCoordinator,
                routingSupport = routingSupport,
            ),
        dependencies =
            MeshEngineInlineDependencies(
                deliveryRetrySupport = deliveryRetrySupport,
                discoverySuspensionSupport = discoverySuspensionSupport,
                ensureHopSession = { peerId, hardRunToken ->
                    sessionSupport.ensureHopSession(peerId, hardRunToken)
                },
                sendEncryptedDirectWireFrame = hopTransportSupport::sendEncryptedDirectWireFrame,
                prepareOutboundInlineMessage =
                    outboundPreparationSupport::prepareOutboundInlineMessage,
                scheduleRetryDiagnostic = scheduleRetryDiagnostic,
                emitHopSessionFailed = hopTransportSupport::emitHopSessionFailed,
            ),
        callbacks =
            MeshEngineInlineCallbacks(emitDiagnostic = emitDiagnostic, ttlMillisFor = ttlMillisFor),
    )
}

private val INLINE_DELIVERY_RETRY_PROFILE =
    MeshEngineDeliveryRetryProfile(
        scheduledStage = "delivery.retryScheduled",
        retryingStage = "delivery.retrying",
    )
