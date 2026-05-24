package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendFailureReason
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.transfer.OutboundTransferSession
import ch.trancee.meshlink.wire.WireFrame
import kotlin.time.Duration.Companion.milliseconds

internal data class MeshEngineLargeTransferOutboundDeliveryAdapterDependencies(
    val currentTopologyVersion: () -> Long,
    val discoverySuspensionSupport: MeshEngineDiscoverySuspensionSupport,
    val sendTransferTowardsDestination:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
    val clearQueuedOutboundFrames: suspend (PeerId, String) -> Unit,
    val progressSupport: MeshEngineLargeTransferProgressSupport,
)

internal data class MeshEngineLargeTransferOutboundDeliveryState(
    val activeSession: OutboundTransferSession? = null,
    val lastRouteAvailable: Boolean = false,
)

internal class MeshEngineLargeTransferOutboundDeliveryAdapter(
    private val routingSupport: MeshEngineRoutingSupport,
    private val outboundTransferLifecycleSupport: MeshEngineOutboundTransferLifecycleSupport,
    private val dependencies: MeshEngineLargeTransferOutboundDeliveryAdapterDependencies,
    private val emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit,
) {
    fun currentTopologyVersion(): Long {
        return dependencies.currentTopologyVersion()
    }

    fun beginOutboundDelivery(
        @Suppress("UnusedParameter") context: MeshEngineOutboundDeliveryAttemptContext
    ): MeshEngineLargeTransferOutboundDeliveryState {
        return MeshEngineLargeTransferOutboundDeliveryState()
    }

    suspend fun <T> withDiscoveryPolicy(
        @Suppress("UnusedParameter") context: MeshEngineOutboundDeliveryAttemptContext,
        block: suspend () -> T,
    ): T {
        return dependencies.discoverySuspensionSupport.withDiscoverySuspended(block = block)
    }

    suspend fun attemptOutboundDelivery(
        state: MeshEngineLargeTransferOutboundDeliveryState,
        context: MeshEngineOutboundDeliveryAttemptContext,
    ): MeshEngineOutboundDeliveryAttemptOutcome<MeshEngineLargeTransferOutboundDeliveryState> {
        return when (
            val sessionResolution =
                outboundTransferLifecycleSupport.resolveActiveOrPrepareSession(
                    activeSession = state.activeSession,
                    peerId = context.peerId,
                    payload = context.payload,
                    priority = context.priority,
                    hardRunToken = context.hardRunToken,
                )
        ) {
            MeshEngineOutboundTransferLifecycleResolution.AwaitRetry ->
                MeshEngineOutboundDeliveryAttemptOutcome.AwaitRetry(
                    nextState = MeshEngineLargeTransferOutboundDeliveryState(),
                    retryPolicy =
                        MeshEngineOutboundDeliveryRetryPolicy(
                            profile = LARGE_TRANSFER_DELIVERY_RETRY_POLICY,
                            emitDiagnostics = true,
                        ),
                )
            is MeshEngineOutboundTransferLifecycleResolution.Completed ->
                MeshEngineOutboundDeliveryAttemptOutcome.Completed(sessionResolution.result)
            is MeshEngineOutboundTransferLifecycleResolution.Ready -> {
                val progressResult =
                    dependencies.progressSupport.advance(
                        session = sessionResolution.session,
                        priority = context.priority,
                        remainingBudget = context.remainingBudget,
                        hardRunToken = context.hardRunToken,
                    )
                val nextState =
                    MeshEngineLargeTransferOutboundDeliveryState(
                        activeSession = sessionResolution.session,
                        lastRouteAvailable = progressResult.lastRouteAvailable,
                    )
                when {
                    progressResult.sessionIsComplete -> {
                        MeshEngineOutboundDeliveryAttemptOutcome.Completed(
                            completeOutboundTransferSession(
                                session = sessionResolution.session,
                                hardRunToken = context.hardRunToken,
                            )
                        )
                    }
                    progressResult.transferProgressObserved -> {
                        MeshEngineOutboundDeliveryAttemptOutcome.RetryImmediately(nextState)
                    }
                    else -> {
                        MeshEngineOutboundDeliveryAttemptOutcome.AwaitRetry(
                            nextState = nextState,
                            retryPolicy =
                                MeshEngineOutboundDeliveryRetryPolicy(
                                    profile = LARGE_TRANSFER_DELIVERY_RETRY_POLICY,
                                    emitDiagnostics = !progressResult.lastRouteAvailable,
                                ),
                        )
                    }
                }
            }
        }
    }

    suspend fun onDeadlineExpired(
        state: MeshEngineLargeTransferOutboundDeliveryState,
        context: MeshEngineOutboundDeliveryAttemptContext,
    ): SendResult {
        return finishFailedLargeTransfer(
            activeSession = state.activeSession,
            peerId = context.peerId,
            lastRouteAvailable = state.lastRouteAvailable,
        )
    }

    suspend fun onHardRunEnded(
        state: MeshEngineLargeTransferOutboundDeliveryState,
        @Suppress("UnusedParameter") context: MeshEngineOutboundDeliveryAttemptContext,
    ): SendResult {
        return abortedLargeTransferResult(state.activeSession)
    }

    private suspend fun finishFailedLargeTransfer(
        activeSession: OutboundTransferSession?,
        peerId: PeerId,
        lastRouteAvailable: Boolean,
    ): SendResult {
        activeSession?.let { session ->
            outboundTransferLifecycleSupport.removeSession(session.transferId)
            dependencies.clearQueuedOutboundFrames(
                session.destinationPeerId,
                "transfer.clearQueuedFramesOnFailure",
            )
        }
        return if (!lastRouteAvailable) {
            emitDiagnostic(
                DiagnosticCode.DELIVERY_UNREACHABLE,
                DiagnosticSeverity.ERROR,
                "transfer.retryExpired",
                peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                DiagnosticReason.DELIVERY_FAILURE,
                routingSupport.peerRouteMetadata(peerId),
            )
            SendResult.NotSent(SendFailureReason.UNREACHABLE)
        } else {
            emitDiagnostic(
                DiagnosticCode.TRANSFER_FAILED,
                DiagnosticSeverity.ERROR,
                "transfer.send.timeout",
                peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                DiagnosticReason.TRANSFER_FAILURE,
                routingSupport.peerRouteMetadata(peerId),
            )
            SendResult.NotSent(SendFailureReason.TRANSFER_TIMED_OUT)
        }
    }

    private suspend fun completeOutboundTransferSession(
        session: OutboundTransferSession,
        hardRunToken: MeshEngineHardRunToken,
    ): SendResult {
        dependencies.clearQueuedOutboundFrames(
            session.destinationPeerId,
            "transfer.clearQueuedFrames",
        )
        dependencies.sendTransferTowardsDestination(
            session.destinationPeerId,
            WireFrame.TransferComplete(session.transferId),
            "transfer.complete",
            hardRunToken,
        )
        outboundTransferLifecycleSupport.removeSession(session.transferId)
        emitDiagnostic(
            DiagnosticCode.TRANSFER_COMPLETED,
            DiagnosticSeverity.INFO,
            "transfer.send.complete",
            session.destinationPeerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
            null,
            routingSupport.peerRouteMetadata(session.destinationPeerId),
        )
        return SendResult.Sent
    }

    private suspend fun abortedLargeTransferResult(
        activeSession: OutboundTransferSession?
    ): SendResult {
        activeSession?.let { session ->
            outboundTransferLifecycleSupport.removeSession(session.transferId)
            dependencies.clearQueuedOutboundFrames(
                session.destinationPeerId,
                "transfer.clearQueuedFramesOnAbort",
            )
        }
        return SendResult.NotSent(SendFailureReason.TRANSFER_ABORTED)
    }
}

internal fun buildMeshEngineRuntimeLargeTransferOutboundDeliveryAdapter(
    routingSupport: MeshEngineRoutingSupport,
    runtimeGate: MeshEngineRuntimeGate,
    currentTopologyVersion: () -> Long,
    outboundTransferLifecycleSupport: MeshEngineOutboundTransferLifecycleSupport,
    discoverySuspensionSupport: MeshEngineDiscoverySuspensionSupport,
    scheduleRetryDiagnostic: (PeerId, DeliveryPriority) -> Unit,
    sendTransferTowardsDestination:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
    clearQueuedOutboundFrames: suspend (PeerId, String) -> Unit,
    emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit,
): MeshEngineLargeTransferOutboundDeliveryAdapter {
    val progressSupport =
        MeshEngineLargeTransferProgressSupport(
            config =
                MeshEngineLargeTransferProgressConfig(
                    ackSettlementTimeout = TRANSFER_ACK_SETTLEMENT_TIMEOUT,
                    ackIdleWindow = TRANSFER_ACK_IDLE_WINDOW,
                ),
            dependencies =
                MeshEngineLargeTransferProgressDependencies(
                    runtimeGate = runtimeGate,
                    scheduleRetryDiagnostic = scheduleRetryDiagnostic,
                    sendTransferTowardsDestination = sendTransferTowardsDestination,
                ),
            callbacks =
                MeshEngineLargeTransferProgressCallbacks(
                    emitDiagnostic = emitDiagnostic,
                    routeMetadata = { peerId, metadata ->
                        routingSupport.peerRouteMetadata(peerId = peerId, metadata = metadata)
                    },
                ),
        )
    return MeshEngineLargeTransferOutboundDeliveryAdapter(
        routingSupport = routingSupport,
        outboundTransferLifecycleSupport = outboundTransferLifecycleSupport,
        dependencies =
            MeshEngineLargeTransferOutboundDeliveryAdapterDependencies(
                currentTopologyVersion = currentTopologyVersion,
                discoverySuspensionSupport = discoverySuspensionSupport,
                sendTransferTowardsDestination = sendTransferTowardsDestination,
                clearQueuedOutboundFrames = clearQueuedOutboundFrames,
                progressSupport = progressSupport,
            ),
        emitDiagnostic = emitDiagnostic,
    )
}

private val LARGE_TRANSFER_DELIVERY_RETRY_POLICY =
    MeshEngineDeliveryRetryProfile(
        scheduledStage = "transfer.retryScheduled",
        retryingStage = "transfer.retrying",
    )

private val TRANSFER_ACK_SETTLEMENT_TIMEOUT = 1_500.milliseconds
private val TRANSFER_ACK_IDLE_WINDOW = 100.milliseconds
