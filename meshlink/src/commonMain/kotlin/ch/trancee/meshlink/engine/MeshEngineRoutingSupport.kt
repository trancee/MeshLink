package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.routing.RouteEntry
import ch.trancee.meshlink.routing.RouteSelectionChange
import ch.trancee.meshlink.routing.RoutingAdvertisement
import ch.trancee.meshlink.routing.RoutingMutation
import ch.trancee.meshlink.wire.WireFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class MeshEngineRoutingSupport(
    private val routeCoordinator: RouteCoordinator,
    private val runtimeGate: MeshEngineRuntimeGate,
    private val coroutineScope: CoroutineScope,
    private val emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit,
    private val sendEncryptedWireFrame:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
) {
    fun dispatchMutation(
        mutation: RoutingMutation,
        stage: String,
        removalCode: DiagnosticCode = DiagnosticCode.ROUTE_RETRACTED,
        metadata: Map<String, String> = emptyMap(),
    ): Unit {
        emitRouteSelectionDiagnostics(
            changes = mutation.routeChanges,
            stage = stage,
            removalCode = removalCode,
            metadata = metadata,
        )
        dispatchRoutingAdvertisements(mutation.advertisements)
    }

    fun peerRouteMetadata(
        peerId: PeerId,
        route: RouteEntry? = routeCoordinator.routeFor(peerId),
        previousRoute: RouteEntry? = null,
        metadata: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        return buildMap {
            put("peerId", peerId.value)
            put("topologyVersion", routeCoordinator.topologyVersion.value.toString())
            put("routeAvailable", (route != null).toString())
            route?.let { selectedRoute ->
                put("destinationPeerId", selectedRoute.destinationPeerId.value)
                put("nextHopPeerId", selectedRoute.nextHopPeerId.value)
                put("routeMetric", selectedRoute.metric.toString())
                put("routeSeqNo", selectedRoute.seqNo.toString())
                put("routeIsDirect", selectedRoute.isDirect.toString())
            }
            previousRoute?.let { priorRoute ->
                put("previousDestinationPeerId", priorRoute.destinationPeerId.value)
                put("previousNextHopPeerId", priorRoute.nextHopPeerId.value)
                put("previousRouteMetric", priorRoute.metric.toString())
                put("previousRouteSeqNo", priorRoute.seqNo.toString())
                put("previousRouteIsDirect", priorRoute.isDirect.toString())
            }
            metadata.forEach { (key, value) -> put(key, value) }
        }
    }

    private fun emitRouteSelectionDiagnostics(
        changes: List<RouteSelectionChange>,
        stage: String,
        removalCode: DiagnosticCode,
        metadata: Map<String, String>,
    ): Unit {
        changes.forEach { change ->
            emitRouteSelectionDiagnostic(
                change = change,
                stage = stage,
                removalCode = removalCode,
                metadata = metadata,
            )
        }
    }

    private fun emitRouteSelectionDiagnostic(
        change: RouteSelectionChange,
        stage: String,
        removalCode: DiagnosticCode,
        metadata: Map<String, String>,
    ): Unit {
        when (change) {
            is RouteSelectionChange.Available ->
                emitAvailableRouteDiagnostic(change = change, stage = stage, metadata = metadata)
            is RouteSelectionChange.Updated ->
                emitUpdatedRouteDiagnostic(change = change, stage = stage, metadata = metadata)
            is RouteSelectionChange.Removed ->
                emitRemovedRouteDiagnostic(
                    change = change,
                    stage = stage,
                    removalCode = removalCode,
                    metadata = metadata,
                )
        }
    }

    private fun emitAvailableRouteDiagnostic(
        change: RouteSelectionChange.Available,
        stage: String,
        metadata: Map<String, String>,
    ): Unit {
        emitDiagnostic(
            DiagnosticCode.ROUTE_DISCOVERED,
            DiagnosticSeverity.INFO,
            "$stage.routeAvailable",
            change.route.destinationPeerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
            DiagnosticReason.ROUTE_CHANGE,
            peerRouteMetadata(
                peerId = change.route.destinationPeerId,
                route = change.route,
                metadata = metadata + ("routeChange" to "available"),
            ),
        )
    }

    private fun emitUpdatedRouteDiagnostic(
        change: RouteSelectionChange.Updated,
        stage: String,
        metadata: Map<String, String>,
    ): Unit {
        emitDiagnostic(
            DiagnosticCode.ROUTE_UPDATED,
            DiagnosticSeverity.DEBUG,
            "$stage.routeUpdated",
            change.route.destinationPeerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
            DiagnosticReason.ROUTE_CHANGE,
            peerRouteMetadata(
                peerId = change.route.destinationPeerId,
                route = change.route,
                previousRoute = change.previousRoute,
                metadata = metadata + ("routeChange" to "updated"),
            ),
        )
    }

    private fun emitRemovedRouteDiagnostic(
        change: RouteSelectionChange.Removed,
        stage: String,
        removalCode: DiagnosticCode,
        metadata: Map<String, String>,
    ): Unit {
        emitDiagnostic(
            removalCode,
            DiagnosticSeverity.WARN,
            routeRemovalStage(stage = stage, removalCode = removalCode),
            change.previousRoute.destinationPeerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
            DiagnosticReason.ROUTE_CHANGE,
            peerRouteMetadata(
                peerId = change.previousRoute.destinationPeerId,
                previousRoute = change.previousRoute,
                metadata = metadata + ("routeChange" to routeRemovalLabel(removalCode)),
            ),
        )
    }

    private fun dispatchRoutingAdvertisements(advertisements: List<RoutingAdvertisement>): Unit {
        val hardRunToken = runtimeGate.captureHardRunToken()
        advertisements.forEach { advertisement ->
            coroutineScope.launch {
                if (!runtimeGate.isHardRunActive(hardRunToken)) {
                    return@launch
                }
                sendEncryptedWireFrame(
                    advertisement.targetPeerId,
                    advertisement.frame,
                    "routing.advertise",
                    null,
                )
            }
        }
    }
}
