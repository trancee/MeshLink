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

internal data class MeshEngineLargeTransferConfig(
    val ackSettlementTimeout: Duration,
    val ackIdleWindow: Duration,
)

internal data class MeshEngineLargeTransferState(
    val outboundTransfers: MutableMap<String, OutboundTransferSession>
)

internal data class MeshEngineLargeTransferDependencies(
    val runtimeGate: MeshEngineRuntimeGate,
    val currentTopologyVersion: () -> Long,
    val discoverySuspensionSupport: MeshEngineDiscoverySuspensionSupport,
    val prepareOutboundTransferSession:
        suspend (PeerId, ByteArray, MeshEngineHardRunToken) -> OutboundTransferPreparation,
    val scheduleRetryDiagnostic: (PeerId, DeliveryPriority) -> Unit,
    val sendTransferTowardsDestination:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
    val clearQueuedOutboundFrames: suspend (PeerId, String) -> Unit,
)

internal data class MeshEngineLargeTransferSendState(
    val activeSession: OutboundTransferSession? = null,
    val lastRouteAvailable: Boolean = false,
)

internal class MeshEngineLargeTransferSupport(
    private val config: MeshEngineLargeTransferConfig,
    private val state: MeshEngineLargeTransferState,
    private val routingSupport: MeshEngineRoutingSupport,
    private val dependencies: MeshEngineLargeTransferDependencies,
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
    ): MeshEngineLargeTransferSendState {
        return MeshEngineLargeTransferSendState()
    }

    suspend fun <T> withDiscoveryPolicy(
        @Suppress("UnusedParameter") context: MeshEngineOutboundDeliveryAttemptContext,
        block: suspend () -> T,
    ): T {
        return dependencies.discoverySuspensionSupport.withDiscoverySuspended(block = block)
    }

    suspend fun attemptOutboundDelivery(
        state: MeshEngineLargeTransferSendState,
        context: MeshEngineOutboundDeliveryAttemptContext,
    ): MeshEngineOutboundDeliveryAttemptOutcome<MeshEngineLargeTransferSendState> {
        val loopResult =
            advanceLargeTransferSendLoop(
                activeSession = state.activeSession,
                peerId = context.peerId,
                payload = context.payload,
                priority = context.priority,
                remainingBudget = context.remainingBudget,
                hardRunToken = context.hardRunToken,
            )
        val nextState =
            MeshEngineLargeTransferSendState(
                activeSession = loopResult.session,
                lastRouteAvailable = loopResult.lastRouteAvailable,
            )
        return when {
            loopResult.result != null -> {
                MeshEngineOutboundDeliveryAttemptOutcome.Completed(loopResult.result)
            }
            loopResult.transferProgressObserved -> {
                MeshEngineOutboundDeliveryAttemptOutcome.RetryImmediately(nextState)
            }
            else -> {
                MeshEngineOutboundDeliveryAttemptOutcome.AwaitRetry(
                    nextState = nextState,
                    retryPolicy =
                        MeshEngineOutboundDeliveryRetryPolicy(
                            profile = LARGE_TRANSFER_DELIVERY_RETRY_POLICY,
                            emitDiagnostics = !loopResult.lastRouteAvailable,
                        ),
                )
            }
        }
    }

    suspend fun onDeadlineExpired(
        state: MeshEngineLargeTransferSendState,
        context: MeshEngineOutboundDeliveryAttemptContext,
    ): SendResult {
        return finishFailedLargeTransfer(
            activeSession = state.activeSession,
            peerId = context.peerId,
            lastRouteAvailable = state.lastRouteAvailable,
        )
    }

    suspend fun onHardRunEnded(
        state: MeshEngineLargeTransferSendState,
        @Suppress("UnusedParameter") context: MeshEngineOutboundDeliveryAttemptContext,
    ): SendResult {
        return abortedLargeTransferResult(state.activeSession)
    }

    private suspend fun advanceLargeTransferSendLoop(
        activeSession: OutboundTransferSession?,
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
        remainingBudget: Duration,
        hardRunToken: MeshEngineHardRunToken,
    ): LargeTransferLoopResult {
        val sessionResolution =
            resolveLargeTransferSession(
                activeSession = activeSession,
                peerId = peerId,
                payload = payload,
                priority = priority,
                hardRunToken = hardRunToken,
            )
        val resolvedResult = sessionResolution.result
        val session = sessionResolution.session

        return if (resolvedResult != null) {
            LargeTransferLoopResult(
                session = null,
                lastRouteAvailable = sessionResolution.lastRouteAvailable,
                result = resolvedResult,
            )
        } else if (session == null) {
            LargeTransferLoopResult(
                session = null,
                lastRouteAvailable = sessionResolution.lastRouteAvailable,
            )
        } else {
            val iterationResult =
                sendLargeTransferIteration(
                    session = session,
                    priority = priority,
                    remainingBudget = remainingBudget,
                    hardRunToken = hardRunToken,
                )
            LargeTransferLoopResult(
                session = session,
                lastRouteAvailable = iterationResult.lastRouteAvailable,
                transferProgressObserved = iterationResult.transferProgressObserved,
                result = iterationResult.result,
            )
        }
    }

    private suspend fun resolveLargeTransferSession(
        activeSession: OutboundTransferSession?,
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
        hardRunToken: MeshEngineHardRunToken,
    ): LargeTransferSessionResolution {
        if (activeSession != null) {
            return LargeTransferSessionResolution(
                session = activeSession,
                lastRouteAvailable = true,
            )
        }

        return when (
            val preparation =
                dependencies.prepareOutboundTransferSession(peerId, payload, hardRunToken)
        ) {
            OutboundTransferPreparation.PendingRoute -> {
                dependencies.scheduleRetryDiagnostic(peerId, priority)
                LargeTransferSessionResolution(session = null, lastRouteAvailable = false)
            }
            is OutboundTransferPreparation.Ready ->
                LargeTransferSessionResolution(
                    session = preparation.session,
                    lastRouteAvailable = true,
                )
            is OutboundTransferPreparation.Failed ->
                LargeTransferSessionResolution(
                    session = null,
                    lastRouteAvailable = true,
                    result = preparation.result,
                )
        }
    }

    private suspend fun sendLargeTransferIteration(
        session: OutboundTransferSession,
        priority: DeliveryPriority,
        remainingBudget: Duration,
        hardRunToken: MeshEngineHardRunToken,
    ): LargeTransferIterationResult {
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

        return LargeTransferIterationResult(
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
            state.outboundTransfers.remove(session.transferId)
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
        state.outboundTransfers.remove(session.transferId)
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
            state.outboundTransfers.remove(session.transferId)
            dependencies.clearQueuedOutboundFrames(
                session.destinationPeerId,
                "transfer.clearQueuedFramesOnAbort",
            )
        }
        return SendResult.NotSent(SendFailureReason.TRANSFER_ABORTED)
    }
}

internal fun buildMeshEngineRuntimeLargeTransferSupport(
    outboundTransfers: MutableMap<String, OutboundTransferSession>,
    routingSupport: MeshEngineRoutingSupport,
    runtimeGate: MeshEngineRuntimeGate,
    currentTopologyVersion: () -> Long,
    outboundPreparationSupport: MeshEngineOutboundPreparationSupport,
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
): MeshEngineLargeTransferSupport {
    return MeshEngineLargeTransferSupport(
        config =
            MeshEngineLargeTransferConfig(
                ackSettlementTimeout = TRANSFER_ACK_SETTLEMENT_TIMEOUT,
                ackIdleWindow = TRANSFER_ACK_IDLE_WINDOW,
            ),
        state = MeshEngineLargeTransferState(outboundTransfers = outboundTransfers),
        routingSupport = routingSupport,
        dependencies =
            MeshEngineLargeTransferDependencies(
                runtimeGate = runtimeGate,
                currentTopologyVersion = currentTopologyVersion,
                discoverySuspensionSupport = discoverySuspensionSupport,
                prepareOutboundTransferSession =
                    outboundPreparationSupport::prepareOutboundTransferSession,
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
