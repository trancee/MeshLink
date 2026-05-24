package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendFailureReason
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.transfer.AcknowledgementSettlementResult
import ch.trancee.meshlink.transfer.OutboundTransferSession
import ch.trancee.meshlink.wire.WireFrame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal data class MeshEngineLargeTransferOutboundDeliveryAdapterConfig(
    val ackSettlementTimeout: Duration,
    val ackIdleWindow: Duration,
)

internal data class MeshEngineLargeTransferOutboundDeliveryAdapterDependencies(
    val runtimeGate: MeshEngineRuntimeGate,
    val currentTopologyVersion: () -> Long,
    val discoverySuspensionSupport: MeshEngineDiscoverySuspensionSupport,
    val scheduleRetryDiagnostic: (PeerId, DeliveryPriority) -> Unit,
    val sendTransferTowardsDestination:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
    val clearQueuedOutboundFrames: suspend (PeerId, String) -> Unit,
)

internal data class MeshEngineLargeTransferOutboundDeliveryState(
    val activeSession: OutboundTransferSession? = null,
    val lastRouteAvailable: Boolean = false,
)

private data class MeshEngineLargeTransferOutboundDeliveryAttemptResult(
    val lastRouteAvailable: Boolean,
    val transferProgressObserved: Boolean,
    val result: SendResult? = null,
)

internal class MeshEngineLargeTransferOutboundDeliveryAdapter(
    private val config: MeshEngineLargeTransferOutboundDeliveryAdapterConfig,
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
                val iterationResult =
                    sendLargeTransferAttempt(
                        session = sessionResolution.session,
                        priority = context.priority,
                        remainingBudget = context.remainingBudget,
                        hardRunToken = context.hardRunToken,
                    )
                val nextState =
                    MeshEngineLargeTransferOutboundDeliveryState(
                        activeSession = sessionResolution.session,
                        lastRouteAvailable = iterationResult.lastRouteAvailable,
                    )
                when {
                    iterationResult.result != null -> {
                        MeshEngineOutboundDeliveryAttemptOutcome.Completed(iterationResult.result)
                    }
                    iterationResult.transferProgressObserved -> {
                        MeshEngineOutboundDeliveryAttemptOutcome.RetryImmediately(nextState)
                    }
                    else -> {
                        MeshEngineOutboundDeliveryAttemptOutcome.AwaitRetry(
                            nextState = nextState,
                            retryPolicy =
                                MeshEngineOutboundDeliveryRetryPolicy(
                                    profile = LARGE_TRANSFER_DELIVERY_RETRY_POLICY,
                                    emitDiagnostics = !iterationResult.lastRouteAvailable,
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

    private suspend fun sendLargeTransferAttempt(
        session: OutboundTransferSession,
        priority: DeliveryPriority,
        remainingBudget: Duration,
        hardRunToken: MeshEngineHardRunToken,
    ): MeshEngineLargeTransferOutboundDeliveryAttemptResult {
        var routeAvailable =
            dependencies.sendTransferTowardsDestination(
                session.destinationPeerId,
                session.asStartFrame(),
                "transfer.start",
                hardRunToken,
            )
        var transferProgressObserved = false
        val result =
            if (session.isComplete()) {
                completeOutboundTransferSession(session, hardRunToken)
            } else {
                session.forEachMissingChunkIndex { chunkIndex ->
                    routeAvailable =
                        dependencies.sendTransferTowardsDestination(
                            session.destinationPeerId,
                            WireFrame.TransferChunk(
                                transferId = session.transferId,
                                chunkIndex = chunkIndex,
                                payload = session.chunks[chunkIndex],
                            ),
                            "transfer.chunk",
                            hardRunToken,
                        ) || routeAvailable
                }
                emitLargeTransferProgressDiagnostic(session)
                if (!routeAvailable) {
                    dependencies.scheduleRetryDiagnostic(session.destinationPeerId, priority)
                    null
                } else {
                    transferProgressObserved =
                        awaitLargeTransferAcknowledgementProgress(
                            session = session,
                            remainingBudget = remainingBudget,
                            hardRunToken = hardRunToken,
                        )
                    if (session.isComplete()) {
                        completeOutboundTransferSession(session, hardRunToken)
                    } else {
                        null
                    }
                }
            }

        return MeshEngineLargeTransferOutboundDeliveryAttemptResult(
            lastRouteAvailable = routeAvailable,
            transferProgressObserved = transferProgressObserved,
            result = result,
        )
    }

    private fun emitLargeTransferProgressDiagnostic(session: OutboundTransferSession): Unit {
        emitDiagnostic(
            DiagnosticCode.TRANSFER_PROGRESS,
            DiagnosticSeverity.DEBUG,
            "transfer.send.progress",
            session.destinationPeerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
            null,
            routingSupport.peerRouteMetadata(
                session.destinationPeerId,
                metadata =
                    mapOf(
                        "ackedChunks" to session.acknowledgedChunkCount().toString(),
                        "totalChunks" to session.totalChunks.toString(),
                    ),
            ),
        )
    }

    private suspend fun awaitLargeTransferAcknowledgementProgress(
        session: OutboundTransferSession,
        remainingBudget: Duration,
        hardRunToken: MeshEngineHardRunToken,
    ): Boolean {
        val acknowledgedChunkCountBeforeSettlement = session.acknowledgedChunkCount()
        return when (
            val settlement =
                session.awaitAcknowledgementSettlement(
                    maximumWait = remainingBudget.coerceAtMost(config.ackSettlementTimeout),
                    idleWindow = config.ackIdleWindow,
                    runtimeGate = dependencies.runtimeGate,
                    hardRunToken = hardRunToken,
                )
        ) {
            is AcknowledgementSettlementResult.Completed -> {
                settlement.acknowledgedChunkCount > acknowledgedChunkCountBeforeSettlement
            }
            AcknowledgementSettlementResult.HardRunEnded -> false
        }
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
    return MeshEngineLargeTransferOutboundDeliveryAdapter(
        config =
            MeshEngineLargeTransferOutboundDeliveryAdapterConfig(
                ackSettlementTimeout = TRANSFER_ACK_SETTLEMENT_TIMEOUT,
                ackIdleWindow = TRANSFER_ACK_IDLE_WINDOW,
            ),
        routingSupport = routingSupport,
        outboundTransferLifecycleSupport = outboundTransferLifecycleSupport,
        dependencies =
            MeshEngineLargeTransferOutboundDeliveryAdapterDependencies(
                runtimeGate = runtimeGate,
                currentTopologyVersion = currentTopologyVersion,
                discoverySuspensionSupport = discoverySuspensionSupport,
                scheduleRetryDiagnostic = scheduleRetryDiagnostic,
                sendTransferTowardsDestination = sendTransferTowardsDestination,
                clearQueuedOutboundFrames = clearQueuedOutboundFrames,
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
