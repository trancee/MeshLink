package ch.trancee.meshlink.engine

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
    return MeshEngineRuntimeSessionAndHopTransportPhase(
        sessionSupport = sessionSupport,
        hopTransportSupport = hopTransportSupport,
        peerFlowSupport = peerFlowSupport,
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
    return MeshEngineRuntimeHandshakePhase(
        initiatorHandshakeSupport = initiatorHandshakeSupport,
        responderHandshakeSupport = responderHandshakeSupport,
    )
}
