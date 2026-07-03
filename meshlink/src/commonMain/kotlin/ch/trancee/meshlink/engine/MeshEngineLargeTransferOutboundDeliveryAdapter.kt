package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
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
    val shouldSuspendDiscovery: suspend (PeerId) -> Boolean,
    val progressSupport: MeshEngineLargeTransferProgressSupport,
    val terminalSupport: MeshEngineLargeTransferTerminalSupport,
)

internal data class MeshEngineLargeTransferOutboundDeliveryState(
    val activeSession: OutboundTransferSession? = null,
    val lastRouteAvailable: Boolean = false,
)

internal class MeshEngineLargeTransferOutboundDeliveryAdapter(
    private val outboundTransferLifecycleSupport: MeshEngineOutboundTransferLifecycleSupport,
    private val dependencies: MeshEngineLargeTransferOutboundDeliveryAdapterDependencies,
) : MeshEngineOutboundDeliveryAdapter<MeshEngineLargeTransferOutboundDeliveryState> {
    override fun currentTopologyVersion(): Long {
        return dependencies.currentTopologyVersion()
    }

    override fun beginOutboundDelivery(
        @Suppress("UnusedParameter") context: MeshEngineOutboundDeliveryAttemptContext
    ): MeshEngineLargeTransferOutboundDeliveryState {
        return MeshEngineLargeTransferOutboundDeliveryState()
    }

    override suspend fun <T> withDiscoveryPolicy(
        context: MeshEngineOutboundDeliveryAttemptContext,
        block: suspend () -> T,
    ): T {
        return dependencies.discoverySuspensionSupport.withDiscoverySuspended(
            shouldSuspend = dependencies.shouldSuspendDiscovery(context.peerId),
            block = block,
        )
    }

    override suspend fun attemptOutboundDelivery(
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
                            dependencies.terminalSupport.complete(
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

    override suspend fun onDeadlineExpired(
        state: MeshEngineLargeTransferOutboundDeliveryState,
        context: MeshEngineOutboundDeliveryAttemptContext,
    ): SendResult {
        return dependencies.terminalSupport.fail(
            activeSession = state.activeSession,
            peerId = context.peerId,
            lastRouteAvailable = state.lastRouteAvailable,
        )
    }

    override suspend fun onHardRunEnded(
        state: MeshEngineLargeTransferOutboundDeliveryState,
        @Suppress("UnusedParameter") context: MeshEngineOutboundDeliveryAttemptContext,
    ): SendResult {
        return dependencies.terminalSupport.abort(state.activeSession)
    }
}

internal fun buildMeshEngineRuntimeLargeTransferOutboundDeliveryAdapter(
    routingSupport: MeshEngineRoutingSupport,
    runtimeGate: MeshEngineRuntimeGate,
    currentTopologyVersion: () -> Long,
    outboundTransferLifecycleSupport: MeshEngineOutboundTransferLifecycleSupport,
    discoverySuspensionSupport: MeshEngineDiscoverySuspensionSupport,
    scheduleRetryDiagnostic: suspend (PeerId, DeliveryPriority) -> Unit,
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
    val terminalSupport =
        MeshEngineLargeTransferTerminalSupport(
            outboundTransferLifecycleSupport = outboundTransferLifecycleSupport,
            dependencies =
                MeshEngineLargeTransferTerminalDependencies(
                    clearQueuedOutboundFrames = clearQueuedOutboundFrames,
                    sendTransferTowardsDestination = sendTransferTowardsDestination,
                ),
            callbacks =
                MeshEngineLargeTransferTerminalCallbacks(
                    routeMetadata = { peerId -> routingSupport.peerRouteMetadata(peerId = peerId) },
                    emitDiagnostic = emitDiagnostic,
                ),
        )
    return MeshEngineLargeTransferOutboundDeliveryAdapter(
        outboundTransferLifecycleSupport = outboundTransferLifecycleSupport,
        dependencies =
            MeshEngineLargeTransferOutboundDeliveryAdapterDependencies(
                currentTopologyVersion = currentTopologyVersion,
                discoverySuspensionSupport = discoverySuspensionSupport,
                shouldSuspendDiscovery = { peerId ->
                    routingSupport.peerRouteMetadata(peerId)["routeAvailable"] == "true"
                },
                progressSupport = progressSupport,
                terminalSupport = terminalSupport,
            ),
    )
}

private val LARGE_TRANSFER_DELIVERY_RETRY_POLICY =
    MeshEngineDeliveryRetryProfile(
        scheduledStage = "transfer.retryScheduled",
        retryingStage = "transfer.retrying",
    )

private val TRANSFER_ACK_SETTLEMENT_TIMEOUT = 1_500.milliseconds
private val TRANSFER_ACK_IDLE_WINDOW = 100.milliseconds
