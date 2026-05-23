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

internal data class MeshEngineInlineOutboundDeliveryAdapterConfig(
    val inlineMessagePayloadBytes: Int
)

internal data class MeshEngineInlineOutboundDeliveryAdapterRoutingContext(
    val routeCoordinator: ch.trancee.meshlink.routing.RouteCoordinator,
    val routingSupport: MeshEngineRoutingSupport,
)

internal data class MeshEngineInlineOutboundDeliveryAdapterDependencies(
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

internal data class MeshEngineInlineOutboundDeliveryAdapterCallbacks(
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

private data class MeshEngineInlineOutboundDeliverySessionResolution(
    val nextHopPeerId: PeerId,
    val session: HopSession?,
    val result: SendResult? = null,
)

internal class MeshEngineInlineOutboundDeliveryAdapter(
    private val config: MeshEngineInlineOutboundDeliveryAdapterConfig,
    private val routingContext: MeshEngineInlineOutboundDeliveryAdapterRoutingContext,
    private val dependencies: MeshEngineInlineOutboundDeliveryAdapterDependencies,
    private val callbacks: MeshEngineInlineOutboundDeliveryAdapterCallbacks,
) {
    fun currentTopologyVersion(): Long {
        return routingContext.routeCoordinator.topologyVersion.value
    }

    fun beginOutboundDelivery(
        @Suppress("UnusedParameter") context: MeshEngineOutboundDeliveryAttemptContext
    ): Unit = Unit

    suspend fun <T> withDiscoveryPolicy(
        context: MeshEngineOutboundDeliveryAttemptContext,
        block: suspend () -> T,
    ): T {
        return dependencies.discoverySuspensionSupport.withDiscoverySuspended(
            shouldSuspend = context.payload.size > config.inlineMessagePayloadBytes,
            block = block,
        )
    }

    suspend fun attemptOutboundDelivery(
        @Suppress("UnusedParameter") state: Unit,
        context: MeshEngineOutboundDeliveryAttemptContext,
    ): MeshEngineOutboundDeliveryAttemptOutcome<Unit> {
        val sendResult =
            attemptInlineSend(
                peerId = context.peerId,
                payload = context.payload,
                priority = context.priority,
                hardRunToken = context.hardRunToken,
            )
        return if (
            sendResult is SendResult.NotSent && sendResult.reason == SendFailureReason.UNREACHABLE
        ) {
            MeshEngineOutboundDeliveryAttemptOutcome.AwaitRetry(
                nextState = Unit,
                retryPolicy = MeshEngineOutboundDeliveryRetryPolicy(INLINE_DELIVERY_RETRY_POLICY),
            )
        } else {
            MeshEngineOutboundDeliveryAttemptOutcome.Completed(sendResult)
        }
    }

    fun onDeadlineExpired(
        @Suppress("UnusedParameter") state: Unit,
        context: MeshEngineOutboundDeliveryAttemptContext,
    ): SendResult {
        return unreachableInlineSendResult(context.peerId)
    }

    fun onHardRunEnded(
        @Suppress("UnusedParameter") state: Unit,
        @Suppress("UnusedParameter") context: MeshEngineOutboundDeliveryAttemptContext,
    ): SendResult {
        return abortedInlineSendResult()
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

    private suspend fun resolveInlineSession(
        peerId: PeerId,
        priority: DeliveryPriority,
        hardRunToken: MeshEngineHardRunToken,
    ): MeshEngineInlineOutboundDeliverySessionResolution {
        val nextHopPeerId = routingContext.routeCoordinator.nextHopFor(peerId) ?: peerId
        return when (
            val sessionOutcome = dependencies.ensureHopSession(nextHopPeerId, hardRunToken)
        ) {
            is SessionEstablishmentOutcome.Established ->
                MeshEngineInlineOutboundDeliverySessionResolution(
                    nextHopPeerId = nextHopPeerId,
                    session = sessionOutcome.session,
                )
            SessionEstablishmentOutcome.TrustFailure ->
                MeshEngineInlineOutboundDeliverySessionResolution(
                    nextHopPeerId = nextHopPeerId,
                    session = null,
                    result = SendResult.NotSent(SendFailureReason.TRUST_FAILURE),
                )
            SessionEstablishmentOutcome.Unreachable -> {
                dependencies.scheduleRetryDiagnostic(peerId, priority)
                MeshEngineInlineOutboundDeliverySessionResolution(
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

internal fun buildMeshEngineRuntimeInlineOutboundDeliveryAdapter(
    inlineMessagePayloadBytes: Int,
    routeCoordinator: ch.trancee.meshlink.routing.RouteCoordinator,
    routingSupport: MeshEngineRoutingSupport,
    sessionSupport: MeshEngineSessionSupport,
    hopTransportSupport: MeshEngineHopTransportSupport,
    outboundPreparationSupport: MeshEngineOutboundPreparationSupport,
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
): MeshEngineInlineOutboundDeliveryAdapter {
    return MeshEngineInlineOutboundDeliveryAdapter(
        config =
            MeshEngineInlineOutboundDeliveryAdapterConfig(
                inlineMessagePayloadBytes = inlineMessagePayloadBytes
            ),
        routingContext =
            MeshEngineInlineOutboundDeliveryAdapterRoutingContext(
                routeCoordinator = routeCoordinator,
                routingSupport = routingSupport,
            ),
        dependencies =
            MeshEngineInlineOutboundDeliveryAdapterDependencies(
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
            MeshEngineInlineOutboundDeliveryAdapterCallbacks(
                emitDiagnostic = emitDiagnostic,
                ttlMillisFor = ttlMillisFor,
            ),
    )
}

private val INLINE_DELIVERY_RETRY_POLICY =
    MeshEngineDeliveryRetryProfile(
        scheduledStage = "delivery.retryScheduled",
        retryingStage = "delivery.retrying",
    )
