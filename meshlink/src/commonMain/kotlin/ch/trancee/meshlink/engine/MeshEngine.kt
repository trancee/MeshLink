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
import ch.trancee.meshlink.storage.InMemorySecureStorage
import ch.trancee.meshlink.storage.SecureStorage
import ch.trancee.meshlink.transfer.InboundTransferSession
import ch.trancee.meshlink.transfer.OutboundTransferSession
import ch.trancee.meshlink.transfer.RelayTransferSession
import ch.trancee.meshlink.transport.BleTransport
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

internal class MeshEngine
private constructor(
    private val config: MeshLinkConfig,
    private val platformContext: Any?,
    private val localIdentity: LocalIdentity,
    secureStorage: SecureStorage,
    private val bleTransport: BleTransport? = null,
    diagnosticSink: DiagnosticSink? = null,
) : MeshLinkApi {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engineClock = TimeSource.Monotonic.markNow()
    private val trustStore = TofuTrustStore(secureStorage)
    private val presenceTracker = PeerPresenceTracker()
    private val routeCoordinator = RouteCoordinator(localIdentity.peerId)
    private val deliveryRetryScheduler = DeliveryRetryScheduler(routeCoordinator.topologyVersion)
    private val powerPolicyController =
        PowerPolicyController(configuredMode = config.powerMode, region = config.regulatoryRegion)
    private val platformBridge = MeshEnginePlatformBridge(bleTransport)
    private val runtimeSurface = MeshEngineRuntimeSurface(diagnosticSink = diagnosticSink)
    private val publishedSurface: MeshEnginePublishedRuntimeSurface = runtimeSurface
    private val compatibilitySurface: MeshEngineCompatibilityRuntimeSurface = runtimeSurface
    private val routingSupport: MeshEngineRoutingSupport =
        MeshEngineRoutingSupport(
            routeCoordinator = routeCoordinator,
            coroutineScope = coroutineScope,
            emitDiagnostic = ::emitDiagnostic,
            sendEncryptedWireFrame = { peerId, frame, action ->
                hopTransportSupport.sendEncryptedWireFrame(peerId, frame, action)
            },
        )
    private val trustSupport =
        MeshEngineTrustSupport(
            localIdentity = localIdentity,
            trustStore = trustStore,
            emitDiagnostic = ::emitDiagnostic,
        )
    private val sessionRegistry = MeshEngineSessionRegistry()
    private val outboundTransfers: MutableMap<String, OutboundTransferSession> = linkedMapOf()
    private val inboundTransfers: MutableMap<String, InboundTransferSession> = linkedMapOf()
    private val relayTransfers: MutableMap<String, RelayTransferSession> = linkedMapOf()
    private val sequenceGenerator = MeshEngineSequenceGenerator(localIdentity)
    private val powerPolicyNowMillis: () -> Long = { engineClock.elapsedNow().inWholeMilliseconds }
    private val ttlMillisFor: (DeliveryPriority) -> Int = { priority ->
        when (priority) {
            DeliveryPriority.HIGH -> HIGH_PRIORITY_TTL_MILLIS
            DeliveryPriority.NORMAL -> NORMAL_PRIORITY_TTL_MILLIS
            DeliveryPriority.LOW -> LOW_PRIORITY_TTL_MILLIS
        }
    }
    private val scheduleRetryDiagnostic: (PeerId, DeliveryPriority) -> Unit = { peerId, priority ->
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
    private val sessionSupport =
        MeshEngineSessionSupport(
            localIdentity = localIdentity,
            state = MeshEngineSessionState(sessionRegistry = sessionRegistry),
            handshakeTimeout = HANDSHAKE_TIMEOUT,
            callbacks =
                MeshEngineSessionCallbacks(
                    hasTransport = { platformBridge.hasTransport },
                    sendDirectWireFrame = { peerId, frame, action, preferredMode ->
                        sendDirectWireFrame(peerId, frame, action, preferredMode)
                    },
                    emitHopSessionFailed = { peerId, stage, reason, metadata ->
                        emitDiagnostic(
                            code = DiagnosticCode.HOP_SESSION_FAILED,
                            severity = DiagnosticSeverity.WARN,
                            stage = stage,
                            peerSuffix = peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                            reason = reason,
                            metadata = routingSupport.peerRouteMetadata(peerId, metadata = metadata),
                        )
                    },
                ),
        )
    private val hopTransportSupport: MeshEngineHopTransportSupport =
        MeshEngineHopTransportSupport(
            localIdentity = localIdentity,
            routingSupport = routingSupport,
            establishedHopSession = sessionSupport::establishedHopSession,
            sendDirectWireFrame = { peerId, frame, action, preferredMode ->
                sendDirectWireFrame(peerId, frame, action, preferredMode)
            },
            emitDiagnostic = ::emitDiagnostic,
        )
    private val peerFlowSupport =
        MeshEnginePeerFlowSupport(
            localIdentity = localIdentity,
            context =
                MeshEnginePeerFlowContext(
                    routeCoordinator = routeCoordinator,
                    coroutineScope = coroutineScope,
                ),
            config =
                MeshEnginePeerFlowConfig(
                    largeInlineTransportBudgetBytes = LARGE_INLINE_SEND_TRANSPORT_BUDGET_BYTES
                ),
            callbacks =
                MeshEnginePeerFlowCallbacks(
                    sendEncryptedWireFrame = hopTransportSupport::sendEncryptedWireFrame,
                    ensureHopSession = sessionSupport::ensureHopSession,
                    maximumPayloadBytesPerDelivery = platformBridge::maximumPayloadBytesPerDelivery,
                    emitDiagnostic = ::emitDiagnostic,
                    peerRouteMetadata = { peerId, metadata ->
                        routingSupport.peerRouteMetadata(peerId, metadata = metadata)
                    },
                ),
        )
    private val handshakeState = MeshEngineHandshakeState(sessionRegistry = sessionRegistry)
    private val handshakeRoutingContext =
        MeshEngineHandshakeRoutingContext(
            routeCoordinator = routeCoordinator,
            routingSupport = routingSupport,
        )
    private val handshakeCallbacks =
        MeshEngineHandshakeCallbacks(
            sendDirectWireFrame = { peerId, frame, action ->
                sendDirectWireFrame(peerId = peerId, frame = frame, action = action)
            },
            emitHopSessionEstablished = hopTransportSupport::emitHopSessionEstablished,
            emitHopSessionFailed = hopTransportSupport::emitHopSessionFailed,
            promoteTemporaryPeer = { temporaryPeerId, canonicalPeerId ->
                runCatching {
                        platformBridge.promoteTemporaryPeer(temporaryPeerId, canonicalPeerId)
                    }
                    .getOrElse { Unit }
            },
        )
    private val initiatorHandshakeSupport =
        MeshEngineInitiatorHandshakeSupport(
            localIdentity = localIdentity,
            trustSupport = trustSupport,
            state = handshakeState,
            routingContext = handshakeRoutingContext,
            callbacks = handshakeCallbacks,
        )
    private val responderHandshakeSupport =
        MeshEngineResponderHandshakeSupport(
            localIdentity = localIdentity,
            trustSupport = trustSupport,
            state = handshakeState,
            routingContext = handshakeRoutingContext,
            callbacks = handshakeCallbacks,
        )

    private val messageDeliverySupport =
        MeshEngineMessageDeliverySupport(
            localIdentity = localIdentity,
            trustSupport = trustSupport,
            mutableMessages = compatibilitySurface.mutableMessages,
            emitHopSessionFailed = hopTransportSupport::emitHopSessionFailed,
            emitDiagnostic = ::emitDiagnostic,
        )
    private val outboundPreparationSupport =
        MeshEngineOutboundPreparationSupport(
            localIdentity = localIdentity,
            trustStore = trustStore,
            state = MeshEngineOutboundPreparationState(outboundTransfers = outboundTransfers),
            routingContext =
                MeshEngineOutboundPreparationRoutingContext(
                    routeCoordinator = routeCoordinator,
                    routingSupport = routingSupport,
                ),
            callbacks =
                MeshEngineOutboundPreparationCallbacks(
                    createMessageId = sequenceGenerator::createMessageId,
                    createTransferId = sequenceGenerator::createTransferId,
                    emitTransferEncryptFailure = { peerId, cause ->
                        hopTransportSupport.emitHopSessionFailed(
                            peerId = peerId,
                            stage = "transfer.encrypt",
                            reason = DiagnosticReason.TRUST_FAILURE,
                            metadata = mapOf("cause" to cause),
                        )
                    },
                ),
            emitDiagnostic = ::emitDiagnostic,
        )
    private val inlineSendSupport =
        MeshEngineInlineSendSupport(
            localIdentity = localIdentity,
            config =
                MeshEngineInlineConfig(
                    deliveryRetryDeadline = config.deliveryRetryDeadline,
                    inlineMessagePayloadBytes = INLINE_MESSAGE_PAYLOAD_BYTES,
                ),
            routingContext =
                MeshEngineInlineRoutingContext(
                    routeCoordinator = routeCoordinator,
                    routingSupport = routingSupport,
                ),
            dependencies =
                MeshEngineInlineDependencies(
                    deliveryRetryScheduler = deliveryRetryScheduler,
                    ensureHopSession = sessionSupport::ensureHopSession,
                    sendEncryptedDirectWireFrame =
                        hopTransportSupport::sendEncryptedDirectWireFrame,
                    resolveRecipientTrust = outboundPreparationSupport::resolveRecipientTrust,
                    scheduleRetryDiagnostic = scheduleRetryDiagnostic,
                    setDiscoverySuspended = { suspended ->
                        val action =
                            if (suspended) "inline.discoverySuspend" else "inline.discoveryResume"
                        platformBridge.setDiscoverySuspended(action = action, suspended = suspended)
                    },
                    emitHopSessionFailed = hopTransportSupport::emitHopSessionFailed,
                ),
            callbacks =
                MeshEngineInlineCallbacks(
                    emitDiagnostic = ::emitDiagnostic,
                    createMessageId = sequenceGenerator::createMessageId,
                    ttlMillisFor = ttlMillisFor,
                ),
        )
    private val transferSupport =
        MeshEngineTransferSupport(
            state =
                MeshEngineTransferState(
                    outboundTransfers = outboundTransfers,
                    inboundTransfers = inboundTransfers,
                    relayTransfers = relayTransfers,
                ),
            routingSupport = routingSupport,
            callbacks =
                MeshEngineTransferCallbacks(
                    isLocalPeerId = peerFlowSupport::isLocalPeerId,
                    sendEncryptedWireFrame = hopTransportSupport::sendEncryptedWireFrame,
                    sendTransferTowardsDestination =
                        peerFlowSupport::sendTransferTowardsDestination,
                    deliverInnerEnvelope = messageDeliverySupport::deliverInnerEnvelope,
                ),
            emitDiagnostic = ::emitDiagnostic,
        )
    private val largeTransferSupport =
        MeshEngineLargeTransferSupport(
            config =
                MeshEngineLargeTransferConfig(
                    deliveryRetryDeadline = config.deliveryRetryDeadline,
                    ackSettlementTimeout = TRANSFER_ACK_SETTLEMENT_TIMEOUT,
                    ackIdleWindow = TRANSFER_ACK_IDLE_WINDOW,
                ),
            state = MeshEngineLargeTransferState(outboundTransfers = outboundTransfers),
            routingSupport = routingSupport,
            dependencies =
                MeshEngineLargeTransferDependencies(
                    currentTopologyVersion = { routeCoordinator.topologyVersion.value },
                    deliveryRetryScheduler = deliveryRetryScheduler,
                    prepareOutboundTransferSession =
                        outboundPreparationSupport::prepareOutboundTransferSession,
                    scheduleRetryDiagnostic = scheduleRetryDiagnostic,
                    sendTransferTowardsDestination =
                        peerFlowSupport::sendTransferTowardsDestination,
                    setDiscoverySuspended = { suspended ->
                        val action =
                            if (suspended) "transfer.discoverySuspend"
                            else "transfer.discoveryResume"
                        platformBridge.setDiscoverySuspended(action = action, suspended = suspended)
                    },
                    clearQueuedOutboundFrames = { peerId, action ->
                        platformBridge.clearQueuedOutboundFrames(peerId = peerId, action = action)
                    },
                ),
            emitDiagnostic = ::emitDiagnostic,
        )
    private val inboundSupport =
        MeshEngineInboundSupport(
            localIdentity = localIdentity,
            sessionRegistry = sessionRegistry,
            routingContext =
                MeshEngineInboundRoutingContext(
                    routeCoordinator = routeCoordinator,
                    routingSupport = routingSupport,
                ),
            transport =
                MeshEngineInboundTransport(
                    emitHopSessionFailed = hopTransportSupport::emitHopSessionFailed,
                    decryptHopPayload = hopTransportSupport::decryptHopPayload,
                ),
            messageCallbacks =
                MeshEngineInboundMessageCallbacks(
                    forwardMessageToNextHop = peerFlowSupport::forwardMessageToNextHop,
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
    private val transportSupport =
        MeshEngineTransportSupport(
            peerState =
                MeshEngineTransportPeerState(
                    presenceTracker = presenceTracker,
                    mutablePeerEvents = compatibilitySurface.mutablePeerEvents,
                    sessionRegistry = sessionRegistry,
                ),
            routingContext =
                MeshEngineTransportRoutingContext(
                    routeCoordinator = routeCoordinator,
                    routingSupport = routingSupport,
                ),
            callbacks =
                MeshEngineTransportCallbacks(
                    prewarmHopSession = peerFlowSupport::prewarmHopSession,
                    handleHandshakeMessage1 = responderHandshakeSupport::handleHandshakeMessage1,
                    handleHandshakeMessage2 = initiatorHandshakeSupport::handleHandshakeMessage2,
                    handleHandshakeMessage3 = responderHandshakeSupport::handleHandshakeMessage3,
                    handleEncryptedDataFrame = inboundSupport::handleEncryptedDataFrame,
                ),
            emitDiagnostic = ::emitDiagnostic,
        )
    private val transportCollector =
        MeshEngineTransportCollector(
            coroutineScope = coroutineScope,
            transportEvents = { platformBridge.events },
            handleTransportEvent = transportSupport::handleTransportEvent,
        )
    private val lifecycleState =
        MeshEngineLifecycleState(
            mutableState = compatibilitySurface.mutableState,
            sessionRegistry = sessionRegistry,
            outboundTransfers = outboundTransfers,
            inboundTransfers = inboundTransfers,
            relayTransfers = relayTransfers,
            currentPowerPolicy = powerPolicyController.currentPolicy(nowMillis = 0L),
        )
    private val lifecycleSupport =
        MeshEngineLifecycleSupport(
            powerPolicyController = powerPolicyController,
            powerPolicyNowMillis = powerPolicyNowMillis,
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
    private val sendSupport =
        MeshEngineSendSupport(
            config =
                MeshEngineSendConfig(
                    maxSupportedPayloadBytes = MAX_SUPPORTED_PAYLOAD_BYTES,
                    inlineMessagePayloadBytes = INLINE_MESSAGE_PAYLOAD_BYTES,
                ),
            callbacks =
                MeshEngineSendCallbacks(
                    isMeshRunning = { publishedSurface.state.value === MeshLinkState.Running },
                    hasTransport = { platformBridge.hasTransport },
                    shouldAttemptLargeInlineSend = peerFlowSupport::shouldAttemptLargeInlineSend,
                    sendInlinePayload = { peerId, payload, priority ->
                        inlineSendSupport.sendInlinePayload(
                            peerId = peerId,
                            payload = payload,
                            priority = priority,
                        )
                    },
                    sendLargePayload = { peerId, payload, priority ->
                        largeTransferSupport.sendLargePayload(
                            peerId = peerId,
                            payload = payload,
                            priority = priority,
                        )
                    },
                    scheduleRetryDiagnostic = scheduleRetryDiagnostic,
                    emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
                        emitDiagnostic(
                            code = code,
                            severity = severity,
                            stage = stage,
                            peerSuffix = peerSuffix,
                            reason = reason,
                            metadata = metadata,
                        )
                    },
                ),
        )
    private val peerForgetSupport =
        MeshEnginePeerForgetSupport(
            callbacks =
                MeshEnginePeerForgetCallbacks(
                    readFirstSeenAtEpochMillis = { peerId ->
                        trustStore.read(peerId.value)?.firstSeenAtEpochMillis
                    },
                    deleteTrust = { peerId -> trustStore.delete(peerId.value) },
                    clearPeer = sessionRegistry::clearPeer,
                    dispatchPeerDisconnected = { peerId, metadata ->
                        routingSupport.dispatchMutation(
                            mutation = routeCoordinator.onPeerDisconnected(peerId),
                            stage = "trust.forgetPeer",
                            removalCode = DiagnosticCode.ROUTE_RETRACTED,
                            metadata = metadata,
                        )
                    },
                    markPeerDisconnected = presenceTracker::onPeerDisconnected,
                    emitPeerLost = { peerId ->
                        compatibilitySurface.mutablePeerEvents.emit(PeerEvent.Lost(peerId))
                    },
                )
        )

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

    @Suppress("LongParameterList")
    private fun emitDiagnostic(
        code: DiagnosticCode,
        severity: DiagnosticSeverity,
        stage: String,
        peerSuffix: String? = null,
        reason: DiagnosticReason? = null,
        metadata: Map<String, String> = emptyMap(),
    ): Unit {
        compatibilitySurface.emitDiagnostic(
            code = code,
            severity = severity,
            stage = stage,
            peerSuffix = peerSuffix,
            reason = reason,
            metadata = metadata,
        )
    }

    internal companion object {
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

        @Suppress("LongParameterList")
        internal fun create(
            config: MeshLinkConfig,
            platformContext: Any? = null,
            localIdentity: LocalIdentity = LocalIdentity.fromAppId(config.appId),
            secureStorage: SecureStorage = InMemorySecureStorage(),
            bleTransport: BleTransport? = null,
            diagnosticSink: DiagnosticSink? = null,
        ): MeshLinkApi {
            return MeshEngine(
                config = config,
                platformContext = platformContext,
                localIdentity = localIdentity,
                secureStorage = secureStorage,
                bleTransport = bleTransport,
                diagnosticSink = diagnosticSink,
            )
        }
    }
}
