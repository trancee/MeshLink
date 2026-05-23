package ch.trancee.meshlink.engine

import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import kotlin.time.Duration.Companion.seconds

internal fun buildMeshEngineRuntimeSessionAssembly(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    support: MeshEngineRuntimeAssemblySupport,
    foundation: MeshEngineRuntimeFoundationAssembly,
    lateBindingContext: MeshEngineRuntimeLateBindingContext,
): MeshEngineRuntimeSessionAssembly {
    val sessionAndHopTransport =
        buildMeshEngineRuntimeSessionAndHopTransportPhase(
            environment = environment,
            support = support,
            foundation = foundation,
        )
    lateBindingContext.registerRoutingAdvertisementSender(
        sessionAndHopTransport.hopTransportSupport::sendEncryptedWireFrame
    )
    val handshake =
        buildMeshEngineRuntimeHandshakePhase(
            environment = environment,
            support = support,
            foundation = foundation,
            sessionAndHopTransport = sessionAndHopTransport,
        )
    return MeshEngineRuntimeSessionAssembly(
        sessionAndHopTransport = sessionAndHopTransport,
        handshake = handshake,
    )
}

private fun buildMeshEngineRuntimeSessionAndHopTransportPhase(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    support: MeshEngineRuntimeAssemblySupport,
    foundation: MeshEngineRuntimeFoundationAssembly,
): MeshEngineRuntimeSessionAndHopTransportPhase {
    val sharedState = foundation.sharedState
    val routingAndTrust = foundation.routingAndTrust
    val sessionSupport =
        buildMeshEngineRuntimeSessionSupport(
            environment = environment,
            support = support,
            sharedState = sharedState,
            routingAndTrust = routingAndTrust,
        )
    val hopTransportSupport =
        buildMeshEngineRuntimeHopTransportSupport(
            environment = environment,
            support = support,
            routingAndTrust = routingAndTrust,
            sessionSupport = sessionSupport,
        )
    val peerFlowSupport =
        buildMeshEngineRuntimePeerFlowSupport(
            environment = environment,
            support = support,
            sharedState = sharedState,
            routingAndTrust = routingAndTrust,
            sessionSupport = sessionSupport,
            hopTransportSupport = hopTransportSupport,
        )
    return MeshEngineRuntimeSessionAndHopTransportPhase(
        sessionSupport = sessionSupport,
        hopTransportSupport = hopTransportSupport,
        peerFlowSupport = peerFlowSupport,
    )
}

private fun buildMeshEngineRuntimeSessionSupport(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    support: MeshEngineRuntimeAssemblySupport,
    sharedState: MeshEngineRuntimeSharedState,
    routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
): MeshEngineSessionSupport {
    return MeshEngineSessionSupport(
        localIdentity = environment.localIdentity,
        state =
            MeshEngineSessionState(
                sessionRegistry = sharedState.sessionRegistry,
                runtimeGate = environment.compatibilitySurface.runtimeGate,
            ),
        handshakeTimeout = HANDSHAKE_TIMEOUT,
        callbacks =
            MeshEngineSessionCallbacks(
                hasTransport = { environment.platformBridge.hasTransport },
                sendDirectWireFrame = { peerId, frame, action, preferredMode ->
                    support.sendDirectWireFrame(peerId, frame, action, preferredMode)
                },
                emitHopSessionFailed = { peerId, stage, reason, metadata ->
                    support.emitDiagnostic(
                        DiagnosticCode.HOP_SESSION_FAILED,
                        DiagnosticSeverity.WARN,
                        stage,
                        peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                        reason,
                        routingAndTrust.routingSupport.peerRouteMetadata(
                            peerId,
                            metadata = metadata,
                        ),
                    )
                },
            ),
    )
}

private fun buildMeshEngineRuntimeHopTransportSupport(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    support: MeshEngineRuntimeAssemblySupport,
    routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
    sessionSupport: MeshEngineSessionSupport,
): MeshEngineHopTransportSupport {
    return MeshEngineHopTransportSupport(
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
}

private fun buildMeshEngineRuntimePeerFlowSupport(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    support: MeshEngineRuntimeAssemblySupport,
    sharedState: MeshEngineRuntimeSharedState,
    routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
    sessionSupport: MeshEngineSessionSupport,
    hopTransportSupport: MeshEngineHopTransportSupport,
): MeshEnginePeerFlowSupport {
    return MeshEnginePeerFlowSupport(
        localIdentity = environment.localIdentity,
        context =
            MeshEnginePeerFlowContext(
                routeCoordinator = sharedState.routeCoordinator,
                coroutineScope = environment.coroutineScope,
            ),
        config =
            MeshEnginePeerFlowConfig(
                largeInlineTransportBudgetBytes = LARGE_INLINE_SEND_TRANSPORT_BUDGET_BYTES
            ),
        callbacks =
            MeshEnginePeerFlowCallbacks(
                runtimeGate = environment.compatibilitySurface.runtimeGate,
                captureHardRunToken =
                    environment.compatibilitySurface.runtimeGate::captureHardRunToken,
                sendEncryptedWireFrame = hopTransportSupport::sendEncryptedWireFrame,
                ensureHopSession = { peerId -> sessionSupport.ensureHopSession(peerId) },
                maximumPayloadBytesPerDelivery =
                    environment.platformBridge::maximumPayloadBytesPerDelivery,
                emitDiagnostic = support.emitDiagnostic,
                peerRouteMetadata = { peerId, metadata ->
                    routingAndTrust.routingSupport.peerRouteMetadata(peerId, metadata = metadata)
                },
            ),
    )
}

private fun buildMeshEngineRuntimeHandshakePhase(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    support: MeshEngineRuntimeAssemblySupport,
    foundation: MeshEngineRuntimeFoundationAssembly,
    sessionAndHopTransport: MeshEngineRuntimeSessionAndHopTransportPhase,
): MeshEngineRuntimeHandshakePhase {
    val sharedState = foundation.sharedState
    val routingAndTrust = foundation.routingAndTrust
    val handshakeState = MeshEngineHandshakeState(sessionRegistry = sharedState.sessionRegistry)
    val handshakeRoutingContext =
        MeshEngineHandshakeRoutingContext(
            routeCoordinator = sharedState.routeCoordinator,
            routingSupport = routingAndTrust.routingSupport,
        )
    val handshakeCallbacks =
        buildMeshEngineRuntimeHandshakeCallbacks(
            environment = environment,
            support = support,
            sessionAndHopTransport = sessionAndHopTransport,
        )
    val initiatorHandshakeSupport =
        MeshEngineInitiatorHandshakeSupport(
            localIdentity = environment.localIdentity,
            trustSupport = routingAndTrust.trustSupport,
            state = handshakeState,
            routingContext = handshakeRoutingContext,
            callbacks = handshakeCallbacks,
        )
    val responderHandshakeSupport =
        MeshEngineResponderHandshakeSupport(
            localIdentity = environment.localIdentity,
            trustSupport = routingAndTrust.trustSupport,
            state = handshakeState,
            routingContext = handshakeRoutingContext,
            callbacks = handshakeCallbacks,
        )
    return MeshEngineRuntimeHandshakePhase(
        initiatorHandshakeSupport = initiatorHandshakeSupport,
        responderHandshakeSupport = responderHandshakeSupport,
    )
}

private fun buildMeshEngineRuntimeHandshakeCallbacks(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    support: MeshEngineRuntimeAssemblySupport,
    sessionAndHopTransport: MeshEngineRuntimeSessionAndHopTransportPhase,
): MeshEngineHandshakeCallbacks {
    return MeshEngineHandshakeCallbacks(
        sendDirectWireFrame = { peerId, frame, action ->
            support.sendDirectWireFrame(peerId, frame, action, null)
        },
        emitHopSessionEstablished =
            sessionAndHopTransport.hopTransportSupport::emitHopSessionEstablished,
        emitHopSessionFailed = sessionAndHopTransport.hopTransportSupport::emitHopSessionFailed,
        promoteTemporaryPeer = { temporaryPeerId, canonicalPeerId ->
            runCatching {
                    environment.platformBridge.promoteTemporaryPeer(
                        temporaryPeerId,
                        canonicalPeerId,
                    )
                }
                .getOrElse { Unit }
        },
    )
}

private val HANDSHAKE_TIMEOUT = 3.seconds
private const val LARGE_INLINE_SEND_TRANSPORT_BUDGET_BYTES: Int = 16 * 1024
