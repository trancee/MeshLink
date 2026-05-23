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
import ch.trancee.meshlink.power.PowerPolicyController
import ch.trancee.meshlink.presence.PeerPresenceTracker
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.storage.SecureStorage
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.trust.TofuTrustStore
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
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
            val runtimeSurface = MeshEngineRuntimeSurface(diagnosticSink = diagnosticSink)
            val facadeOperations =
                RuntimeGraphAssembler(
                        config = config,
                        localIdentity = localIdentity,
                        secureStorage = secureStorage,
                        coroutineScope = coroutineScope,
                        platformBridge = MeshEnginePlatformBridge(bleTransport),
                        runtimeSurface = runtimeSurface,
                    )
                    .assemble()
            return MeshEngineRuntime(
                publishedSurface = runtimeSurface,
                facadeOperations = facadeOperations,
            )
        }
    }
}

private class RuntimeGraphAssembler(
    private val config: MeshLinkConfig,
    private val localIdentity: LocalIdentity,
    secureStorage: SecureStorage,
    private val coroutineScope: CoroutineScope,
    private val platformBridge: MeshEnginePlatformBridge,
    private val runtimeSurface: MeshEngineCompatibilityRuntimeSurface,
) {
    private val trustStore = TofuTrustStore(secureStorage)

    fun assemble(): MeshEngineRuntimeFacadeOperationsPhase {
        val context = AssemblyContext()
        context.sharedState = buildSharedState()
        context.routingAndTrust = buildRoutingAndTrust(context)
        context.sessionAndHopTransport = buildSessionAndHopTransport(context)
        context.handshake = buildHandshake(context)
        context.transferAndInbound = buildTransferAndInbound(context)
        context.facadeOperations = buildTransportAndFacadeOperations(context)
        return context.facadeOperations
    }

    private fun buildSharedState(): MeshEngineRuntimeSharedState {
        val routeCoordinator = RouteCoordinator(localIdentity.peerId)
        val engineClock = TimeSource.Monotonic.markNow()
        return MeshEngineRuntimeSharedState(
            presenceTracker = PeerPresenceTracker(),
            routeCoordinator = routeCoordinator,
            deliveryRetryScheduler = DeliveryRetryScheduler(routeCoordinator.topologyVersion),
            powerPolicyController =
                PowerPolicyController(
                    configuredMode = config.powerMode,
                    region = config.regulatoryRegion,
                ),
            sessionRegistry = MeshEngineSessionRegistry(),
            outboundTransfers = linkedMapOf(),
            inboundTransfers = linkedMapOf(),
            relayTransfers = linkedMapOf(),
            sequenceGenerator = MeshEngineSequenceGenerator(localIdentity),
            powerPolicyNowMillis = { engineClock.elapsedNow().inWholeMilliseconds },
            ttlMillisFor = { priority ->
                when (priority) {
                    DeliveryPriority.HIGH -> HIGH_PRIORITY_TTL_MILLIS
                    DeliveryPriority.NORMAL -> NORMAL_PRIORITY_TTL_MILLIS
                    DeliveryPriority.LOW -> LOW_PRIORITY_TTL_MILLIS
                }
            },
        )
    }

    private fun buildRoutingAndTrust(
        context: AssemblyContext
    ): MeshEngineRuntimeRoutingAndTrustPhase {
        val sharedState = context.sharedState
        val routingSupport =
            MeshEngineRoutingSupport(
                routeCoordinator = sharedState.routeCoordinator,
                runtimeGate = runtimeSurface.runtimeGate,
                coroutineScope = coroutineScope,
                emitDiagnostic = ::emitDiagnostic,
                sendEncryptedWireFrame = { peerId, frame, action, hardRunToken ->
                    context.sessionAndHopTransport.hopTransportSupport.sendEncryptedWireFrame(
                        peerId = peerId,
                        frame = frame,
                        action = action,
                        hardRunToken = hardRunToken,
                    )
                },
            )
        val trustSupport =
            MeshEngineTrustSupport(
                localIdentity = localIdentity,
                trustStore = trustStore,
                emitDiagnostic = ::emitDiagnostic,
            )
        val scheduleRetryDiagnostic = { peerId: PeerId, priority: DeliveryPriority ->
            scheduleRetryDiagnostic(routingSupport, peerId, priority)
        }
        return MeshEngineRuntimeRoutingAndTrustPhase(
            routingSupport = routingSupport,
            trustSupport = trustSupport,
            scheduleRetryDiagnostic = scheduleRetryDiagnostic,
        )
    }

    private fun buildSessionAndHopTransport(
        context: AssemblyContext
    ): MeshEngineRuntimeSessionAndHopTransportPhase {
        val sharedState = context.sharedState
        val routingAndTrust = context.routingAndTrust
        val sessionSupport =
            buildSessionSupport(sharedState = sharedState, routingAndTrust = routingAndTrust)
        val hopTransportSupport =
            buildHopTransportSupport(
                routingAndTrust = routingAndTrust,
                sessionSupport = sessionSupport,
            )
        val peerFlowSupport =
            buildPeerFlowSupport(
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

    private fun buildHandshake(context: AssemblyContext): MeshEngineRuntimeHandshakePhase {
        val sharedState = context.sharedState
        val routingAndTrust = context.routingAndTrust
        val sessionAndHopTransport = context.sessionAndHopTransport
        val handshakeState = MeshEngineHandshakeState(sessionRegistry = sharedState.sessionRegistry)
        val handshakeRoutingContext =
            MeshEngineHandshakeRoutingContext(
                routeCoordinator = sharedState.routeCoordinator,
                routingSupport = routingAndTrust.routingSupport,
            )
        val handshakeCallbacks =
            buildHandshakeCallbacks(sessionAndHopTransport = sessionAndHopTransport)
        val initiatorHandshakeSupport =
            buildInitiatorHandshakeSupport(
                routingAndTrust = routingAndTrust,
                handshakeState = handshakeState,
                handshakeRoutingContext = handshakeRoutingContext,
                handshakeCallbacks = handshakeCallbacks,
            )
        val responderHandshakeSupport =
            buildResponderHandshakeSupport(
                routingAndTrust = routingAndTrust,
                handshakeState = handshakeState,
                handshakeRoutingContext = handshakeRoutingContext,
                handshakeCallbacks = handshakeCallbacks,
            )
        return MeshEngineRuntimeHandshakePhase(
            initiatorHandshakeSupport = initiatorHandshakeSupport,
            responderHandshakeSupport = responderHandshakeSupport,
        )
    }

    private fun buildTransferAndInbound(
        context: AssemblyContext
    ): MeshEngineRuntimeTransferAndInboundPhase {
        val sharedState = context.sharedState
        val routingAndTrust = context.routingAndTrust
        val sessionAndHopTransport = context.sessionAndHopTransport
        val messageDeliverySupport =
            buildMessageDeliverySupport(
                routingAndTrust = routingAndTrust,
                sessionAndHopTransport = sessionAndHopTransport,
            )
        val outboundPreparationSupport =
            buildOutboundPreparationSupport(
                sharedState = sharedState,
                routingAndTrust = routingAndTrust,
                sessionAndHopTransport = sessionAndHopTransport,
            )
        val inlineSendSupport =
            buildInlineSendSupport(
                sharedState = sharedState,
                routingAndTrust = routingAndTrust,
                sessionAndHopTransport = sessionAndHopTransport,
                outboundPreparationSupport = outboundPreparationSupport,
            )
        val transferSupport =
            buildTransferSupport(
                sharedState = sharedState,
                routingAndTrust = routingAndTrust,
                sessionAndHopTransport = sessionAndHopTransport,
                messageDeliverySupport = messageDeliverySupport,
            )
        val largeTransferSupport =
            buildLargeTransferSupport(
                sharedState = sharedState,
                routingAndTrust = routingAndTrust,
                sessionAndHopTransport = sessionAndHopTransport,
                outboundPreparationSupport = outboundPreparationSupport,
            )
        val inboundSupport =
            buildInboundSupport(
                sharedState = sharedState,
                routingAndTrust = routingAndTrust,
                sessionAndHopTransport = sessionAndHopTransport,
                messageDeliverySupport = messageDeliverySupport,
                transferSupport = transferSupport,
            )
        return MeshEngineRuntimeTransferAndInboundPhase(
            inlineSendSupport = inlineSendSupport,
            transferSupport = transferSupport,
            largeTransferSupport = largeTransferSupport,
            inboundSupport = inboundSupport,
        )
    }

    private fun buildSessionSupport(
        sharedState: MeshEngineRuntimeSharedState,
        routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
    ): MeshEngineSessionSupport {
        return MeshEngineSessionSupport(
            localIdentity = localIdentity,
            state =
                MeshEngineSessionState(
                    sessionRegistry = sharedState.sessionRegistry,
                    runtimeGate = runtimeSurface.runtimeGate,
                ),
            handshakeTimeout = HANDSHAKE_TIMEOUT,
            callbacks =
                MeshEngineSessionCallbacks(
                    hasTransport = { platformBridge.hasTransport },
                    sendDirectWireFrame = { peerId, frame, action, preferredMode ->
                        sendDirectWireFrame(
                            peerId = peerId,
                            frame = frame,
                            action = action,
                            preferredMode = preferredMode,
                        )
                    },
                    emitHopSessionFailed = { peerId, stage, reason, metadata ->
                        emitDiagnostic(
                            code = DiagnosticCode.HOP_SESSION_FAILED,
                            severity = DiagnosticSeverity.WARN,
                            stage = stage,
                            peerSuffix = peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                            reason = reason,
                            metadata =
                                routingAndTrust.routingSupport.peerRouteMetadata(
                                    peerId,
                                    metadata = metadata,
                                ),
                        )
                    },
                ),
        )
    }

    private fun buildHopTransportSupport(
        routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
        sessionSupport: MeshEngineSessionSupport,
    ): MeshEngineHopTransportSupport {
        return MeshEngineHopTransportSupport(
            localIdentity = localIdentity,
            runtimeGate = runtimeSurface.runtimeGate,
            routingSupport = routingAndTrust.routingSupport,
            establishedHopSession = { peerId -> sessionSupport.establishedHopSession(peerId) },
            ensureHopSession = { peerId, hardRunToken ->
                sessionSupport.ensureHopSession(peerId, hardRunToken)
            },
            sendDirectWireFrame = { peerId, frame, action, preferredMode ->
                sendDirectWireFrame(
                    peerId = peerId,
                    frame = frame,
                    action = action,
                    preferredMode = preferredMode,
                )
            },
            emitDiagnostic = ::emitDiagnostic,
        )
    }

    private fun buildPeerFlowSupport(
        sharedState: MeshEngineRuntimeSharedState,
        routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
        sessionSupport: MeshEngineSessionSupport,
        hopTransportSupport: MeshEngineHopTransportSupport,
    ): MeshEnginePeerFlowSupport {
        return MeshEnginePeerFlowSupport(
            localIdentity = localIdentity,
            context =
                MeshEnginePeerFlowContext(
                    routeCoordinator = sharedState.routeCoordinator,
                    coroutineScope = coroutineScope,
                ),
            config =
                MeshEnginePeerFlowConfig(
                    largeInlineTransportBudgetBytes = LARGE_INLINE_SEND_TRANSPORT_BUDGET_BYTES
                ),
            callbacks =
                MeshEnginePeerFlowCallbacks(
                    runtimeGate = runtimeSurface.runtimeGate,
                    captureHardRunToken = runtimeSurface.runtimeGate::captureHardRunToken,
                    sendEncryptedWireFrame = hopTransportSupport::sendEncryptedWireFrame,
                    ensureHopSession = { peerId -> sessionSupport.ensureHopSession(peerId) },
                    maximumPayloadBytesPerDelivery = platformBridge::maximumPayloadBytesPerDelivery,
                    emitDiagnostic = ::emitDiagnostic,
                    peerRouteMetadata = { peerId, metadata ->
                        routingAndTrust.routingSupport.peerRouteMetadata(
                            peerId,
                            metadata = metadata,
                        )
                    },
                ),
        )
    }

    private fun buildHandshakeCallbacks(
        sessionAndHopTransport: MeshEngineRuntimeSessionAndHopTransportPhase
    ): MeshEngineHandshakeCallbacks {
        return MeshEngineHandshakeCallbacks(
            sendDirectWireFrame = { peerId, frame, action ->
                sendDirectWireFrame(peerId = peerId, frame = frame, action = action)
            },
            emitHopSessionEstablished =
                sessionAndHopTransport.hopTransportSupport::emitHopSessionEstablished,
            emitHopSessionFailed = sessionAndHopTransport.hopTransportSupport::emitHopSessionFailed,
            promoteTemporaryPeer = { temporaryPeerId, canonicalPeerId ->
                runCatching {
                        platformBridge.promoteTemporaryPeer(temporaryPeerId, canonicalPeerId)
                    }
                    .getOrElse { Unit }
            },
        )
    }

    private fun buildInitiatorHandshakeSupport(
        routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
        handshakeState: MeshEngineHandshakeState,
        handshakeRoutingContext: MeshEngineHandshakeRoutingContext,
        handshakeCallbacks: MeshEngineHandshakeCallbacks,
    ): MeshEngineInitiatorHandshakeSupport {
        return MeshEngineInitiatorHandshakeSupport(
            localIdentity = localIdentity,
            trustSupport = routingAndTrust.trustSupport,
            state = handshakeState,
            routingContext = handshakeRoutingContext,
            callbacks = handshakeCallbacks,
        )
    }

    private fun buildResponderHandshakeSupport(
        routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
        handshakeState: MeshEngineHandshakeState,
        handshakeRoutingContext: MeshEngineHandshakeRoutingContext,
        handshakeCallbacks: MeshEngineHandshakeCallbacks,
    ): MeshEngineResponderHandshakeSupport {
        return MeshEngineResponderHandshakeSupport(
            localIdentity = localIdentity,
            trustSupport = routingAndTrust.trustSupport,
            state = handshakeState,
            routingContext = handshakeRoutingContext,
            callbacks = handshakeCallbacks,
        )
    }

    private fun buildMessageDeliverySupport(
        routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
        sessionAndHopTransport: MeshEngineRuntimeSessionAndHopTransportPhase,
    ): MeshEngineMessageDeliverySupport {
        return MeshEngineMessageDeliverySupport(
            localIdentity = localIdentity,
            runtimeGate = runtimeSurface.runtimeGate,
            trustSupport = routingAndTrust.trustSupport,
            mutableMessages = runtimeSurface.mutableMessages,
            emitHopSessionFailed = sessionAndHopTransport.hopTransportSupport::emitHopSessionFailed,
            emitDiagnostic = ::emitDiagnostic,
        )
    }

    private fun buildOutboundPreparationSupport(
        sharedState: MeshEngineRuntimeSharedState,
        routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
        sessionAndHopTransport: MeshEngineRuntimeSessionAndHopTransportPhase,
    ): MeshEngineOutboundPreparationSupport {
        return MeshEngineOutboundPreparationSupport(
            localIdentity = localIdentity,
            trustStore = trustStore,
            state =
                MeshEngineOutboundPreparationState(
                    outboundTransfers = sharedState.outboundTransfers
                ),
            routingContext =
                MeshEngineOutboundPreparationRoutingContext(
                    routeCoordinator = sharedState.routeCoordinator,
                    routingSupport = routingAndTrust.routingSupport,
                ),
            callbacks =
                MeshEngineOutboundPreparationCallbacks(
                    createMessageId = sharedState.sequenceGenerator::createMessageId,
                    createTransferId = sharedState.sequenceGenerator::createTransferId,
                    emitTransferEncryptFailure = { peerId, cause ->
                        sessionAndHopTransport.hopTransportSupport.emitHopSessionFailed(
                            peerId = peerId,
                            stage = "transfer.encrypt",
                            reason = DiagnosticReason.TRUST_FAILURE,
                            metadata = mapOf("cause" to cause),
                        )
                    },
                ),
            emitDiagnostic = ::emitDiagnostic,
        )
    }

    private fun buildInlineSendSupport(
        sharedState: MeshEngineRuntimeSharedState,
        routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
        sessionAndHopTransport: MeshEngineRuntimeSessionAndHopTransportPhase,
        outboundPreparationSupport: MeshEngineOutboundPreparationSupport,
    ): MeshEngineInlineSendSupport {
        return MeshEngineInlineSendSupport(
            localIdentity = localIdentity,
            config =
                MeshEngineInlineConfig(
                    deliveryRetryDeadline = config.deliveryRetryDeadline,
                    inlineMessagePayloadBytes = INLINE_MESSAGE_PAYLOAD_BYTES,
                ),
            routingContext =
                MeshEngineInlineRoutingContext(
                    routeCoordinator = sharedState.routeCoordinator,
                    routingSupport = routingAndTrust.routingSupport,
                ),
            dependencies =
                MeshEngineInlineDependencies(
                    runtimeGate = runtimeSurface.runtimeGate,
                    deliveryRetryScheduler = sharedState.deliveryRetryScheduler,
                    ensureHopSession = { peerId, hardRunToken ->
                        sessionAndHopTransport.sessionSupport.ensureHopSession(peerId, hardRunToken)
                    },
                    sendEncryptedDirectWireFrame =
                        sessionAndHopTransport.hopTransportSupport::sendEncryptedDirectWireFrame,
                    resolveRecipientTrust = outboundPreparationSupport::resolveRecipientTrust,
                    scheduleRetryDiagnostic = routingAndTrust.scheduleRetryDiagnostic,
                    setDiscoverySuspended = { suspended ->
                        val action =
                            if (suspended) {
                                "inline.discoverySuspend"
                            } else {
                                "inline.discoveryResume"
                            }
                        platformBridge.setDiscoverySuspended(action = action, suspended = suspended)
                    },
                    emitHopSessionFailed =
                        sessionAndHopTransport.hopTransportSupport::emitHopSessionFailed,
                ),
            callbacks =
                MeshEngineInlineCallbacks(
                    emitDiagnostic = ::emitDiagnostic,
                    createMessageId = sharedState.sequenceGenerator::createMessageId,
                    ttlMillisFor = sharedState.ttlMillisFor,
                ),
        )
    }

    private fun buildTransferSupport(
        sharedState: MeshEngineRuntimeSharedState,
        routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
        sessionAndHopTransport: MeshEngineRuntimeSessionAndHopTransportPhase,
        messageDeliverySupport: MeshEngineMessageDeliverySupport,
    ): MeshEngineTransferSupport {
        return MeshEngineTransferSupport(
            state =
                MeshEngineTransferState(
                    outboundTransfers = sharedState.outboundTransfers,
                    inboundTransfers = sharedState.inboundTransfers,
                    relayTransfers = sharedState.relayTransfers,
                ),
            routingSupport = routingAndTrust.routingSupport,
            callbacks =
                MeshEngineTransferCallbacks(
                    runtimeGate = runtimeSurface.runtimeGate,
                    captureHardRunToken = runtimeSurface.runtimeGate::captureHardRunToken,
                    isLocalPeerId = sessionAndHopTransport.peerFlowSupport::isLocalPeerId,
                    sendEncryptedWireFrame =
                        sessionAndHopTransport.hopTransportSupport::sendEncryptedWireFrame,
                    sendTransferTowardsDestination =
                        createSendTransferTowardsDestination(
                            sharedState = sharedState,
                            sessionAndHopTransport = sessionAndHopTransport,
                        ),
                    deliverInnerEnvelope = messageDeliverySupport::deliverInnerEnvelope,
                    clearQueuedOutboundFrames = { peerId, action ->
                        platformBridge.clearQueuedOutboundFrames(peerId = peerId, action = action)
                    },
                ),
            emitDiagnostic = ::emitDiagnostic,
        )
    }

    private fun buildLargeTransferSupport(
        sharedState: MeshEngineRuntimeSharedState,
        routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
        sessionAndHopTransport: MeshEngineRuntimeSessionAndHopTransportPhase,
        outboundPreparationSupport: MeshEngineOutboundPreparationSupport,
    ): MeshEngineLargeTransferSupport {
        return MeshEngineLargeTransferSupport(
            config =
                MeshEngineLargeTransferConfig(
                    deliveryRetryDeadline = config.deliveryRetryDeadline,
                    ackSettlementTimeout = TRANSFER_ACK_SETTLEMENT_TIMEOUT,
                    ackIdleWindow = TRANSFER_ACK_IDLE_WINDOW,
                ),
            state = MeshEngineLargeTransferState(outboundTransfers = sharedState.outboundTransfers),
            routingSupport = routingAndTrust.routingSupport,
            dependencies =
                MeshEngineLargeTransferDependencies(
                    runtimeGate = runtimeSurface.runtimeGate,
                    currentTopologyVersion = { sharedState.routeCoordinator.topologyVersion.value },
                    deliveryRetryScheduler = sharedState.deliveryRetryScheduler,
                    prepareOutboundTransferSession =
                        outboundPreparationSupport::prepareOutboundTransferSession,
                    scheduleRetryDiagnostic = routingAndTrust.scheduleRetryDiagnostic,
                    sendTransferTowardsDestination =
                        createSendTransferTowardsDestination(
                            sharedState = sharedState,
                            sessionAndHopTransport = sessionAndHopTransport,
                        ),
                    setDiscoverySuspended = { suspended ->
                        val action =
                            if (suspended) {
                                "transfer.discoverySuspend"
                            } else {
                                "transfer.discoveryResume"
                            }
                        platformBridge.setDiscoverySuspended(action = action, suspended = suspended)
                    },
                    clearQueuedOutboundFrames = { peerId, action ->
                        platformBridge.clearQueuedOutboundFrames(peerId = peerId, action = action)
                    },
                ),
            emitDiagnostic = ::emitDiagnostic,
        )
    }

    private fun buildInboundSupport(
        sharedState: MeshEngineRuntimeSharedState,
        routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
        sessionAndHopTransport: MeshEngineRuntimeSessionAndHopTransportPhase,
        messageDeliverySupport: MeshEngineMessageDeliverySupport,
        transferSupport: MeshEngineTransferSupport,
    ): MeshEngineInboundSupport {
        return MeshEngineInboundSupport(
            localIdentity = localIdentity,
            sessionRegistry = sharedState.sessionRegistry,
            routingContext =
                MeshEngineInboundRoutingContext(
                    routeCoordinator = sharedState.routeCoordinator,
                    routingSupport = routingAndTrust.routingSupport,
                ),
            transport =
                MeshEngineInboundTransport(
                    emitHopSessionFailed =
                        sessionAndHopTransport.hopTransportSupport::emitHopSessionFailed,
                    decryptHopPayload =
                        sessionAndHopTransport.hopTransportSupport::decryptHopPayload,
                ),
            messageCallbacks =
                MeshEngineInboundMessageCallbacks(
                    captureHardRunToken = runtimeSurface.runtimeGate::captureHardRunToken,
                    forwardMessageToNextHop =
                        sessionAndHopTransport.peerFlowSupport::forwardMessageToNextHop,
                    deliverInnerEnvelope = messageDeliverySupport::deliverInnerEnvelope,
                ),
            transferCallbacks =
                MeshEngineInboundTransferCallbacks(
                    handleTransferStart = transferSupport::handleTransferStart,
                    handleTransferChunk = transferSupport::handleTransferChunk,
                    handleTransferAck = transferSupport::handleTransferAck,
                    handleTransferComplete = transferSupport::handleTransferComplete,
                    handleTransferAbort = transferSupport::handleTransferAbort,
                ),
        )
    }

    private fun createSendTransferTowardsDestination(
        sharedState: MeshEngineRuntimeSharedState,
        sessionAndHopTransport: MeshEngineRuntimeSessionAndHopTransportPhase,
    ): suspend (
        PeerId, ch.trancee.meshlink.wire.WireFrame, String, MeshEngineHardRunToken?,
    ) -> Boolean {
        return { peerId, frame, action, hardRunToken ->
            if (hardRunToken != null) {
                sessionAndHopTransport.peerFlowSupport.sendTransferTowardsDestination(
                    destinationPeerId = peerId,
                    frame = frame,
                    action = action,
                    hardRunToken = hardRunToken,
                )
            } else {
                val nextHopPeerId = sharedState.routeCoordinator.nextHopFor(peerId) ?: peerId
                sessionAndHopTransport.hopTransportSupport.sendEncryptedWireFrame(
                    peerId = nextHopPeerId,
                    frame = frame,
                    action = action,
                )
            }
        }
    }

    private fun buildTransportAndFacadeOperations(
        context: AssemblyContext
    ): MeshEngineRuntimeFacadeOperationsPhase {
        val sharedState = context.sharedState
        val routingAndTrust = context.routingAndTrust
        val sessionAndHopTransport = context.sessionAndHopTransport
        val handshake = context.handshake
        val transferAndInbound = context.transferAndInbound
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
        return platformBridge.send(
            frame =
                OutboundFrame(
                    peerId = peerId,
                    payload = frame.encode(),
                    preferredMode = preferredMode,
                ),
            action = action,
        )
    }

    private fun scheduleRetryDiagnostic(
        routingSupport: MeshEngineRoutingSupport,
        peerId: PeerId,
        priority: DeliveryPriority,
    ): Unit {
        emitDiagnostic(
            code = DiagnosticCode.NO_ROUTE_AVAILABLE,
            severity = DiagnosticSeverity.WARN,
            stage = "delivery.noRoute",
            peerSuffix = peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
            reason = DiagnosticReason.DELIVERY_FAILURE,
            metadata =
                routingSupport.peerRouteMetadata(
                    peerId,
                    metadata =
                        mapOf(
                            "priority" to priority.name,
                            "retryDeadlineMs" to
                                config.deliveryRetryDeadline.inWholeMilliseconds.toString(),
                            "retryBackoffBaseMs" to INITIAL_BACKOFF.inWholeMilliseconds.toString(),
                        ),
                ),
        )
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
        runtimeSurface.emitDiagnostic(
            code = code,
            severity = severity,
            stage = stage,
            peerSuffix = peerSuffix,
            reason = reason,
            metadata = metadata,
        )
    }

    private class AssemblyContext {
        lateinit var sharedState: MeshEngineRuntimeSharedState
        lateinit var routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase
        lateinit var sessionAndHopTransport: MeshEngineRuntimeSessionAndHopTransportPhase
        lateinit var handshake: MeshEngineRuntimeHandshakePhase
        lateinit var transferAndInbound: MeshEngineRuntimeTransferAndInboundPhase
        lateinit var facadeOperations: MeshEngineRuntimeFacadeOperationsPhase
    }
}

private const val MAX_SUPPORTED_PAYLOAD_BYTES: Int = 64 * 1024
private const val INLINE_MESSAGE_PAYLOAD_BYTES: Int = 1_024
private const val LARGE_INLINE_SEND_TRANSPORT_BUDGET_BYTES: Int = 16 * 1024
private const val HIGH_PRIORITY_TTL_MILLIS: Int = 45 * 60 * 1_000
private const val NORMAL_PRIORITY_TTL_MILLIS: Int = 15 * 60 * 1_000
private const val LOW_PRIORITY_TTL_MILLIS: Int = 5 * 60 * 1_000
private val TRANSFER_ACK_SETTLEMENT_TIMEOUT = 1_500.milliseconds
private val TRANSFER_ACK_IDLE_WINDOW = 100.milliseconds
private val HANDSHAKE_TIMEOUT = 3.seconds
private val INITIAL_BACKOFF = 250.milliseconds
