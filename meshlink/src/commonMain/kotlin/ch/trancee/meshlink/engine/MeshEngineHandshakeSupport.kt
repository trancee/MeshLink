package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult

internal data class MeshEngineHandshakeState(val sessionRegistry: MeshEngineSessionRegistry)

internal data class MeshEngineHandshakeRoutingContext(
    val routeCoordinator: RouteCoordinator,
    val routingSupport: MeshEngineRoutingSupport,
)

internal data class MeshEngineHandshakeCallbacks(
    val sendDirectWireFrame:
        suspend (PeerId, DirectWireFrame, String, TransportMode?) -> TransportSendResult,
    val emitHopSessionEstablished: (PeerId, String) -> Unit,
    val emitHopSessionFailed: (PeerId, String, DiagnosticReason, Map<String, String>) -> Unit,
    val promoteTemporaryPeer: suspend (PeerId, PeerId) -> Unit,
)

internal data class PendingInitiatorHandshakeFailure(
    val outcome: SessionEstablishmentOutcome,
    val stage: String,
    val reason: DiagnosticReason,
    val metadata: Map<String, String> = emptyMap(),
)

internal fun buildMeshEngineRuntimeHandshakeCallbacks(
    sendDirectWireFrame:
        suspend (PeerId, DirectWireFrame, String, TransportMode?) -> TransportSendResult,
    emitHopSessionEstablished: (PeerId, String) -> Unit,
    emitHopSessionFailed: (PeerId, String, DiagnosticReason, Map<String, String>) -> Unit,
    promoteTemporaryPeer: suspend (PeerId, PeerId) -> Unit,
): MeshEngineHandshakeCallbacks {
    return MeshEngineHandshakeCallbacks(
        sendDirectWireFrame = sendDirectWireFrame,
        emitHopSessionEstablished = emitHopSessionEstablished,
        emitHopSessionFailed = emitHopSessionFailed,
        promoteTemporaryPeer = promoteTemporaryPeer,
    )
}
