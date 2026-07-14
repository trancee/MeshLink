package ch.trancee.meshlink.engine.assembly

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.engine.handshake.EndToEndSessionEstablishmentOutcome
import ch.trancee.meshlink.engine.handshake.MeshEngineEndToEndHandshakeCallbacks
import ch.trancee.meshlink.engine.handshake.MeshEngineHandshakeRoutingContext
import ch.trancee.meshlink.engine.handshake.MeshEngineHandshakeState
import ch.trancee.meshlink.engine.handshake.buildMeshEngineRuntimeEndToEndHandshakeSupport
import ch.trancee.meshlink.engine.handshake.buildMeshEngineRuntimeHandshakeCallbacks
import ch.trancee.meshlink.engine.handshake.buildMeshEngineRuntimeInitiatorHandshakeSupport
import ch.trancee.meshlink.engine.handshake.buildMeshEngineRuntimeResponderHandshakeSupport
import ch.trancee.meshlink.engine.handshake.buildMeshEngineRuntimeSessionSupport
import ch.trancee.meshlink.engine.internal.HopSession
import ch.trancee.meshlink.engine.internal.MeshEngineSendEncryptedWireFrame
import ch.trancee.meshlink.engine.internal.SessionEstablishmentOutcome
import ch.trancee.meshlink.engine.lifecycle.buildMeshEngineRuntimePeerFlowSupport
import ch.trancee.meshlink.engine.transport.buildMeshEngineRuntimeHopTransportSupport
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.wire.WireFrame
import kotlinx.coroutines.CancellationException

internal data class MeshEngineRuntimeSessionAssembly(
    val ensureHopSession: suspend (PeerId, MeshEngineHardRunToken?) -> SessionEstablishmentOutcome,
    val sendEncryptedWireFrame: MeshEngineSendEncryptedWireFrame,
    val sendEncryptedDirectWireFrame:
        suspend (PeerId, HopSession, WireFrame, String) -> TransportSendResult,
    val decryptHopPayload: suspend (HopSession, ByteArray) -> ByteArray,
    val emitHopSessionFailed:
        suspend (PeerId, String, DiagnosticReason, Map<String, String>) -> Unit,
    val prewarmHopSession: (PeerId) -> Unit,
    val forwardMessageToNextHop: suspend (WireFrame.Message, MeshEngineHardRunToken) -> Unit,
    val forwardEndToEndHandshakeFrame:
        suspend (WireFrame.EndToEndHandshakeFrame, MeshEngineHardRunToken) -> Unit,
    val shouldAttemptLargeInlineSend: suspend (PeerId) -> Boolean,
    val isLocalPeerId: (PeerId) -> Boolean,
    val handleHandshakeMessage1: suspend (PeerId, ByteArray) -> Unit,
    val handleHandshakeMessage2: suspend (PeerId, ByteArray) -> Unit,
    val handleHandshakeMessage3: suspend (PeerId, ByteArray) -> Unit,
    val ensureEndToEndSession: suspend (PeerId) -> EndToEndSessionEstablishmentOutcome,
    val handleLocalEndToEndHandshakeFrame: suspend (WireFrame.EndToEndHandshakeFrame) -> Unit,
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
                        // promoteTemporaryPeer is a suspend call; a cancellation while it is in
                        // flight (e.g. runtime shutdown racing an in-progress handshake
                        // completion) must propagate immediately to unwind this coroutine
                        // normally, not be reported as an ordinary promoteTemporaryPeer failure
                        // below.
                        if (exception is CancellationException) {
                            throw exception
                        }
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
    val endToEndHandshakeSupport =
        buildMeshEngineRuntimeEndToEndHandshakeSupport(
            localIdentity = environment.localIdentity,
            trustSupport = routingAndTrust.trustSupport,
            registry = sharedState.endToEndSessionRegistry,
            callbacks =
                MeshEngineEndToEndHandshakeCallbacks(
                    sendFrameTowardsPeer = { peerId, frame, action ->
                        val route = sharedState.routeCoordinator.routeFor(peerId)
                        val nextHopPeerId = route?.nextHopPeerId ?: peerId
                        hopTransportSupport.sendEncryptedWireFrame(
                            nextHopPeerId,
                            frame,
                            action,
                            null,
                        )
                    },
                    createHandshakeId = sharedState.sequenceGenerator::createHandshakeId,
                    emitDiagnostic = support.emitDiagnostic,
                ),
        )
    return MeshEngineRuntimeSessionAssembly(
        ensureHopSession = sessionSupport::ensureHopSession,
        sendEncryptedWireFrame = hopTransportSupport::sendEncryptedWireFrame,
        sendEncryptedDirectWireFrame = hopTransportSupport::sendEncryptedDirectWireFrame,
        decryptHopPayload = hopTransportSupport::decryptHopPayload,
        emitHopSessionFailed = hopTransportSupport::emitHopSessionFailed,
        prewarmHopSession = peerFlowSupport::prewarmHopSession,
        forwardMessageToNextHop = peerFlowSupport::forwardMessageToNextHop,
        forwardEndToEndHandshakeFrame = peerFlowSupport::forwardEndToEndHandshakeFrame,
        shouldAttemptLargeInlineSend = peerFlowSupport::shouldAttemptLargeInlineSend,
        isLocalPeerId = peerFlowSupport::isLocalPeerId,
        handleHandshakeMessage1 = responderHandshakeSupport::handleHandshakeMessage1,
        handleHandshakeMessage2 = initiatorHandshakeSupport::handleHandshakeMessage2,
        handleHandshakeMessage3 = responderHandshakeSupport::handleHandshakeMessage3,
        ensureEndToEndSession = endToEndHandshakeSupport::ensureEndToEndSession,
        handleLocalEndToEndHandshakeFrame =
            endToEndHandshakeSupport::handleLocalEndToEndHandshakeFrame,
    )
}
