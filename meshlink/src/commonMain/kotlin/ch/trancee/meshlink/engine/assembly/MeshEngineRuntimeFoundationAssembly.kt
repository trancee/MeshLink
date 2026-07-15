package ch.trancee.meshlink.engine.assembly

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.engine.handshake.MeshEngineEndToEndSessionRegistry
import ch.trancee.meshlink.engine.handshake.MeshEngineSessionRegistry
import ch.trancee.meshlink.engine.internal.MeshEngineSequenceGenerator
import ch.trancee.meshlink.engine.internal.diagnosticSuffix
import ch.trancee.meshlink.engine.routing.MeshEngineRoutingSupport
import ch.trancee.meshlink.engine.transfer.DeliveryRetryScheduler
import ch.trancee.meshlink.engine.transfer.MeshEngineTransferRegistry
import ch.trancee.meshlink.engine.trust.MeshEngineTrustSupport
import ch.trancee.meshlink.platform.currentEpochMillis
import ch.trancee.meshlink.power.PowerPolicyController
import ch.trancee.meshlink.presence.PeerPresenceTracker
import ch.trancee.meshlink.routing.RouteCoordinator
import kotlin.time.TimeSource

internal data class MeshEngineRuntimeFoundationAssembly(
    val sharedState: MeshEngineRuntimeSharedState,
    val routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
)

internal data class MeshEngineRuntimeSharedState(
    val presenceTracker: PeerPresenceTracker,
    val routeCoordinator: RouteCoordinator,
    val deliveryRetryScheduler: DeliveryRetryScheduler,
    val powerPolicyController: PowerPolicyController,
    val sessionRegistry: MeshEngineSessionRegistry,
    val endToEndSessionRegistry: MeshEngineEndToEndSessionRegistry,
    val transferRegistry: MeshEngineTransferRegistry,
    val sequenceGenerator: MeshEngineSequenceGenerator,
    val runtimePolicies: MeshEngineRuntimePolicies,
    val localSelfRouteSeqNo: Long,
) {
    suspend fun outboundTransfers():
        Map<String, ch.trancee.meshlink.transfer.OutboundTransferSession> =
        transferRegistry.outboundTransfersSnapshot()

    suspend fun inboundTransfers():
        Map<String, ch.trancee.meshlink.transfer.InboundTransferSession> =
        transferRegistry.inboundTransfersSnapshot()

    suspend fun relayTransfers(): Map<String, ch.trancee.meshlink.transfer.RelayTransferSession> =
        transferRegistry.relayTransfersSnapshot()
}

internal data class MeshEngineRuntimeRoutingAndTrustPhase(
    val routingSupport: MeshEngineRoutingSupport,
    val trustSupport: MeshEngineTrustSupport,
    val scheduleRetryDiagnostic: suspend (PeerId, DeliveryPriority) -> Unit,
)

internal fun buildMeshEngineRuntimeFoundationAssembly(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    support: MeshEngineRuntimeAssemblySupport,
    lateBindingContext: MeshEngineRuntimeLateBindingContext,
): MeshEngineRuntimeFoundationAssembly {
    val sharedState = buildMeshEngineRuntimeSharedState(environment)
    val routingAndTrust =
        buildMeshEngineRuntimeRoutingAndTrustPhase(
            environment = environment,
            support = support,
            lateBindingContext = lateBindingContext,
            sharedState = sharedState,
        )
    return MeshEngineRuntimeFoundationAssembly(
        sharedState = sharedState,
        routingAndTrust = routingAndTrust,
    )
}

private fun buildMeshEngineRuntimeSharedState(
    environment: MeshEngineRuntimeAssemblyEnvironment
): MeshEngineRuntimeSharedState {
    val routeCoordinator = RouteCoordinator(environment.localIdentity.peerId)
    val engineClock = TimeSource.Monotonic.markNow()
    val localSelfRouteSeqNo = maxOf(1L, currentEpochMillis())
    return MeshEngineRuntimeSharedState(
        presenceTracker = PeerPresenceTracker(),
        routeCoordinator = routeCoordinator,
        deliveryRetryScheduler = DeliveryRetryScheduler(routeCoordinator.topologyVersion),
        powerPolicyController =
            PowerPolicyController(
                configuredMode = environment.config.powerMode,
                region = environment.config.regulatoryRegion,
            ),
        sessionRegistry = MeshEngineSessionRegistry(),
        endToEndSessionRegistry = MeshEngineEndToEndSessionRegistry(),
        transferRegistry = MeshEngineTransferRegistry(),
        sequenceGenerator = MeshEngineSequenceGenerator(environment.localIdentity),
        runtimePolicies =
            buildMeshEngineRuntimePolicies(
                config = environment.config,
                powerPolicyNowMillis = { engineClock.elapsedNow().inWholeMilliseconds },
            ),
        localSelfRouteSeqNo = localSelfRouteSeqNo,
    )
}

private fun buildMeshEngineRuntimeRoutingAndTrustPhase(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    support: MeshEngineRuntimeAssemblySupport,
    lateBindingContext: MeshEngineRuntimeLateBindingContext,
    sharedState: MeshEngineRuntimeSharedState,
): MeshEngineRuntimeRoutingAndTrustPhase {
    val routingSupport =
        MeshEngineRoutingSupport(
            routeCoordinator = sharedState.routeCoordinator,
            runtimeGate = environment.compatibilitySurface.runtimeGate,
            coroutineScope = environment.coroutineScope,
            emitDiagnostic = support.emitDiagnostic,
            sendEncryptedWireFrame = { peerId, frame, action, hardRunToken ->
                lateBindingContext.routingAdvertisementSender()(peerId, frame, action, hardRunToken)
            },
        )
    val trustSupport =
        MeshEngineTrustSupport(
            localIdentity = environment.localIdentity,
            trustStore = environment.trustStore,
            emitDiagnostic = support.emitDiagnostic,
        )
    val scheduleRetryDiagnostic =
        buildMeshEngineRuntimeScheduleRetryDiagnostic(
            deliveryPolicy = sharedState.runtimePolicies.delivery,
            support = support,
            routingSupport = routingSupport,
        )
    return MeshEngineRuntimeRoutingAndTrustPhase(
        routingSupport = routingSupport,
        trustSupport = trustSupport,
        scheduleRetryDiagnostic = scheduleRetryDiagnostic,
    )
}

internal fun buildMeshEngineRuntimeScheduleRetryDiagnostic(
    deliveryPolicy: MeshEngineRuntimeDeliveryPolicy,
    support: MeshEngineRuntimeAssemblySupport,
    routingSupport: MeshEngineRoutingSupport,
): suspend (PeerId, DeliveryPriority) -> Unit {
    return { peerId, priority ->
        support.emitDiagnostic(
            DiagnosticCode.NO_ROUTE_AVAILABLE,
            DiagnosticSeverity.WARN,
            "delivery.noRoute",
            peerId.diagnosticSuffix(),
            DiagnosticReason.DELIVERY_FAILURE,
            routingSupport.peerRouteMetadata(
                peerId,
                metadata = deliveryPolicy.retryDiagnosticMetadata(priority),
            ),
        )
    }
}
