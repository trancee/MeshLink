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
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal data class MeshEngineLargeTransferConfig(
    val deliveryRetryDeadline: Duration,
    val ackSettlementTimeout: Duration,
    val ackIdleWindow: Duration,
)

internal data class MeshEngineLargeTransferState(
    val outboundTransfers: MutableMap<String, OutboundTransferSession>
)

internal data class MeshEngineLargeTransferDependencies(
    val runtimeGate: MeshEngineRuntimeGate,
    val currentTopologyVersion: () -> Long,
    val deliveryRetryScheduler: DeliveryRetryScheduler,
    val prepareOutboundTransferSession:
        suspend (PeerId, ByteArray, MeshEngineHardRunToken) -> OutboundTransferPreparation,
    val scheduleRetryDiagnostic: (PeerId, DeliveryPriority) -> Unit,
    val sendTransferTowardsDestination:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
    val setDiscoverySuspended: suspend (Boolean) -> Unit,
    val clearQueuedOutboundFrames: suspend (PeerId, String) -> Unit,
)

private sealed class LargeTransferRetryWakeupResult {
    internal class Woke internal constructor(internal val state: LargeTransferRetryWakeupState) :
        LargeTransferRetryWakeupResult()

    internal data object DeadlineExpired : LargeTransferRetryWakeupResult()

    internal data object HardRunEnded : LargeTransferRetryWakeupResult()
}

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
    suspend fun sendLargePayload(
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
        hardRunToken: MeshEngineHardRunToken,
    ): SendResult {
        val startedAt = TimeSource.Monotonic.markNow()
        var attempt = 0
        var topologyVersion = dependencies.currentTopologyVersion()
        var activeSession: OutboundTransferSession? = null
        var lastRouteAvailable = false

        dependencies.setDiscoverySuspended(true)
        try {
            while (startedAt.elapsedNow() < config.deliveryRetryDeadline) {
                val loopResult =
                    advanceLargeTransferSendLoop(
                        activeSession = activeSession,
                        peerId = peerId,
                        payload = payload,
                        priority = priority,
                        startedAt = startedAt,
                        hardRunToken = hardRunToken,
                    )
                val completedResult = loopResult.result
                if (completedResult != null) {
                    return completedResult
                }
                activeSession = loopResult.session
                lastRouteAvailable = loopResult.lastRouteAvailable

                if (loopResult.transferProgressObserved) {
                    attempt = 0
                } else {
                    when (
                        val wakeupState =
                            awaitLargeTransferRetryWakeup(
                                peerId = peerId,
                                attempt = attempt,
                                topologyVersion = topologyVersion,
                                startedAt = startedAt,
                                noRouteRetry = !lastRouteAvailable,
                                hardRunToken = hardRunToken,
                            )
                    ) {
                        is LargeTransferRetryWakeupResult.Woke -> {
                            topologyVersion = wakeupState.state.topologyVersion
                            attempt = wakeupState.state.attempt
                        }
                        LargeTransferRetryWakeupResult.DeadlineExpired -> break
                        LargeTransferRetryWakeupResult.HardRunEnded -> {
                            return abortedLargeTransferResult(activeSession)
                        }
                    }
                }
            }
            return finishFailedLargeTransfer(
                activeSession = activeSession,
                peerId = peerId,
                lastRouteAvailable = lastRouteAvailable,
            )
        } finally {
            dependencies.setDiscoverySuspended(false)
        }
    }

    private suspend fun advanceLargeTransferSendLoop(
        activeSession: OutboundTransferSession?,
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
        startedAt: TimeMark,
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
                    startedAt = startedAt,
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
        startedAt: TimeMark,
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
                            startedAt = startedAt,
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
        startedAt: TimeMark,
        hardRunToken: MeshEngineHardRunToken,
    ): Boolean {
        val acknowledgedChunkCountBeforeSettlement = session.acknowledgedChunkCount()
        return when (
            val settlement =
                session.awaitAcknowledgementSettlement(
                    maximumWait =
                        (config.deliveryRetryDeadline - startedAt.elapsedNow()).coerceAtMost(
                            config.ackSettlementTimeout
                        ),
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

    private suspend fun awaitLargeTransferRetryWakeup(
        peerId: PeerId,
        attempt: Int,
        topologyVersion: Long,
        startedAt: TimeMark,
        noRouteRetry: Boolean,
        hardRunToken: MeshEngineHardRunToken,
    ): LargeTransferRetryWakeupResult {
        if (noRouteRetry) {
            emitDiagnostic(
                DiagnosticCode.DELIVERY_RETRY_SCHEDULED,
                DiagnosticSeverity.WARN,
                "transfer.retryScheduled",
                peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                DiagnosticReason.DELIVERY_RETRY,
                routingSupport.peerRouteMetadata(
                    peerId,
                    metadata = mapOf("attempt" to attempt.toString()),
                ),
            )
        }

        val wakeupState =
            when (
                val wakeup =
                    dependencies.deliveryRetryScheduler.awaitRetry(
                        attempt = attempt,
                        remainingBudget = config.deliveryRetryDeadline - startedAt.elapsedNow(),
                        lastObservedTopologyVersion = topologyVersion,
                        runtimeGate = dependencies.runtimeGate,
                        hardRunToken = hardRunToken,
                    )
            ) {
                is RetryWakeup.DeadlineExpired -> LargeTransferRetryWakeupResult.DeadlineExpired
                is RetryWakeup.TimerElapsed ->
                    LargeTransferRetryWakeupResult.Woke(
                        LargeTransferRetryWakeupState(
                            attempt = attempt + 1,
                            topologyVersion = wakeup.topologyVersion,
                        )
                    )
                is RetryWakeup.TopologyChanged ->
                    LargeTransferRetryWakeupResult.Woke(
                        LargeTransferRetryWakeupState(
                            attempt = 0,
                            topologyVersion = wakeup.topologyVersion,
                        )
                    )
                RetryWakeup.HardRunEnded -> LargeTransferRetryWakeupResult.HardRunEnded
            }
        if (noRouteRetry && wakeupState is LargeTransferRetryWakeupResult.Woke) {
            emitDiagnostic(
                DiagnosticCode.DELIVERY_RETRYING,
                DiagnosticSeverity.WARN,
                "transfer.retrying",
                peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                DiagnosticReason.DELIVERY_RETRY,
                routingSupport.peerRouteMetadata(
                    peerId,
                    metadata = mapOf("attempt" to wakeupState.state.attempt.toString()),
                ),
            )
        }
        return wakeupState
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
