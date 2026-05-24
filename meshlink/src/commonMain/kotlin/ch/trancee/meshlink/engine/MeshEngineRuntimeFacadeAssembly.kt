package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.diagnostics.DiagnosticCode
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
            presenceTracker = sharedState.presenceTracker,
            mutablePeerEvents = environment.compatibilitySurface.mutablePeerEvents,
            sessionRegistry = sharedState.sessionRegistry,
            routeCoordinator = sharedState.routeCoordinator,
            routingSupport = routingAndTrust.routingSupport,
            prewarmHopSession = sessionAndHopTransport.peerFlowSupport::prewarmHopSession,
            handleHandshakeMessage1 = handshake.responderHandshakeSupport::handleHandshakeMessage1,
            handleHandshakeMessage2 = handshake.initiatorHandshakeSupport::handleHandshakeMessage2,
            handleHandshakeMessage3 = handshake.responderHandshakeSupport::handleHandshakeMessage3,
            handleEncryptedDataFrame = transferAndInbound.handleEncryptedDataFrame,
            emitDiagnostic = support.emitDiagnostic,
        )
    val transportCollector =
        buildMeshEngineRuntimeTransportCollector(
            coroutineScope = environment.coroutineScope,
            transportEvents = { environment.platformBridge.events },
            handleTransportEvent = transportSupport::handleTransportEvent,
        )
    val lifecycleSupport =
        buildMeshEngineRuntimeLifecycleSupport(
            powerPolicyController = sharedState.powerPolicyController,
            powerPolicyNowMillis = sharedState.powerPolicyNowMillis,
            runtimeSurface = environment.compatibilitySurface,
            inboundTransfers = sharedState.inboundTransfers,
            relayTransfers = sharedState.relayTransfers,
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
            abortCommittedTransfers = transferAndInbound.abortLocalTransfers,
            clearOutboundTransfers = transferAndInbound.clearOutboundTransfers,
            emitDiagnostic = support.emitDiagnostic,
        )
    val sendSupport =
        buildMeshEngineRuntimeSendSupport(
            currentLifecycleState = environment.compatibilitySurface::currentState,
            captureHardRunToken = environment.compatibilitySurface.runtimeGate::captureHardRunToken,
            hasTransport = { environment.platformBridge.hasTransport },
            shouldAttemptLargeInlineSend =
                sessionAndHopTransport.peerFlowSupport::shouldAttemptLargeInlineSend,
            sendPayload = transferAndInbound.sendPayload,
            scheduleRetryDiagnostic = routingAndTrust.scheduleRetryDiagnostic,
            emitDiagnostic = support.emitDiagnostic,
        )
    val peerForgetSupport =
        buildMeshEngineRuntimePeerForgetSupport(
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
    return MeshEngineRuntimeFacadeOperationsPhase(
        lifecycleSupport = lifecycleSupport,
        sendSupport = sendSupport,
        peerForgetSupport = peerForgetSupport,
    )
}
