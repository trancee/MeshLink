package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.BatterySnapshot
import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.ForgetPeerResult
import ch.trancee.meshlink.api.PauseResult
import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.ResumeResult
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.api.StartResult
import ch.trancee.meshlink.api.StopResult
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import kotlinx.coroutines.launch

internal interface MeshEngineRuntimeFacadeOperations {
    suspend fun start(): StartResult

    suspend fun pause(): PauseResult

    suspend fun resume(): ResumeResult

    suspend fun stop(): StopResult

    suspend fun send(peerId: PeerId, payload: ByteArray, priority: DeliveryPriority): SendResult

    suspend fun forgetPeer(peerId: PeerId): ForgetPeerResult

    fun updateBattery(snapshot: BatterySnapshot): Unit
}

internal fun buildMeshEngineRuntimeFacadeOperations(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    support: MeshEngineRuntimeAssemblySupport,
    foundation: MeshEngineRuntimeFoundationAssembly,
    session: MeshEngineRuntimeSessionAssembly,
    transferAndInbound: MeshEngineRuntimeTransferAndInboundPhase,
): MeshEngineRuntimeFacadeOperations {
    val sharedState = foundation.sharedState
    val routingAndTrust = foundation.routingAndTrust
    val transportSupport =
        buildMeshEngineRuntimeTransportSupport(
            presenceTracker = sharedState.presenceTracker,
            mutablePeerEvents = environment.compatibilitySurface.mutablePeerEvents,
            sessionRegistry = sharedState.sessionRegistry,
            routeCoordinator = sharedState.routeCoordinator,
            routingSupport = routingAndTrust.routingSupport,
            prewarmHopSession = session.prewarmHopSession,
            handleHandshakeMessage1 = session.handleHandshakeMessage1,
            handleHandshakeMessage2 = session.handleHandshakeMessage2,
            handleHandshakeMessage3 = session.handleHandshakeMessage3,
            handleEncryptedDataFrame = transferAndInbound.handleEncryptedDataFrame,
            emitDiagnostic = support.emitDiagnostic,
        )
    val transportCollector =
        buildMeshEngineRuntimeTransportCollector(
            coroutineScope = environment.coroutineScope,
            transportEvents = { environment.platformBridge.events },
            handleTransportEvent = transportSupport::handleTransportEvent,
        )
    val powerPolicySupport =
        buildMeshEngineRuntimePowerPolicySupport(
            powerPolicyController = sharedState.powerPolicyController,
            powerPolicyNowMillis = sharedState.runtimePolicies.powerPolicyNowMillis,
            updateTransportPowerPolicy = environment.platformBridge::updatePowerPolicy,
            launchTransportPowerPolicyUpdate = { policy ->
                environment.coroutineScope.launch {
                    environment.platformBridge.updatePowerPolicy(policy)
                }
            },
            emitDiagnostic = support.emitDiagnostic,
        )
    val lifecycleSupport =
        buildMeshEngineRuntimeLifecycleSupport(
            runtimeSurface = environment.compatibilitySurface,
            inboundTransfers = sharedState.inboundTransfers,
            relayTransfers = sharedState.relayTransfers,
            ensureTransportCollector = transportCollector::ensureStarted,
            stopTransportCollector = transportCollector::stop,
            startTransport = environment.platformBridge::start,
            pauseTransport = environment.platformBridge::pause,
            resumeTransport = environment.platformBridge::resume,
            stopTransport = environment.platformBridge::stop,
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
            powerPolicySupport = powerPolicySupport,
        )
    val sendSupport =
        buildMeshEngineRuntimeSendSupport(
            currentLifecycleState = environment.compatibilitySurface::currentState,
            captureHardRunToken = environment.compatibilitySurface.runtimeGate::captureHardRunToken,
            hasTransport = { environment.platformBridge.hasTransport },
            inlineMessagePayloadBytes =
                sharedState.runtimePolicies.delivery.inlineMessagePayloadBytes,
            shouldAttemptLargeInlineSend = session.shouldAttemptLargeInlineSend,
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
    return object : MeshEngineRuntimeFacadeOperations {
        override suspend fun start() = lifecycleSupport.start()

        override suspend fun pause() = lifecycleSupport.pause()

        override suspend fun resume() = lifecycleSupport.resume()

        override suspend fun stop() = lifecycleSupport.stop()

        override suspend fun send(
            peerId: ch.trancee.meshlink.api.PeerId,
            payload: ByteArray,
            priority: ch.trancee.meshlink.api.DeliveryPriority,
        ) = sendSupport.send(peerId = peerId, payload = payload, priority = priority)

        override suspend fun forgetPeer(peerId: ch.trancee.meshlink.api.PeerId) =
            peerForgetSupport.forgetPeer(peerId)

        override fun updateBattery(snapshot: BatterySnapshot) {
            powerPolicySupport.updateBattery(snapshot)
        }
    }
}
