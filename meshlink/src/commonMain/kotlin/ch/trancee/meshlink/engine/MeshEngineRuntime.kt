package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.ForgetPeerResult
import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.PauseResult
import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.ResumeResult
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.api.StartResult
import ch.trancee.meshlink.api.StopResult
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticEvent
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.diagnostics.DiagnosticSink
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.storage.SecureStorage
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.trust.TofuTrustStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal class MeshEngineRuntime
private constructor(
    publishedSurface: MeshEnginePublishedRuntimeSurface,
    facadeOperations: MeshEngineRuntimeFacadeOperationsPhase,
) : MeshLinkApi {
    private val lifecycleSupport: MeshEngineLifecycleSupport = facadeOperations.lifecycleSupport
    private val sendSupport: MeshEngineSendSupport = facadeOperations.sendSupport
    private val peerForgetSupport: MeshEnginePeerForgetSupport = facadeOperations.peerForgetSupport

    override val state: StateFlow<MeshLinkState> = publishedSurface.state
    override val peerEvents: Flow<PeerEvent> = publishedSurface.peerEvents
    override val diagnosticEvents: Flow<DiagnosticEvent> = publishedSurface.diagnosticEvents
    override val messages: Flow<InboundMessage> = publishedSurface.messages

    override suspend fun start(): StartResult {
        return lifecycleSupport.start()
    }

    override suspend fun pause(): PauseResult {
        return lifecycleSupport.pause()
    }

    override suspend fun resume(): ResumeResult {
        return lifecycleSupport.resume()
    }

    override suspend fun stop(): StopResult {
        return lifecycleSupport.stop()
    }

    override suspend fun send(
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
    ): SendResult {
        return sendSupport.send(peerId = peerId, payload = payload, priority = priority)
    }

    override suspend fun forgetPeer(peerId: PeerId): ForgetPeerResult {
        return peerForgetSupport.forgetPeer(peerId)
    }

    override fun updateBattery(level: Float, isCharging: Boolean): Unit {
        lifecycleSupport.updateBattery(level = level, isCharging = isCharging)
    }

    internal companion object {
        internal fun assembleMeshEngineRuntime(
            config: MeshLinkConfig,
            localIdentity: LocalIdentity,
            secureStorage: SecureStorage,
            bleTransport: ch.trancee.meshlink.transport.BleTransport? = null,
            diagnosticSink: DiagnosticSink? = null,
            coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ): MeshEngineRuntime {
            val environment =
                buildMeshEngineRuntimeAssemblyEnvironment(
                    config = config,
                    localIdentity = localIdentity,
                    secureStorage = secureStorage,
                    bleTransport = bleTransport,
                    diagnosticSink = diagnosticSink,
                    coroutineScope = coroutineScope,
                )
            val support = buildMeshEngineRuntimeAssemblySupport(environment)
            val lateBindingContext = buildMeshEngineRuntimeLateBindingContext()
            val facadeOperations =
                RuntimeGraphAssembler(
                        environment = environment,
                        support = support,
                        lateBindingContext = lateBindingContext,
                    )
                    .assemble()
            return MeshEngineRuntime(
                publishedSurface = environment.publishedSurface,
                facadeOperations = facadeOperations,
            )
        }
    }
}

private fun buildMeshEngineRuntimeAssemblyEnvironment(
    config: MeshLinkConfig,
    localIdentity: LocalIdentity,
    secureStorage: SecureStorage,
    bleTransport: ch.trancee.meshlink.transport.BleTransport?,
    diagnosticSink: DiagnosticSink?,
    coroutineScope: CoroutineScope,
): MeshEngineRuntimeAssemblyEnvironment {
    val runtimeSurface = MeshEngineRuntimeSurface(diagnosticSink = diagnosticSink)
    return MeshEngineRuntimeAssemblyEnvironment(
        config = config,
        localIdentity = localIdentity,
        trustStore = TofuTrustStore(secureStorage),
        coroutineScope = coroutineScope,
        platformBridge = MeshEnginePlatformBridge(bleTransport),
        publishedSurface = runtimeSurface,
        compatibilitySurface = runtimeSurface,
    )
}

private fun buildMeshEngineRuntimeAssemblySupport(
    environment: MeshEngineRuntimeAssemblyEnvironment
): MeshEngineRuntimeAssemblySupport {
    return MeshEngineRuntimeAssemblySupport(
        emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
            environment.compatibilitySurface.emitDiagnostic(
                code = code,
                severity = severity,
                stage = stage,
                peerSuffix = peerSuffix,
                reason = reason,
                metadata = metadata,
            )
        },
        sendDirectWireFrame = { peerId, frame, action, preferredMode ->
            environment.platformBridge.send(
                frame =
                    OutboundFrame(
                        peerId = peerId,
                        payload = frame.encode(),
                        preferredMode = preferredMode,
                    ),
                action = action,
            )
        },
    )
}

private fun buildMeshEngineRuntimeLateBindingContext(): MeshEngineRuntimeLateBindingContext {
    return MeshEngineRuntimeLateBindingContext()
}

private class RuntimeGraphAssembler(
    private val environment: MeshEngineRuntimeAssemblyEnvironment,
    private val support: MeshEngineRuntimeAssemblySupport,
    private val lateBindingContext: MeshEngineRuntimeLateBindingContext,
) {
    private val config: MeshLinkConfig
        get() = environment.config

    private val localIdentity: LocalIdentity
        get() = environment.localIdentity

    private val trustStore: TofuTrustStore
        get() = environment.trustStore

    private val coroutineScope: CoroutineScope
        get() = environment.coroutineScope

    private val platformBridge: MeshEnginePlatformBridge
        get() = environment.platformBridge

    private val runtimeSurface: MeshEngineCompatibilityRuntimeSurface
        get() = environment.compatibilitySurface

    fun assemble(): MeshEngineRuntimeFacadeOperationsPhase {
        val foundation =
            buildMeshEngineRuntimeFoundationAssembly(
                environment = environment,
                support = support,
                lateBindingContext = lateBindingContext,
            )
        val session =
            buildMeshEngineRuntimeSessionAssembly(
                environment = environment,
                support = support,
                foundation = foundation,
                lateBindingContext = lateBindingContext,
            )
        val transferAndInbound =
            buildMeshEngineRuntimeTransferAndInboundPhase(
                environment = environment,
                support = support,
                foundation = foundation,
                session = session,
            )
        return buildMeshEngineRuntimeFacadeOperationsPhase(
            foundation = foundation,
            session = session,
            transferAndInbound = transferAndInbound,
        )
    }

    private fun buildMeshEngineRuntimeFacadeOperationsPhase(
        foundation: MeshEngineRuntimeFoundationAssembly,
        session: MeshEngineRuntimeSessionAssembly,
        transferAndInbound: MeshEngineRuntimeTransferAndInboundPhase,
    ): MeshEngineRuntimeFacadeOperationsPhase {
        val sharedState = foundation.sharedState
        val routingAndTrust = foundation.routingAndTrust
        val sessionAndHopTransport = session.sessionAndHopTransport
        val handshake = session.handshake
        val transportSupport =
            buildTransportSupport(
                sharedState = sharedState,
                routingAndTrust = routingAndTrust,
                sessionAndHopTransport = sessionAndHopTransport,
                handshake = handshake,
                transferAndInbound = transferAndInbound,
            )
        val transportCollector = buildTransportCollector(transportSupport)
        val lifecycleSupport =
            buildLifecycleSupport(
                sharedState = sharedState,
                transportSupport = transportSupport,
                transportCollector = transportCollector,
                transferAndInbound = transferAndInbound,
            )
        val sendSupport =
            buildSendSupport(
                sessionAndHopTransport = sessionAndHopTransport,
                transferAndInbound = transferAndInbound,
                routingAndTrust = routingAndTrust,
            )
        val peerForgetSupport =
            buildPeerForgetSupport(sharedState = sharedState, routingAndTrust = routingAndTrust)
        return MeshEngineRuntimeFacadeOperationsPhase(
            lifecycleSupport = lifecycleSupport,
            sendSupport = sendSupport,
            peerForgetSupport = peerForgetSupport,
        )
    }

    private fun buildTransportSupport(
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
                    mutablePeerEvents = runtimeSurface.mutablePeerEvents,
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
            emitDiagnostic = ::emitDiagnostic,
        )
    }

    private fun buildTransportCollector(
        transportSupport: MeshEngineTransportSupport
    ): MeshEngineTransportCollector {
        return MeshEngineTransportCollector(
            coroutineScope = coroutineScope,
            transportEvents = { platformBridge.events },
            handleTransportEvent = transportSupport::handleTransportEvent,
        )
    }

    private fun buildLifecycleSupport(
        sharedState: MeshEngineRuntimeSharedState,
        transportSupport: MeshEngineTransportSupport,
        transportCollector: MeshEngineTransportCollector,
        transferAndInbound: MeshEngineRuntimeTransferAndInboundPhase,
    ): MeshEngineLifecycleSupport {
        val lifecycleState =
            MeshEngineLifecycleState(
                runtimeSurface = runtimeSurface,
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
                    updateTransportPowerPolicy = platformBridge::updatePowerPolicy,
                    startTransport = platformBridge::start,
                    pauseTransport = platformBridge::pause,
                    resumeTransport = platformBridge::resume,
                    stopTransport = platformBridge::stop,
                    launchTransportPowerPolicyUpdate = { policy ->
                        coroutineScope.launch { platformBridge.updatePowerPolicy(policy) }
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
                        emitDiagnostic(
                            code = code,
                            severity = DiagnosticSeverity.INFO,
                            stage = stage,
                            reason = DiagnosticReason.STATE_CHANGE,
                        )
                    },
                    emitPowerModeChanged = { policy, level, isCharging ->
                        emitDiagnostic(
                            code = DiagnosticCode.POWER_MODE_CHANGED,
                            severity = DiagnosticSeverity.INFO,
                            stage = "power.updateBattery",
                            reason = DiagnosticReason.POWER_CHANGE,
                            metadata =
                                powerPolicyMetadata(
                                    policy = policy,
                                    level = level,
                                    isCharging = isCharging,
                                ),
                        )
                    },
                ),
        )
    }

    private fun buildSendSupport(
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
                    currentLifecycleState = runtimeSurface::currentState,
                    captureHardRunToken = runtimeSurface.runtimeGate::captureHardRunToken,
                    hasTransport = { platformBridge.hasTransport },
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
                    emitDiagnostic = ::emitDiagnostic,
                ),
        )
    }

    private fun buildPeerForgetSupport(
        sharedState: MeshEngineRuntimeSharedState,
        routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
    ): MeshEnginePeerForgetSupport {
        return MeshEnginePeerForgetSupport(
            callbacks =
                MeshEnginePeerForgetCallbacks(
                    readFirstSeenAtEpochMillis = { peerId ->
                        trustStore.read(peerId.value)?.firstSeenAtEpochMillis
                    },
                    deleteTrust = { peerId -> trustStore.delete(peerId.value) },
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
                        runtimeSurface.mutablePeerEvents.emit(PeerEvent.Lost(peerId))
                    },
                )
        )
    }

    private suspend fun sendDirectWireFrame(
        peerId: PeerId,
        frame: DirectWireFrame,
        action: String,
        preferredMode: TransportMode? = null,
    ): TransportSendResult {
        return support.sendDirectWireFrame(peerId, frame, action, preferredMode)
    }

    @Suppress("LongParameterList")
    private fun emitDiagnostic(
        code: DiagnosticCode,
        severity: DiagnosticSeverity,
        stage: String,
        peerSuffix: String? = null,
        reason: DiagnosticReason? = null,
        metadata: Map<String, String> = emptyMap(),
    ): Unit {
        support.emitDiagnostic(code, severity, stage, peerSuffix, reason, metadata)
    }
}

private const val MAX_SUPPORTED_PAYLOAD_BYTES: Int = 64 * 1024
private const val INLINE_MESSAGE_PAYLOAD_BYTES: Int = 1_024
