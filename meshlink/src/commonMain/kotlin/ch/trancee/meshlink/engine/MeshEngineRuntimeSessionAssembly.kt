package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.wire.WireFrame

internal data class MeshEngineRuntimeSessionAssembly(
    val ensureHopSession: suspend (PeerId, MeshEngineHardRunToken?) -> SessionEstablishmentOutcome,
    val sendEncryptedWireFrame:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
    val sendEncryptedDirectWireFrame:
        suspend (PeerId, HopSession, WireFrame, String) -> TransportSendResult,
    val decryptHopPayload: (HopSession, ByteArray) -> ByteArray,
    val emitHopSessionFailed:
        suspend (PeerId, String, DiagnosticReason, Map<String, String>) -> Unit,
    val prewarmHopSession: (PeerId) -> Unit,
    val forwardMessageToNextHop: suspend (WireFrame.Message, MeshEngineHardRunToken) -> Unit,
    val shouldAttemptLargeInlineSend: suspend (PeerId) -> Boolean,
    val isLocalPeerId: (PeerId) -> Boolean,
    val handleHandshakeMessage1: suspend (PeerId, ByteArray) -> Unit,
    val handleHandshakeMessage2: suspend (PeerId, ByteArray) -> Unit,
    val handleHandshakeMessage3: suspend (PeerId, ByteArray) -> Unit,
)

internal fun buildMeshEngineRuntimeSessionAssembly(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    support: MeshEngineRuntimeAssemblySupport,
    foundation: MeshEngineRuntimeFoundationAssembly,
    lateBindingContext: MeshEngineRuntimeLateBindingContext,
): MeshEngineRuntimeSessionAssembly {
    val sharedState = foundation.sharedState
    val routingAndTrust = foundation.routingAndTrust
    val sessionSupport =
        buildMeshEngineRuntimeSessionSupport(
            localIdentity = environment.localIdentity,
            sessionRegistry = sharedState.sessionRegistry,
            runtimeGate = environment.compatibilitySurface.runtimeGate,
            hasTransport = { environment.platformBridge.hasTransport },
            sendDirectWireFrame = { peerId, frame, action, preferredMode ->
                support.sendDirectWireFrame(peerId, frame, action, preferredMode)
            },
            emitDiagnostic = support.emitDiagnostic,
            peerRouteMetadata = { peerId, metadata ->
                routingAndTrust.routingSupport.peerRouteMetadata(peerId, metadata = metadata)
            },
        )
    val hopTransportSupport =
        buildMeshEngineRuntimeHopTransportSupport(
            localIdentity = environment.localIdentity,
            runtimeGate = environment.compatibilitySurface.runtimeGate,
            routingSupport = routingAndTrust.routingSupport,
            establishedHopSession = { peerId -> sessionSupport.establishedHopSession(peerId) },
            ensureHopSession = { peerId, hardRunToken ->
                sessionSupport.ensureHopSession(peerId, hardRunToken)
            },
            sendDirectWireFrame = { peerId, frame, action, preferredMode ->
                support.sendDirectWireFrame(peerId, frame, action, preferredMode)
            },
            emitDiagnostic = support.emitDiagnostic,
        )
    val peerFlowSupport =
        buildMeshEngineRuntimePeerFlowSupport(
            localIdentity = environment.localIdentity,
            routeCoordinator = sharedState.routeCoordinator,
            coroutineScope = environment.coroutineScope,
            runtimeGate = environment.compatibilitySurface.runtimeGate,
            captureHardRunToken = environment.compatibilitySurface.runtimeGate::captureHardRunToken,
            sendEncryptedWireFrame = hopTransportSupport::sendEncryptedWireFrame,
            ensureHopSession = { peerId -> sessionSupport.ensureHopSession(peerId) },
            maximumPayloadBytesPerDelivery =
                environment.platformBridge::maximumPayloadBytesPerDelivery,
            emitDiagnostic = support.emitDiagnostic,
            peerRouteMetadata = { peerId, metadata ->
                routingAndTrust.routingSupport.peerRouteMetadata(peerId, metadata = metadata)
            },
        )
    lateBindingContext.registerRoutingAdvertisementSender(
        hopTransportSupport::sendEncryptedWireFrame
    )
    val handshakeState = MeshEngineHandshakeState(sessionRegistry = sharedState.sessionRegistry)
    val handshakeRoutingContext =
        MeshEngineHandshakeRoutingContext(
            routeCoordinator = sharedState.routeCoordinator,
            routingSupport = routingAndTrust.routingSupport,
        )
    val handshakeCallbacks =
        buildMeshEngineRuntimeHandshakeCallbacks(
            sendDirectWireFrame = { peerId, frame, action, preferredMode ->
                support.sendDirectWireFrame(peerId, frame, action, preferredMode)
            },
            emitHopSessionEstablished = hopTransportSupport::emitHopSessionEstablished,
            emitHopSessionFailed = hopTransportSupport::emitHopSessionFailed,
            promoteTemporaryPeer = { temporaryPeerId, canonicalPeerId ->
                runCatching {
                        environment.platformBridge.promoteTemporaryPeer(
                            temporaryPeerId,
                            canonicalPeerId,
                        )
                    }
                    .getOrElse { exception ->
                        hopTransportSupport.emitHopSessionFailed(
                            temporaryPeerId,
                            "transport.handshake.promoteTemporaryPeer",
                            DiagnosticReason.DELIVERY_FAILURE,
                            mapOf("cause" to exception::class.simpleName.orEmpty()),
                        )
                    }
            },
        )
    val initiatorHandshakeSupport =
        buildMeshEngineRuntimeInitiatorHandshakeSupport(
            localIdentity = environment.localIdentity,
            trustSupport = routingAndTrust.trustSupport,
            state = handshakeState,
            routingContext = handshakeRoutingContext,
            callbacks = handshakeCallbacks,
        )
    val responderHandshakeSupport =
        buildMeshEngineRuntimeResponderHandshakeSupport(
            localIdentity = environment.localIdentity,
            trustSupport = routingAndTrust.trustSupport,
            state = handshakeState,
            routingContext = handshakeRoutingContext,
            callbacks = handshakeCallbacks,
        )
    return MeshEngineRuntimeSessionAssembly(
        ensureHopSession = sessionSupport::ensureHopSession,
        sendEncryptedWireFrame = hopTransportSupport::sendEncryptedWireFrame,
        sendEncryptedDirectWireFrame = hopTransportSupport::sendEncryptedDirectWireFrame,
        decryptHopPayload = hopTransportSupport::decryptHopPayload,
        emitHopSessionFailed = hopTransportSupport::emitHopSessionFailed,
        prewarmHopSession = peerFlowSupport::prewarmHopSession,
        forwardMessageToNextHop = peerFlowSupport::forwardMessageToNextHop,
        shouldAttemptLargeInlineSend = peerFlowSupport::shouldAttemptLargeInlineSend,
        isLocalPeerId = peerFlowSupport::isLocalPeerId,
        handleHandshakeMessage1 = responderHandshakeSupport::handleHandshakeMessage1,
        handleHandshakeMessage2 = initiatorHandshakeSupport::handleHandshakeMessage2,
        handleHandshakeMessage3 = responderHandshakeSupport::handleHandshakeMessage3,
    )
}
