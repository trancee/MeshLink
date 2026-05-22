package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.transport.TransportSendResult

internal data class MeshEngineHandshakeState(val sessionRegistry: MeshEngineSessionRegistry)

internal data class MeshEngineHandshakeRoutingContext(
    val routeCoordinator: RouteCoordinator,
    val routingSupport: MeshEngineRoutingSupport,
)

internal data class MeshEngineHandshakeCallbacks(
    val sendDirectWireFrame: suspend (PeerId, DirectWireFrame, String) -> TransportSendResult,
    val emitHopSessionEstablished: (PeerId, String) -> Unit,
    val emitHopSessionFailed: (PeerId, String, DiagnosticReason, Map<String, String>) -> Unit,
)

internal data class PendingInitiatorHandshakeFailure(
    val outcome: SessionEstablishmentOutcome,
    val stage: String,
    val reason: DiagnosticReason,
    val metadata: Map<String, String> = emptyMap(),
)
