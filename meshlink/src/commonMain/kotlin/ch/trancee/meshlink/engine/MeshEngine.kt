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

internal object MeshEngine {

    private interface MeshEngineGraphOperations {
        suspend fun start(): StartResult

        suspend fun pause(): PauseResult

        suspend fun resume(): ResumeResult

        suspend fun stop(): StopResult

        suspend fun send(peerId: PeerId, payload: ByteArray, priority: DeliveryPriority): SendResult

        suspend fun forgetPeer(peerId: PeerId): ForgetPeerResult

        fun updateBattery(level: Float, isCharging: Boolean): Unit
    }

    private class MeshEngineRuntime(
        private val publishedSurface: MeshEnginePublishedRuntimeSurface,
        private val graph: MeshEngineGraphOperations,
    ) : MeshLinkApi {
        override val state: StateFlow<MeshLinkState> = publishedSurface.state
        override val peerEvents: Flow<PeerEvent> = publishedSurface.peerEvents
        override val diagnosticEvents: Flow<DiagnosticEvent> = publishedSurface.diagnosticEvents
        override val messages: Flow<InboundMessage> = publishedSurface.messages

        override suspend fun start(): StartResult {
            return graph.start()
        }

        override suspend fun pause(): PauseResult {
            return graph.pause()
        }

        override suspend fun resume(): ResumeResult {
            return graph.resume()
        }

        override suspend fun stop(): StopResult {
            return graph.stop()
        }

        override suspend fun send(
            peerId: PeerId,
            payload: ByteArray,
            priority: DeliveryPriority,
        ): SendResult {
            return graph.send(peerId = peerId, payload = payload, priority = priority)
        }

        override suspend fun forgetPeer(peerId: PeerId): ForgetPeerResult {
            return graph.forgetPeer(peerId)
        }

        override fun updateBattery(level: Float, isCharging: Boolean): Unit {
            graph.updateBattery(level = level, isCharging = isCharging)
        }
    }

    private class RuntimeGraph(
        private val config: MeshLinkConfig,
        private val localIdentity: LocalIdentity,
        secureStorage: SecureStorage,
        private val coroutineScope: CoroutineScope,
        private val platformBridge: MeshEnginePlatformBridge,
        private val runtimeSurface: MeshEngineCompatibilityRuntimeSurface,
    ) : MeshEngineGraphOperations {
        private val trustStore = TofuTrustStore(secureStorage)
        private val lifecycleSupport: MeshEngineLifecycleSupport
        private val sendSupport: MeshEngineSendSupport
        private val peerForgetSupport: MeshEnginePeerForgetSupport

        init {
            val context = AssemblyContext()
            buildSharedState(context)
            buildRoutingAndTrust(context)
            buildSessionAndHopTransport(context)
            buildHandshake(context)
            buildTransferAndInbound(context)
            buildTransportAndFacadeOperations(context)
            lifecycleSupport = context.lifecycleSupport
            sendSupport = context.sendSupport
            peerForgetSupport = context.peerForgetSupport
        }

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

        private fun buildSharedState(context: AssemblyContext): Unit {
            context.presenceTracker = PeerPresenceTracker()
            context.routeCoordinator = RouteCoordinator(localIdentity.peerId)
            context.deliveryRetryScheduler =
                DeliveryRetryScheduler(context.routeCoordinator.topologyVersion)
            context.powerPolicyController =
                PowerPolicyController(
                    configuredMode = config.powerMode,
                    region = config.regulatoryRegion,
                )
            context.sessionRegistry = MeshEngineSessionRegistry()
            context.outboundTransfers = linkedMapOf()
            context.inboundTransfers = linkedMapOf()
            context.relayTransfers = linkedMapOf()
            context.sequenceGenerator = MeshEngineSequenceGenerator(localIdentity)
            val engineClock = TimeSource.Monotonic.markNow()
            context.powerPolicyNowMillis = { engineClock.elapsedNow().inWholeMilliseconds }
            context.ttlMillisFor = { priority ->
                when (priority) {
                    DeliveryPriority.HIGH -> HIGH_PRIORITY_TTL_MILLIS
                    DeliveryPriority.NORMAL -> NORMAL_PRIORITY_TTL_MILLIS
                    DeliveryPriority.LOW -> LOW_PRIORITY_TTL_MILLIS
                }
            }
        }

        private fun buildRoutingAndTrust(context: AssemblyContext): Unit {
            context.routingSupport =
                MeshEngineRoutingSupport(
                    routeCoordinator = context.routeCoordinator,
                    coroutineScope = coroutineScope,
                    emitDiagnostic = ::emitDiagnostic,
                    sendEncryptedWireFrame = { peerId, frame, action ->
                        context.hopTransportSupport.sendEncryptedWireFrame(peerId, frame, action)
                    },
                )
            context.trustSupport =
                MeshEngineTrustSupport(
                    localIdentity = localIdentity,
                    trustStore = trustStore,
                    emitDiagnostic = ::emitDiagnostic,
                )
            context.scheduleRetryDiagnostic = { peerId, priority ->
                scheduleRetryDiagnostic(context.routingSupport, peerId, priority)
            }
        }

        private fun buildSessionAndHopTransport(context: AssemblyContext): Unit {
            context.sessionSupport =
                MeshEngineSessionSupport(
                    localIdentity = localIdentity,
                    state = MeshEngineSessionState(sessionRegistry = context.sessionRegistry),
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
                                    peerSuffix =
                                        peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                                    reason = reason,
                                    metadata =
                                        context.routingSupport.peerRouteMetadata(
                                            peerId,
                                            metadata = metadata,
                                        ),
                                )
                            },
                        ),
                )
            context.hopTransportSupport =
                MeshEngineHopTransportSupport(
                    localIdentity = localIdentity,
                    routingSupport = context.routingSupport,
                    establishedHopSession = context.sessionSupport::establishedHopSession,
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
            context.peerFlowSupport =
                MeshEnginePeerFlowSupport(
                    localIdentity = localIdentity,
                    context =
                        MeshEnginePeerFlowContext(
                            routeCoordinator = context.routeCoordinator,
                            coroutineScope = coroutineScope,
                        ),
                    config =
                        MeshEnginePeerFlowConfig(
                            largeInlineTransportBudgetBytes =
                                LARGE_INLINE_SEND_TRANSPORT_BUDGET_BYTES
                        ),
                    callbacks =
                        MeshEnginePeerFlowCallbacks(
                            sendEncryptedWireFrame =
                                context.hopTransportSupport::sendEncryptedWireFrame,
                            ensureHopSession = context.sessionSupport::ensureHopSession,
                            maximumPayloadBytesPerDelivery =
                                platformBridge::maximumPayloadBytesPerDelivery,
                            emitDiagnostic = ::emitDiagnostic,
                            peerRouteMetadata = { peerId, metadata ->
                                context.routingSupport.peerRouteMetadata(
                                    peerId,
                                    metadata = metadata,
                                )
                            },
                        ),
                )
        }

        private fun buildHandshake(context: AssemblyContext): Unit {
            context.handshakeState =
                MeshEngineHandshakeState(sessionRegistry = context.sessionRegistry)
            context.handshakeRoutingContext =
                MeshEngineHandshakeRoutingContext(
                    routeCoordinator = context.routeCoordinator,
                    routingSupport = context.routingSupport,
                )
            context.handshakeCallbacks =
                MeshEngineHandshakeCallbacks(
                    sendDirectWireFrame = { peerId, frame, action ->
                        sendDirectWireFrame(peerId = peerId, frame = frame, action = action)
                    },
                    emitHopSessionEstablished =
                        context.hopTransportSupport::emitHopSessionEstablished,
                    emitHopSessionFailed = context.hopTransportSupport::emitHopSessionFailed,
                    promoteTemporaryPeer = { temporaryPeerId, canonicalPeerId ->
                        runCatching {
                                platformBridge.promoteTemporaryPeer(
                                    temporaryPeerId,
                                    canonicalPeerId,
                                )
                            }
                            .getOrElse { Unit }
                    },
                )
            context.initiatorHandshakeSupport =
                MeshEngineInitiatorHandshakeSupport(
                    localIdentity = localIdentity,
                    trustSupport = context.trustSupport,
                    state = context.handshakeState,
                    routingContext = context.handshakeRoutingContext,
                    callbacks = context.handshakeCallbacks,
                )
            context.responderHandshakeSupport =
                MeshEngineResponderHandshakeSupport(
                    localIdentity = localIdentity,
                    trustSupport = context.trustSupport,
                    state = context.handshakeState,
                    routingContext = context.handshakeRoutingContext,
                    callbacks = context.handshakeCallbacks,
                )
        }

        private fun buildTransferAndInbound(context: AssemblyContext): Unit {
            context.messageDeliverySupport =
                MeshEngineMessageDeliverySupport(
                    localIdentity = localIdentity,
                    trustSupport = context.trustSupport,
                    mutableMessages = runtimeSurface.mutableMessages,
                    emitHopSessionFailed = context.hopTransportSupport::emitHopSessionFailed,
                    emitDiagnostic = ::emitDiagnostic,
                )
            context.outboundPreparationSupport =
                MeshEngineOutboundPreparationSupport(
                    localIdentity = localIdentity,
                    trustStore = trustStore,
                    state =
                        MeshEngineOutboundPreparationState(
                            outboundTransfers = context.outboundTransfers
                        ),
                    routingContext =
                        MeshEngineOutboundPreparationRoutingContext(
                            routeCoordinator = context.routeCoordinator,
                            routingSupport = context.routingSupport,
                        ),
                    callbacks =
                        MeshEngineOutboundPreparationCallbacks(
                            createMessageId = context.sequenceGenerator::createMessageId,
                            createTransferId = context.sequenceGenerator::createTransferId,
                            emitTransferEncryptFailure = { peerId, cause ->
                                context.hopTransportSupport.emitHopSessionFailed(
                                    peerId = peerId,
                                    stage = "transfer.encrypt",
                                    reason = DiagnosticReason.TRUST_FAILURE,
                                    metadata = mapOf("cause" to cause),
                                )
                            },
                        ),
                    emitDiagnostic = ::emitDiagnostic,
                )
            context.inlineSendSupport =
                MeshEngineInlineSendSupport(
                    localIdentity = localIdentity,
                    config =
                        MeshEngineInlineConfig(
                            deliveryRetryDeadline = config.deliveryRetryDeadline,
                            inlineMessagePayloadBytes = INLINE_MESSAGE_PAYLOAD_BYTES,
                        ),
                    routingContext =
                        MeshEngineInlineRoutingContext(
                            routeCoordinator = context.routeCoordinator,
                            routingSupport = context.routingSupport,
                        ),
                    dependencies =
                        MeshEngineInlineDependencies(
                            deliveryRetryScheduler = context.deliveryRetryScheduler,
                            ensureHopSession = context.sessionSupport::ensureHopSession,
                            sendEncryptedDirectWireFrame =
                                context.hopTransportSupport::sendEncryptedDirectWireFrame,
                            resolveRecipientTrust =
                                context.outboundPreparationSupport::resolveRecipientTrust,
                            scheduleRetryDiagnostic = context.scheduleRetryDiagnostic,
                            setDiscoverySuspended = { suspended ->
                                val action =
                                    if (suspended) {
                                        "inline.discoverySuspend"
                                    } else {
                                        "inline.discoveryResume"
                                    }
                                platformBridge.setDiscoverySuspended(
                                    action = action,
                                    suspended = suspended,
                                )
                            },
                            emitHopSessionFailed = context.hopTransportSupport::emitHopSessionFailed,
                        ),
                    callbacks =
                        MeshEngineInlineCallbacks(
                            emitDiagnostic = ::emitDiagnostic,
                            createMessageId = context.sequenceGenerator::createMessageId,
                            ttlMillisFor = context.ttlMillisFor,
                        ),
                )
            context.transferSupport =
                MeshEngineTransferSupport(
                    state =
                        MeshEngineTransferState(
                            outboundTransfers = context.outboundTransfers,
                            inboundTransfers = context.inboundTransfers,
                            relayTransfers = context.relayTransfers,
                        ),
                    routingSupport = context.routingSupport,
                    callbacks =
                        MeshEngineTransferCallbacks(
                            isLocalPeerId = context.peerFlowSupport::isLocalPeerId,
                            sendEncryptedWireFrame =
                                context.hopTransportSupport::sendEncryptedWireFrame,
                            sendTransferTowardsDestination =
                                context.peerFlowSupport::sendTransferTowardsDestination,
                            deliverInnerEnvelope =
                                context.messageDeliverySupport::deliverInnerEnvelope,
                        ),
                    emitDiagnostic = ::emitDiagnostic,
                )
            context.largeTransferSupport =
                MeshEngineLargeTransferSupport(
                    config =
                        MeshEngineLargeTransferConfig(
                            deliveryRetryDeadline = config.deliveryRetryDeadline,
                            ackSettlementTimeout = TRANSFER_ACK_SETTLEMENT_TIMEOUT,
                            ackIdleWindow = TRANSFER_ACK_IDLE_WINDOW,
                        ),
                    state =
                        MeshEngineLargeTransferState(outboundTransfers = context.outboundTransfers),
                    routingSupport = context.routingSupport,
                    dependencies =
                        MeshEngineLargeTransferDependencies(
                            currentTopologyVersion = {
                                context.routeCoordinator.topologyVersion.value
                            },
                            deliveryRetryScheduler = context.deliveryRetryScheduler,
                            prepareOutboundTransferSession =
                                context.outboundPreparationSupport::prepareOutboundTransferSession,
                            scheduleRetryDiagnostic = context.scheduleRetryDiagnostic,
                            sendTransferTowardsDestination =
                                context.peerFlowSupport::sendTransferTowardsDestination,
                            setDiscoverySuspended = { suspended ->
                                val action =
                                    if (suspended) {
                                        "transfer.discoverySuspend"
                                    } else {
                                        "transfer.discoveryResume"
                                    }
                                platformBridge.setDiscoverySuspended(
                                    action = action,
                                    suspended = suspended,
                                )
                            },
                            clearQueuedOutboundFrames = { peerId, action ->
                                platformBridge.clearQueuedOutboundFrames(
                                    peerId = peerId,
                                    action = action,
                                )
                            },
                        ),
                    emitDiagnostic = ::emitDiagnostic,
                )
            context.inboundSupport =
                MeshEngineInboundSupport(
                    localIdentity = localIdentity,
                    sessionRegistry = context.sessionRegistry,
                    routingContext =
                        MeshEngineInboundRoutingContext(
                            routeCoordinator = context.routeCoordinator,
                            routingSupport = context.routingSupport,
                        ),
                    transport =
                        MeshEngineInboundTransport(
                            emitHopSessionFailed =
                                context.hopTransportSupport::emitHopSessionFailed,
                            decryptHopPayload = context.hopTransportSupport::decryptHopPayload,
                        ),
                    messageCallbacks =
                        MeshEngineInboundMessageCallbacks(
                            forwardMessageToNextHop =
                                context.peerFlowSupport::forwardMessageToNextHop,
                            deliverInnerEnvelope =
                                context.messageDeliverySupport::deliverInnerEnvelope,
                        ),
                    transferCallbacks =
                        MeshEngineInboundTransferCallbacks(
                            handleTransferStart = context.transferSupport::handleTransferStart,
                            handleTransferChunk = context.transferSupport::handleTransferChunk,
                            handleTransferAck = context.transferSupport::handleTransferAck,
                            handleTransferComplete =
                                context.transferSupport::handleTransferComplete,
                            handleTransferAbort = context.transferSupport::handleTransferAbort,
                        ),
                )
        }

        private fun buildTransportAndFacadeOperations(context: AssemblyContext): Unit {
            context.transportSupport =
                MeshEngineTransportSupport(
                    peerState =
                        MeshEngineTransportPeerState(
                            presenceTracker = context.presenceTracker,
                            mutablePeerEvents = runtimeSurface.mutablePeerEvents,
                            sessionRegistry = context.sessionRegistry,
                        ),
                    routingContext =
                        MeshEngineTransportRoutingContext(
                            routeCoordinator = context.routeCoordinator,
                            routingSupport = context.routingSupport,
                        ),
                    callbacks =
                        MeshEngineTransportCallbacks(
                            prewarmHopSession = context.peerFlowSupport::prewarmHopSession,
                            handleHandshakeMessage1 =
                                context.responderHandshakeSupport::handleHandshakeMessage1,
                            handleHandshakeMessage2 =
                                context.initiatorHandshakeSupport::handleHandshakeMessage2,
                            handleHandshakeMessage3 =
                                context.responderHandshakeSupport::handleHandshakeMessage3,
                            handleEncryptedDataFrame =
                                context.inboundSupport::handleEncryptedDataFrame,
                        ),
                    emitDiagnostic = ::emitDiagnostic,
                )
            context.transportCollector =
                MeshEngineTransportCollector(
                    coroutineScope = coroutineScope,
                    transportEvents = { platformBridge.events },
                    handleTransportEvent = context.transportSupport::handleTransportEvent,
                )
            context.lifecycleState =
                MeshEngineLifecycleState(
                    mutableState = runtimeSurface.mutableState,
                    sessionRegistry = context.sessionRegistry,
                    outboundTransfers = context.outboundTransfers,
                    inboundTransfers = context.inboundTransfers,
                    relayTransfers = context.relayTransfers,
                    currentPowerPolicy = context.powerPolicyController.currentPolicy(nowMillis = 0L),
                )
            context.lifecycleSupport =
                MeshEngineLifecycleSupport(
                    powerPolicyController = context.powerPolicyController,
                    powerPolicyNowMillis = context.powerPolicyNowMillis,
                    state = context.lifecycleState,
                    callbacks =
                        MeshEngineLifecycleCallbacks(
                            ensureTransportCollector = context.transportCollector::ensureStarted,
                            stopTransportCollector = context.transportCollector::stop,
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
            context.sendSupport =
                MeshEngineSendSupport(
                    config =
                        MeshEngineSendConfig(
                            maxSupportedPayloadBytes = MAX_SUPPORTED_PAYLOAD_BYTES,
                            inlineMessagePayloadBytes = INLINE_MESSAGE_PAYLOAD_BYTES,
                        ),
                    callbacks =
                        MeshEngineSendCallbacks(
                            isMeshRunning = {
                                runtimeSurface.mutableState.value === MeshLinkState.Running
                            },
                            hasTransport = { platformBridge.hasTransport },
                            shouldAttemptLargeInlineSend =
                                context.peerFlowSupport::shouldAttemptLargeInlineSend,
                            sendInlinePayload = { peerId, payload, priority ->
                                context.inlineSendSupport.sendInlinePayload(
                                    peerId = peerId,
                                    payload = payload,
                                    priority = priority,
                                )
                            },
                            sendLargePayload = { peerId, payload, priority ->
                                context.largeTransferSupport.sendLargePayload(
                                    peerId = peerId,
                                    payload = payload,
                                    priority = priority,
                                )
                            },
                            scheduleRetryDiagnostic = context.scheduleRetryDiagnostic,
                            emitDiagnostic = ::emitDiagnostic,
                        ),
                )
            context.peerForgetSupport =
                MeshEnginePeerForgetSupport(
                    callbacks =
                        MeshEnginePeerForgetCallbacks(
                            readFirstSeenAtEpochMillis = { peerId ->
                                trustStore.read(peerId.value)?.firstSeenAtEpochMillis
                            },
                            deleteTrust = { peerId -> trustStore.delete(peerId.value) },
                            clearPeer = context.sessionRegistry::clearPeer,
                            dispatchPeerDisconnected = { peerId, metadata ->
                                context.routingSupport.dispatchMutation(
                                    mutation = context.routeCoordinator.onPeerDisconnected(peerId),
                                    stage = "trust.forgetPeer",
                                    removalCode = DiagnosticCode.ROUTE_RETRACTED,
                                    metadata = metadata,
                                )
                            },
                            markPeerDisconnected = context.presenceTracker::onPeerDisconnected,
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
                                "retryBackoffBaseMs" to
                                    INITIAL_BACKOFF.inWholeMilliseconds.toString(),
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
            lateinit var presenceTracker: PeerPresenceTracker
            lateinit var routeCoordinator: RouteCoordinator
            lateinit var deliveryRetryScheduler: DeliveryRetryScheduler
            lateinit var powerPolicyController: PowerPolicyController
            lateinit var sessionRegistry: MeshEngineSessionRegistry
            lateinit var outboundTransfers: MutableMap<String, OutboundTransferSession>
            lateinit var inboundTransfers: MutableMap<String, InboundTransferSession>
            lateinit var relayTransfers: MutableMap<String, RelayTransferSession>
            lateinit var sequenceGenerator: MeshEngineSequenceGenerator
            var powerPolicyNowMillis: () -> Long = { error("powerPolicyNowMillis not initialized") }
            var ttlMillisFor: (DeliveryPriority) -> Int = { error("ttlMillisFor not initialized") }
            lateinit var routingSupport: MeshEngineRoutingSupport
            lateinit var trustSupport: MeshEngineTrustSupport
            var scheduleRetryDiagnostic: (PeerId, DeliveryPriority) -> Unit = { _, _ ->
                error("scheduleRetryDiagnostic not initialized")
            }
            lateinit var sessionSupport: MeshEngineSessionSupport
            lateinit var hopTransportSupport: MeshEngineHopTransportSupport
            lateinit var peerFlowSupport: MeshEnginePeerFlowSupport
            lateinit var handshakeState: MeshEngineHandshakeState
            lateinit var handshakeRoutingContext: MeshEngineHandshakeRoutingContext
            lateinit var handshakeCallbacks: MeshEngineHandshakeCallbacks
            lateinit var initiatorHandshakeSupport: MeshEngineInitiatorHandshakeSupport
            lateinit var responderHandshakeSupport: MeshEngineResponderHandshakeSupport
            lateinit var messageDeliverySupport: MeshEngineMessageDeliverySupport
            lateinit var outboundPreparationSupport: MeshEngineOutboundPreparationSupport
            lateinit var inlineSendSupport: MeshEngineInlineSendSupport
            lateinit var transferSupport: MeshEngineTransferSupport
            lateinit var largeTransferSupport: MeshEngineLargeTransferSupport
            lateinit var inboundSupport: MeshEngineInboundSupport
            lateinit var transportSupport: MeshEngineTransportSupport
            lateinit var transportCollector: MeshEngineTransportCollector
            lateinit var lifecycleState: MeshEngineLifecycleState
            lateinit var lifecycleSupport: MeshEngineLifecycleSupport
            lateinit var sendSupport: MeshEngineSendSupport
            lateinit var peerForgetSupport: MeshEnginePeerForgetSupport
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

    @Suppress("LongParameterList", "UnusedParameter")
    internal fun create(
        config: MeshLinkConfig,
        platformContext: Any? = null,
        localIdentity: LocalIdentity = LocalIdentity.fromAppId(config.appId),
        secureStorage: SecureStorage = InMemorySecureStorage(),
        bleTransport: BleTransport? = null,
        diagnosticSink: DiagnosticSink? = null,
    ): MeshLinkApi {
        val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtimeSurface = MeshEngineRuntimeSurface(diagnosticSink = diagnosticSink)
        val publishedSurface: MeshEnginePublishedRuntimeSurface = runtimeSurface
        val graph: MeshEngineGraphOperations =
            RuntimeGraph(
                config = config,
                localIdentity = localIdentity,
                secureStorage = secureStorage,
                coroutineScope = coroutineScope,
                platformBridge = MeshEnginePlatformBridge(bleTransport),
                runtimeSurface = runtimeSurface,
            )
        return MeshEngineRuntime(publishedSurface = publishedSurface, graph = graph)
    }
}
