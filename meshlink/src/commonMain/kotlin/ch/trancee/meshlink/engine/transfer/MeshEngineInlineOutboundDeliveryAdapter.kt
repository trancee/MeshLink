package ch.trancee.meshlink.engine.transfer

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendFailureReason
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.engine.assembly.MeshEngineHardRunToken
import ch.trancee.meshlink.engine.internal.HopSession
import ch.trancee.meshlink.engine.internal.MeshEngineEmitDiagnostic
import ch.trancee.meshlink.engine.internal.SessionEstablishmentOutcome
import ch.trancee.meshlink.engine.internal.diagnosticSuffix
import ch.trancee.meshlink.engine.lifecycle.MeshEngineDiscoverySuspensionSupport
import ch.trancee.meshlink.engine.routing.MeshEngineRoutingSupport
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.wire.WireFrame

internal data class MeshEngineInlineOutboundDeliveryAdapterConfig(
    val inlineMessagePayloadBytes: Int
)

internal data class MeshEngineInlineOutboundDeliveryAdapterDependencies(
    val discoverySuspensionSupport: MeshEngineDiscoverySuspensionSupport,
    val prepareOutboundInlineMessage:
        suspend (
            PeerId, ByteArray, DeliveryPriority, Int,
        ) -> MeshEngineOutboundInlineMessagePreparation,
    val dispatchSupport: MeshEngineInlineDispatchSupport,
)

internal data class MeshEngineInlineOutboundDeliveryAdapterCallbacks(
    val emitDiagnostic: MeshEngineEmitDiagnostic,
    val ttlMillisFor: (DeliveryPriority) -> Int,
)

internal class MeshEngineInlineOutboundDeliveryAdapter(
    private val config: MeshEngineInlineOutboundDeliveryAdapterConfig,
    private val dependencies: MeshEngineInlineOutboundDeliveryAdapterDependencies,
    private val callbacks: MeshEngineInlineOutboundDeliveryAdapterCallbacks,
) : MeshEngineOutboundDeliveryAdapter<Unit> {
    override fun currentTopologyVersion(): Long {
        return dependencies.dispatchSupport.currentTopologyVersion()
    }

    override fun beginOutboundDelivery(
        @Suppress("UnusedParameter") context: MeshEngineOutboundDeliveryAttemptContext
    ): Unit = Unit

    override suspend fun <T> withDiscoveryPolicy(
        context: MeshEngineOutboundDeliveryAttemptContext,
        block: suspend () -> T,
    ): T {
        return dependencies.discoverySuspensionSupport.withDiscoverySuspended(
            shouldSuspend = context.payload.size > config.inlineMessagePayloadBytes,
            block = block,
        )
    }

    override suspend fun attemptOutboundDelivery(
        @Suppress("UnusedParameter") state: Unit,
        context: MeshEngineOutboundDeliveryAttemptContext,
    ): MeshEngineOutboundDeliveryAttemptOutcome<Unit> {
        return when (
            val resolution =
                dependencies.dispatchSupport.resolveDispatch(
                    peerId = context.peerId,
                    priority = context.priority,
                    hardRunToken = context.hardRunToken,
                )
        ) {
            MeshEngineInlineDispatchResolution.AwaitRetry -> {
                MeshEngineOutboundDeliveryAttemptOutcome.AwaitRetry(
                    nextState = Unit,
                    retryPolicy =
                        MeshEngineOutboundDeliveryRetryPolicy(INLINE_DELIVERY_RETRY_POLICY),
                )
            }
            MeshEngineInlineDispatchResolution.TrustFailure -> {
                MeshEngineOutboundDeliveryAttemptOutcome.Completed(
                    SendResult.NotSent(SendFailureReason.TRUST_FAILURE)
                )
            }
            is MeshEngineInlineDispatchResolution.Ready -> {
                when (
                    val preparedMessage =
                        dependencies.prepareOutboundInlineMessage(
                            context.peerId,
                            context.payload,
                            context.priority,
                            callbacks.ttlMillisFor(context.priority),
                        )
                ) {
                    MeshEngineOutboundInlineMessagePreparation.MissingTrust -> {
                        callbacks.emitDiagnostic(
                            DiagnosticCode.TRUST_FAILURE,
                            DiagnosticSeverity.ERROR,
                            "delivery.send.noTrust",
                            context.peerId.diagnosticSuffix(),
                            DiagnosticReason.TRUST_FAILURE,
                            emptyMap(),
                        )
                        MeshEngineOutboundDeliveryAttemptOutcome.Completed(
                            SendResult.NotSent(SendFailureReason.TRUST_FAILURE)
                        )
                    }
                    MeshEngineOutboundInlineMessagePreparation.EncryptFailure -> {
                        MeshEngineOutboundDeliveryAttemptOutcome.Completed(
                            SendResult.NotSent(SendFailureReason.TRUST_FAILURE)
                        )
                    }
                    is MeshEngineOutboundInlineMessagePreparation.Ready -> {
                        when (
                            dependencies.dispatchSupport.dispatchPreparedMessage(
                                peerId = context.peerId,
                                priority = context.priority,
                                routedMessage = preparedMessage.message,
                                resolution = resolution,
                            )
                        ) {
                            MeshEngineInlineDispatchResult.Delivered -> {
                                MeshEngineOutboundDeliveryAttemptOutcome.Completed(SendResult.Sent)
                            }
                            MeshEngineInlineDispatchResult.AwaitRetry -> {
                                MeshEngineOutboundDeliveryAttemptOutcome.AwaitRetry(
                                    nextState = Unit,
                                    retryPolicy =
                                        MeshEngineOutboundDeliveryRetryPolicy(
                                            INLINE_DELIVERY_RETRY_POLICY
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override suspend fun onDeadlineExpired(
        @Suppress("UnusedParameter") state: Unit,
        context: MeshEngineOutboundDeliveryAttemptContext,
    ): SendResult {
        callbacks.emitDiagnostic(
            DiagnosticCode.DELIVERY_UNREACHABLE,
            DiagnosticSeverity.ERROR,
            "delivery.retryExpired",
            context.peerId.diagnosticSuffix(),
            DiagnosticReason.DELIVERY_FAILURE,
            dependencies.dispatchSupport.routeMetadata(context.peerId),
        )
        return SendResult.NotSent(SendFailureReason.UNREACHABLE)
    }

    override suspend fun onHardRunEnded(
        @Suppress("UnusedParameter") state: Unit,
        @Suppress("UnusedParameter") context: MeshEngineOutboundDeliveryAttemptContext,
    ): SendResult {
        return SendResult.NotSent(SendFailureReason.TRANSFER_ABORTED)
    }
}

internal fun buildMeshEngineRuntimeInlineOutboundDeliveryAdapter(
    inlineMessagePayloadBytes: Int,
    routeCoordinator: ch.trancee.meshlink.routing.RouteCoordinator,
    routingSupport: MeshEngineRoutingSupport,
    ensureHopSession: suspend (PeerId, MeshEngineHardRunToken?) -> SessionEstablishmentOutcome,
    sendEncryptedDirectWireFrame:
        suspend (PeerId, HopSession, WireFrame, String) -> TransportSendResult,
    emitHopSessionFailed: suspend (PeerId, String, DiagnosticReason, Map<String, String>) -> Unit,
    inlineMessagePreparationSupport: MeshEngineInlineMessagePreparationSupport,
    discoverySuspensionSupport: MeshEngineDiscoverySuspensionSupport,
    ttlMillisFor: (DeliveryPriority) -> Int,
    scheduleRetryDiagnostic: suspend (PeerId, DeliveryPriority) -> Unit,
    emitDiagnostic: MeshEngineEmitDiagnostic,
): MeshEngineInlineOutboundDeliveryAdapter {
    val dispatchSupport =
        MeshEngineInlineDispatchSupport(
            routingContext =
                MeshEngineInlineDispatchRoutingContext(
                    routeCoordinator = routeCoordinator,
                    routingSupport = routingSupport,
                ),
            dependencies =
                MeshEngineInlineDispatchDependencies(
                    ensureHopSession = ensureHopSession,
                    sendEncryptedDirectWireFrame = sendEncryptedDirectWireFrame,
                    scheduleRetryDiagnostic = scheduleRetryDiagnostic,
                    emitHopSessionFailed = emitHopSessionFailed,
                ),
            callbacks = MeshEngineInlineDispatchCallbacks(emitDiagnostic = emitDiagnostic),
        )
    return MeshEngineInlineOutboundDeliveryAdapter(
        config =
            MeshEngineInlineOutboundDeliveryAdapterConfig(
                inlineMessagePayloadBytes = inlineMessagePayloadBytes
            ),
        dependencies =
            MeshEngineInlineOutboundDeliveryAdapterDependencies(
                discoverySuspensionSupport = discoverySuspensionSupport,
                prepareOutboundInlineMessage = inlineMessagePreparationSupport::prepare,
                dispatchSupport = dispatchSupport,
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
