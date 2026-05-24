package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerConnectionState
import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.presence.PeerPresenceTracker
import ch.trancee.meshlink.presence.PresenceTransition
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportMode
import kotlinx.coroutines.flow.MutableSharedFlow

internal data class MeshEngineTransportPeerState(
    val presenceTracker: PeerPresenceTracker,
    val mutablePeerEvents: MutableSharedFlow<PeerEvent>,
    val sessionRegistry: MeshEngineSessionRegistry,
)

internal data class MeshEngineTransportRoutingContext(
    val routeCoordinator: RouteCoordinator,
    val routingSupport: MeshEngineRoutingSupport,
)

internal data class MeshEngineTransportCallbacks(
    val prewarmHopSession: (PeerId) -> Unit,
    val handleHandshakeMessage1: suspend (PeerId, ByteArray) -> Unit,
    val handleHandshakeMessage2: suspend (PeerId, ByteArray) -> Unit,
    val handleHandshakeMessage3: suspend (PeerId, ByteArray) -> Unit,
    val handleEncryptedDataFrame: suspend (PeerId, ByteArray) -> Unit,
)

internal class MeshEngineTransportSupport(
    private val peerState: MeshEngineTransportPeerState,
    private val routingContext: MeshEngineTransportRoutingContext,
    private val callbacks: MeshEngineTransportCallbacks,
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
    suspend fun handleTransportEvent(event: TransportEvent): Unit {
        when (event) {
            is TransportEvent.FrameReceived -> handleInboundFrame(event)
            is TransportEvent.PeerDiscovered -> handleDiscoveredPeer(event)
            is TransportEvent.PeerLost ->
                handleDisconnectedPeer(
                    peerId = event.peerId,
                    stage = "transport.peerLost",
                    removalCode = DiagnosticCode.ROUTE_EXPIRED,
                    metadata = mapOf("removedByPeerId" to event.peerId.value),
                )
            is TransportEvent.TransportModeChanged -> handleTransportModeChanged(event)
        }
    }

    private suspend fun handleDiscoveredPeer(event: TransportEvent.PeerDiscovered): Unit {
        if (event.transportMode != TransportMode.L2CAP) {
            emitTransportModeDiagnostic(
                peerId = event.peerId,
                transportMode = event.transportMode,
                stage = "transport.peerDiscovered.rejected",
                accepted = false,
            )
            return
        }
        emitConnectedPeerEvent(
            peerId = event.peerId,
            transition = peerState.presenceTracker.onPeerConnected(event.peerId),
        )
        callbacks.prewarmHopSession(event.peerId)
    }

    private suspend fun handleTransportModeChanged(
        event: TransportEvent.TransportModeChanged
    ): Unit {
        val accepted = event.transportMode == TransportMode.L2CAP
        emitTransportModeDiagnostic(
            peerId = event.peerId,
            transportMode = event.transportMode,
            stage = "transport.modeChanged",
            accepted = accepted,
        )
        if (!accepted) {
            handleDisconnectedPeer(
                peerId = event.peerId,
                stage = "transport.modeChanged.rejected",
                removalCode = DiagnosticCode.ROUTE_RETRACTED,
                metadata =
                    mapOf(
                        "removedByPeerId" to event.peerId.value,
                        "transportMode" to event.transportMode.name,
                    ),
            )
        }
    }

    private suspend fun handleDisconnectedPeer(
        peerId: PeerId,
        stage: String,
        removalCode: DiagnosticCode,
        metadata: Map<String, String>,
    ): Unit {
        clearPeerTransportState(peerId)
        routingContext.routingSupport.dispatchMutation(
            mutation = routingContext.routeCoordinator.onPeerDisconnected(peerId),
            stage = stage,
            removalCode = removalCode,
            metadata = metadata,
        )
        if (peerState.presenceTracker.onPeerDisconnected(peerId)) {
            peerState.mutablePeerEvents.emit(PeerEvent.Lost(peerId))
        }
    }

    suspend fun clearRuntimeView(
        stage: String,
        removalCode: DiagnosticCode,
        metadata: Map<String, String> = emptyMap(),
    ): Unit {
        peerState.sessionRegistry.clear().forEach { pendingHandshake ->
            pendingHandshake.sessionDeferred.complete(SessionEstablishmentOutcome.Unreachable)
        }
        val clearedPeers = peerState.presenceTracker.clear()
        routingContext.routingSupport.dispatchMutation(
            mutation = routingContext.routeCoordinator.clearConnectedPeers(),
            stage = stage,
            removalCode = removalCode,
            metadata = metadata,
        )
        clearedPeers.forEach { peerId -> peerState.mutablePeerEvents.emit(PeerEvent.Lost(peerId)) }
    }

    private suspend fun clearPeerTransportState(peerId: PeerId): Unit {
        peerState.sessionRegistry
            .clearPeer(peerId)
            ?.sessionDeferred
            ?.complete(SessionEstablishmentOutcome.Unreachable)
    }

    private suspend fun emitConnectedPeerEvent(
        peerId: PeerId,
        transition: PresenceTransition,
    ): Unit {
        when (transition) {
            PresenceTransition.FOUND -> {
                peerState.mutablePeerEvents.emit(
                    PeerEvent.Found(peerId, PeerConnectionState.CONNECTED)
                )
            }
            PresenceTransition.STATE_CHANGED -> {
                peerState.mutablePeerEvents.emit(
                    PeerEvent.StateChanged(peerId, PeerConnectionState.CONNECTED)
                )
            }
        }
    }

    private fun emitTransportModeDiagnostic(
        peerId: PeerId,
        transportMode: TransportMode,
        stage: String,
        accepted: Boolean,
    ): Unit {
        emitDiagnostic(
            DiagnosticCode.TRANSPORT_MODE_CHANGED,
            DiagnosticSeverity.INFO,
            stage,
            peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
            DiagnosticReason.TRANSPORT_CHANGE,
            mapOf(
                "accepted" to accepted.toString(),
                "supportedTransportMode" to TransportMode.L2CAP.name,
                "transportMode" to transportMode.name,
            ),
        )
    }

    private suspend fun handleInboundFrame(event: TransportEvent.FrameReceived): Unit {
        when (val frame = DirectWireFrame.decode(event.payload)) {
            is DirectWireFrame.HandshakeMessage1 ->
                callbacks.handleHandshakeMessage1(event.peerId, frame.payload)
            is DirectWireFrame.HandshakeMessage2 ->
                callbacks.handleHandshakeMessage2(event.peerId, frame.payload)
            is DirectWireFrame.HandshakeMessage3 ->
                callbacks.handleHandshakeMessage3(event.peerId, frame.payload)
            is DirectWireFrame.Data ->
                callbacks.handleEncryptedDataFrame(event.peerId, frame.payload)
        }
    }
}

internal fun buildMeshEngineRuntimeTransportSupport(
    presenceTracker: PeerPresenceTracker,
    mutablePeerEvents: MutableSharedFlow<PeerEvent>,
    sessionRegistry: MeshEngineSessionRegistry,
    routeCoordinator: RouteCoordinator,
    routingSupport: MeshEngineRoutingSupport,
    prewarmHopSession: (PeerId) -> Unit,
    handleHandshakeMessage1: suspend (PeerId, ByteArray) -> Unit,
    handleHandshakeMessage2: suspend (PeerId, ByteArray) -> Unit,
    handleHandshakeMessage3: suspend (PeerId, ByteArray) -> Unit,
    handleEncryptedDataFrame: suspend (PeerId, ByteArray) -> Unit,
    emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit,
): MeshEngineTransportSupport {
    return MeshEngineTransportSupport(
        peerState =
            MeshEngineTransportPeerState(
                presenceTracker = presenceTracker,
                mutablePeerEvents = mutablePeerEvents,
                sessionRegistry = sessionRegistry,
            ),
        routingContext =
            MeshEngineTransportRoutingContext(
                routeCoordinator = routeCoordinator,
                routingSupport = routingSupport,
            ),
        callbacks =
            MeshEngineTransportCallbacks(
                prewarmHopSession = prewarmHopSession,
                handleHandshakeMessage1 = handleHandshakeMessage1,
                handleHandshakeMessage2 = handleHandshakeMessage2,
                handleHandshakeMessage3 = handleHandshakeMessage3,
                handleEncryptedDataFrame = handleEncryptedDataFrame,
            ),
        emitDiagnostic = emitDiagnostic,
    )
}
