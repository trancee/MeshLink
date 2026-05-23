package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import kotlinx.coroutines.launch

internal fun buildMeshEngineRuntimeFacadeOperationsPhase(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    support: MeshEngineRuntimeAssemblySupport,
    foundation: MeshEngineRuntimeFoundationAssembly,
    session: MeshEngineRuntimeSessionAssembly,
    transferAndInbound: MeshEngineRuntimeTransferAndInboundPhase,
): MeshEngineRuntimeFacadeOperationsPhase {
    val sharedState = foundation.sharedState
    val routingAndTrust = foundation.routingAndTrust
    val sessionAndHopTransport = session.sessionAndHopTransport
    val handshake = session.handshake
    val transportSupport =
        buildMeshEngineRuntimeTransportSupport(
            environment = environment,
            support = support,
            sharedState = sharedState,
            routingAndTrust = routingAndTrust,
            sessionAndHopTransport = sessionAndHopTransport,
            handshake = handshake,
            transferAndInbound = transferAndInbound,
        )
    val transportCollector = buildMeshEngineRuntimeTransportCollector(environment, transportSupport)
    val lifecycleSupport =
        buildMeshEngineRuntimeLifecycleSupport(
            environment = environment,
            support = support,
            sharedState = sharedState,
            transportSupport = transportSupport,
            transportCollector = transportCollector,
            transferAndInbound = transferAndInbound,
        )
    val sendSupport =
        buildMeshEngineRuntimeSendSupport(
            environment = environment,
            support = support,
            sessionAndHopTransport = sessionAndHopTransport,
            transferAndInbound = transferAndInbound,
            routingAndTrust = routingAndTrust,
        )
    val peerForgetSupport =
        buildMeshEngineRuntimePeerForgetSupport(
            environment = environment,
            sharedState = sharedState,
            routingAndTrust = routingAndTrust,
        )
    return MeshEngineRuntimeFacadeOperationsPhase(
        lifecycleSupport = lifecycleSupport,
        sendSupport = sendSupport,
        peerForgetSupport = peerForgetSupport,
    )
}

private fun buildMeshEngineRuntimeTransportSupport(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    support: MeshEngineRuntimeAssemblySupport,
    sharedState: MeshEngineRuntimeSharedState,
    routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
    sessionAndHopTransport: MeshEngineRuntimeSessionAndHopTransportPhase,
    handshake: MeshEngineRuntimeHandshakePhase,
    transferAndInbound: MeshEngineRuntimeTransferAndInboundPhase,
): MeshEngineTransportSupport {
    return MeshEngineTransportSupport(
        peerState =
            MeshEngineTransportPeerState(
                presenceTracker = sharedState.presenceTracker,
                mutablePeerEvents = environment.compatibilitySurface.mutablePeerEvents,
                sessionRegistry = sharedState.sessionRegistry,
            ),
        routingContext =
            MeshEngineTransportRoutingContext(
                routeCoordinator = sharedState.routeCoordinator,
                routingSupport = routingAndTrust.routingSupport,
            ),
        callbacks =
            MeshEngineTransportCallbacks(
                prewarmHopSession = sessionAndHopTransport.peerFlowSupport::prewarmHopSession,
                handleHandshakeMessage1 =
                    handshake.responderHandshakeSupport::handleHandshakeMessage1,
                handleHandshakeMessage2 =
                    handshake.initiatorHandshakeSupport::handleHandshakeMessage2,
                handleHandshakeMessage3 =
                    handshake.responderHandshakeSupport::handleHandshakeMessage3,
                handleEncryptedDataFrame =
                    transferAndInbound.inboundSupport::handleEncryptedDataFrame,
            ),
        emitDiagnostic = support.emitDiagnostic,
    )
}

private fun buildMeshEngineRuntimeTransportCollector(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    transportSupport: MeshEngineTransportSupport,
): MeshEngineTransportCollector {
    return MeshEngineTransportCollector(
        coroutineScope = environment.coroutineScope,
        transportEvents = { environment.platformBridge.events },
        handleTransportEvent = transportSupport::handleTransportEvent,
    )
}

private fun buildMeshEngineRuntimeLifecycleSupport(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    support: MeshEngineRuntimeAssemblySupport,
    sharedState: MeshEngineRuntimeSharedState,
    transportSupport: MeshEngineTransportSupport,
    transportCollector: MeshEngineTransportCollector,
    transferAndInbound: MeshEngineRuntimeTransferAndInboundPhase,
): MeshEngineLifecycleSupport {
    val lifecycleState =
        MeshEngineLifecycleState(
            runtimeSurface = environment.compatibilitySurface,
            outboundTransfers = sharedState.outboundTransfers,
            inboundTransfers = sharedState.inboundTransfers,
            relayTransfers = sharedState.relayTransfers,
            currentPowerPolicy = sharedState.powerPolicyController.currentPolicy(nowMillis = 0L),
        )
    return MeshEngineLifecycleSupport(
        powerPolicyController = sharedState.powerPolicyController,
        powerPolicyNowMillis = sharedState.powerPolicyNowMillis,
        state = lifecycleState,
        callbacks =
            MeshEngineLifecycleCallbacks(
                ensureTransportCollector = transportCollector::ensureStarted,
                stopTransportCollector = transportCollector::stop,
                updateTransportPowerPolicy = environment.platformBridge::updatePowerPolicy,
                startTransport = environment.platformBridge::start,
                pauseTransport = environment.platformBridge::pause,
                resumeTransport = environment.platformBridge::resume,
                stopTransport = environment.platformBridge::stop,
                launchTransportPowerPolicyUpdate = { policy ->
                    environment.coroutineScope.launch {
                        environment.platformBridge.updatePowerPolicy(policy)
                    }
                },
                clearVolatileRuntimeView = { stage, removalCode, metadata ->
                    transportSupport.clearRuntimeView(
                        stage = stage,
                        removalCode = removalCode,
                        metadata = metadata,
                    )
                },
                abortCommittedTransfers = { reasonCode ->
                    transferAndInbound.transferSupport.abortLocalTransfers(reasonCode)
                },
            ),
        diagnostics =
            MeshEngineLifecycleDiagnostics(
                emitLifecycleEvent = { code, stage ->
                    support.emitDiagnostic(
                        code,
                        DiagnosticSeverity.INFO,
                        stage,
                        null,
                        DiagnosticReason.STATE_CHANGE,
                        emptyMap(),
                    )
                },
                emitPowerModeChanged = { policy, level, isCharging ->
                    support.emitDiagnostic(
                        DiagnosticCode.POWER_MODE_CHANGED,
                        DiagnosticSeverity.INFO,
                        "power.updateBattery",
                        null,
                        DiagnosticReason.POWER_CHANGE,
                        powerPolicyMetadata(policy = policy, level = level, isCharging = isCharging),
                    )
                },
            ),
    )
}

private fun buildMeshEngineRuntimeSendSupport(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    support: MeshEngineRuntimeAssemblySupport,
    sessionAndHopTransport: MeshEngineRuntimeSessionAndHopTransportPhase,
    transferAndInbound: MeshEngineRuntimeTransferAndInboundPhase,
    routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
): MeshEngineSendSupport {
    return MeshEngineSendSupport(
        config =
            MeshEngineSendConfig(
                maxSupportedPayloadBytes = MAX_SUPPORTED_PAYLOAD_BYTES,
                inlineMessagePayloadBytes = INLINE_MESSAGE_PAYLOAD_BYTES,
            ),
        callbacks =
            MeshEngineSendCallbacks(
                currentLifecycleState = environment.compatibilitySurface::currentState,
                captureHardRunToken =
                    environment.compatibilitySurface.runtimeGate::captureHardRunToken,
                hasTransport = { environment.platformBridge.hasTransport },
                shouldAttemptLargeInlineSend =
                    sessionAndHopTransport.peerFlowSupport::shouldAttemptLargeInlineSend,
                sendInlinePayload = { peerId, payload, priority, hardRunToken ->
                    transferAndInbound.inlineSendSupport.sendInlinePayload(
                        peerId = peerId,
                        payload = payload,
                        priority = priority,
                        hardRunToken = hardRunToken,
                    )
                },
                sendLargePayload = { peerId, payload, priority, hardRunToken ->
                    transferAndInbound.largeTransferSupport.sendLargePayload(
                        peerId = peerId,
                        payload = payload,
                        priority = priority,
                        hardRunToken = hardRunToken,
                    )
                },
                scheduleRetryDiagnostic = routingAndTrust.scheduleRetryDiagnostic,
                emitDiagnostic = support.emitDiagnostic,
            ),
    )
}

private fun buildMeshEngineRuntimePeerForgetSupport(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    sharedState: MeshEngineRuntimeSharedState,
    routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
): MeshEnginePeerForgetSupport {
    return MeshEnginePeerForgetSupport(
        callbacks =
            MeshEnginePeerForgetCallbacks(
                readFirstSeenAtEpochMillis = { peerId ->
                    environment.trustStore.read(peerId.value)?.firstSeenAtEpochMillis
                },
                deleteTrust = { peerId -> environment.trustStore.delete(peerId.value) },
                clearPeer = sharedState.sessionRegistry::clearPeer,
                dispatchPeerDisconnected = { peerId, metadata ->
                    routingAndTrust.routingSupport.dispatchMutation(
                        mutation = sharedState.routeCoordinator.onPeerDisconnected(peerId),
                        stage = "trust.forgetPeer",
                        removalCode = DiagnosticCode.ROUTE_RETRACTED,
                        metadata = metadata,
                    )
                },
                markPeerDisconnected = sharedState.presenceTracker::onPeerDisconnected,
                emitPeerLost = { peerId ->
                    environment.compatibilitySurface.mutablePeerEvents.emit(PeerEvent.Lost(peerId))
                },
            )
    )
}
