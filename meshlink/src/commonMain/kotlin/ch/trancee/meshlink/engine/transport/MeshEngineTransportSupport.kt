package ch.trancee.meshlink.engine.transport

import ch.trancee.meshlink.api.PeerConnectionState
import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.engine.handshake.EndToEndSessionEstablishmentOutcome
import ch.trancee.meshlink.engine.handshake.MeshEngineEndToEndSessionRegistry
import ch.trancee.meshlink.engine.handshake.MeshEngineSessionRegistry
import ch.trancee.meshlink.engine.internal.DIAGNOSTIC_PEER_SUFFIX_LENGTH
import ch.trancee.meshlink.engine.internal.SessionEstablishmentOutcome
import ch.trancee.meshlink.engine.routing.MeshEngineRoutingSupport
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
    val endToEndSessionRegistry: MeshEngineEndToEndSessionRegistry,
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
            is TransportEvent.InboundPeerClaimed -> callbacks.prewarmHopSession(event.peerId)
            is TransportEvent.PeerLost ->
                handleDisconnectedPeer(
                    peerId = event.peerId,
                    stage = "transport.peerLost",
                    removalCode = DiagnosticCode.ROUTE_EXPIRED,
                    metadata = mapOf("removedByPeerId" to event.peerId.value),
                )
            is TransportEvent.TransportModeChanged -> handleTransportModeChanged(event)
            is TransportEvent.AdvertiseFailed -> handleAdvertiseFailed(event)
            is TransportEvent.ScanFailed -> handleScanFailed(event)
            is TransportEvent.ManualBluetoothRecoveryNeeded -> handleManualBluetoothRecoveryNeeded()
        }
    }

    private suspend fun handleDiscoveredPeer(event: TransportEvent.PeerDiscovered): Unit {
        if (
            event.transportMode != TransportMode.L2CAP && event.transportMode != TransportMode.GATT
        ) {
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
        val accepted =
            event.transportMode == TransportMode.L2CAP || event.transportMode == TransportMode.GATT
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
        peerState.endToEndSessionRegistry.clear().forEach { pendingHandshake ->
            pendingHandshake.sessionDeferred.complete(
                EndToEndSessionEstablishmentOutcome.Unreachable
            )
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
        // Deliberately does not clear peerState.endToEndSessionRegistry here: an end-to-end
        // session's destination peer may still be reachable through a different hop after this
        // one disconnects, so tearing it down eagerly on every direct-connection loss would
        // force needless handshake churn. End-to-end sessions are invalidated separately once
        // the destination becomes truly unreachable (no route at all) or via clearRuntimeView.
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

    private fun handleAdvertiseFailed(event: TransportEvent.AdvertiseFailed): Unit {
        emitDiagnostic(
            DiagnosticCode.DISCOVERY_ADVERTISE_FAILED,
            DiagnosticSeverity.WARN,
            "transport.discovery.advertiseFailed",
            null,
            DiagnosticReason.TRANSPORT_CHANGE,
            mapOf(
                "errorCode" to event.errorCode.toString(),
                "errorName" to event.errorName,
                "willRetry" to event.willRetry.toString(),
                "attempt" to event.attempt.toString(),
            ),
        )
    }

    private fun handleScanFailed(event: TransportEvent.ScanFailed): Unit {
        emitDiagnostic(
            DiagnosticCode.DISCOVERY_SCAN_FAILED,
            DiagnosticSeverity.WARN,
            "transport.discovery.scanFailed",
            null,
            DiagnosticReason.TRANSPORT_CHANGE,
            mapOf(
                "errorCode" to event.errorCode.toString(),
                "errorName" to event.errorName,
                "willRetry" to event.willRetry.toString(),
                "attempt" to event.attempt.toString(),
            ),
        )
    }

    private fun handleManualBluetoothRecoveryNeeded(): Unit {
        emitDiagnostic(
            DiagnosticCode.MANUAL_BLUETOOTH_RECOVERY_NEEDED,
            DiagnosticSeverity.ERROR,
            "transport.discovery.manualBluetoothRecoveryNeeded",
            null,
            DiagnosticReason.TRANSPORT_CHANGE,
            emptyMap(),
        )
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
                "supportedTransportMode" to
                    "${TransportMode.L2CAP.name},${TransportMode.GATT.name}",
                "transportMode" to transportMode.name,
            ),
        )
    }

    private suspend fun handleInboundFrame(event: TransportEvent.FrameReceived): Unit {
        val frame =
            runCatching { DirectWireFrame.decode(event.payload) }
                .getOrElse { exception ->
                    emitDiagnostic(
                        DiagnosticCode.TRANSPORT_FRAME_REJECTED,
                        DiagnosticSeverity.WARN,
                        "transport.frame.malformed",
                        event.peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                        DiagnosticReason.DELIVERY_FAILURE,
                        mapOf("cause" to exception::class.simpleName.orEmpty()),
                    )
                    return
                }
        when (frame) {
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
    endToEndSessionRegistry: MeshEngineEndToEndSessionRegistry,
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
                endToEndSessionRegistry = endToEndSessionRegistry,
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
