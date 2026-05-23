package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.power.PowerPolicyController
import ch.trancee.meshlink.presence.PeerPresenceTracker
import ch.trancee.meshlink.routing.RouteCoordinator
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

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
        outboundTransfers = linkedMapOf(),
        inboundTransfers = linkedMapOf(),
        relayTransfers = linkedMapOf(),
        sequenceGenerator = MeshEngineSequenceGenerator(environment.localIdentity),
        powerPolicyNowMillis = { engineClock.elapsedNow().inWholeMilliseconds },
        ttlMillisFor = { priority ->
            when (priority) {
                DeliveryPriority.HIGH -> HIGH_PRIORITY_TTL_MILLIS
                DeliveryPriority.NORMAL -> NORMAL_PRIORITY_TTL_MILLIS
                DeliveryPriority.LOW -> LOW_PRIORITY_TTL_MILLIS
            }
        },
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
            environment = environment,
            support = support,
            routingSupport = routingSupport,
        )
    return MeshEngineRuntimeRoutingAndTrustPhase(
        routingSupport = routingSupport,
        trustSupport = trustSupport,
        scheduleRetryDiagnostic = scheduleRetryDiagnostic,
    )
}

private fun buildMeshEngineRuntimeScheduleRetryDiagnostic(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    support: MeshEngineRuntimeAssemblySupport,
    routingSupport: MeshEngineRoutingSupport,
): (PeerId, DeliveryPriority) -> Unit {
    return { peerId, priority ->
        support.emitDiagnostic(
            DiagnosticCode.NO_ROUTE_AVAILABLE,
            DiagnosticSeverity.WARN,
            "delivery.noRoute",
            peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
            DiagnosticReason.DELIVERY_FAILURE,
            routingSupport.peerRouteMetadata(
                peerId,
                metadata =
                    mapOf(
                        "priority" to priority.name,
                        "retryDeadlineMs" to
                            environment.config.deliveryRetryDeadline.inWholeMilliseconds.toString(),
                        "retryBackoffBaseMs" to INITIAL_BACKOFF.inWholeMilliseconds.toString(),
                    ),
            ),
        )
    }
}

private const val HIGH_PRIORITY_TTL_MILLIS: Int = 45 * 60 * 1_000
private const val NORMAL_PRIORITY_TTL_MILLIS: Int = 15 * 60 * 1_000
private const val LOW_PRIORITY_TTL_MILLIS: Int = 5 * 60 * 1_000
private val INITIAL_BACKOFF = 250.milliseconds
