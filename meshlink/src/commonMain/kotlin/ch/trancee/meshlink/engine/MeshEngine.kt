package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.ForgetPeerResult
import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.PauseResult
import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.ResumeResult
import ch.trancee.meshlink.api.SendFailureReason
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
import ch.trancee.meshlink.platform.PlatformPermissionDeniedException
import ch.trancee.meshlink.power.PowerPolicy
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class MeshEngine
private constructor(
    private val config: MeshLinkConfig,
    private val platformContext: Any?,
    private val localIdentity: LocalIdentity,
    secureStorage: SecureStorage,
    private val bleTransport: BleTransport? = null,
    private val diagnosticSink: DiagnosticSink? = null,
) : MeshLinkApi {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engineClock = TimeSource.Monotonic.markNow()
    private val trustStore = TofuTrustStore(secureStorage)
    private val presenceTracker = PeerPresenceTracker()
    private val routeCoordinator = RouteCoordinator(localIdentity.peerId)
    private val deliveryRetryScheduler = DeliveryRetryScheduler(routeCoordinator.topologyVersion)
    private val powerPolicyController =
        PowerPolicyController(configuredMode = config.powerMode, region = config.regulatoryRegion)
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
    private var currentPowerPolicy: PowerPolicy =
        powerPolicyController.currentPolicy(nowMillis = 0L)
    private var transportCollectionJob: Job? = null
    private val hopSessions: MutableMap<String, HopSession> = linkedMapOf()
    private val pendingInitiatorHandshakes: MutableMap<String, PendingInitiatorHandshake> =
        linkedMapOf()
    private val pendingResponderHandshakes: MutableMap<String, PendingResponderHandshake> =
        linkedMapOf()
    private val outboundTransfers: MutableMap<String, OutboundTransferSession> = linkedMapOf()
    private val inboundTransfers: MutableMap<String, InboundTransferSession> = linkedMapOf()
    private val relayTransfers: MutableMap<String, RelayTransferSession> = linkedMapOf()
    private val sequenceGenerator = MeshEngineSequenceGenerator(localIdentity)
    private val powerPolicyNowMillis: () -> Long = { engineClock.elapsedNow().inWholeMilliseconds }
    private val ensureTransportCollector: () -> Unit =
        fun() {
            if (bleTransport == null || transportCollectionJob != null) {
                return
            }
            transportCollectionJob =
                coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    bleTransport.events.collect { event ->
                        transportSupport.handleTransportEvent(event)
                    }
                }
        }
    private val ttlMillisFor: (DeliveryPriority) -> Int = { priority ->
        when (priority) {
            DeliveryPriority.HIGH -> 45 * 60 * 1_000
            DeliveryPriority.NORMAL -> 15 * 60 * 1_000
            DeliveryPriority.LOW -> 5 * 60 * 1_000
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
            state =
                MeshEngineSessionState(
                    hopSessions = hopSessions,
                    pendingInitiatorHandshakes = pendingInitiatorHandshakes,
                ),
            handshakeTimeout = HANDSHAKE_TIMEOUT,
            callbacks =
                MeshEngineSessionCallbacks(
                    hasTransport = { bleTransport != null },
                    sendDirectWireFrame = { peerId, frame, action, preferredMode ->
                        sendDirectWireFrame(peerId, frame, action, preferredMode)
                    },
                    emitHopSessionFailed = { peerId, stage, reason, metadata ->
                        emitDiagnostic(
                            code = DiagnosticCode.HOP_SESSION_FAILED,
                            severity = DiagnosticSeverity.WARN,
                            stage = stage,
                            peerSuffix = peerId.value.takeLast(6),
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
                    maximumPayloadBytesPerDelivery = { peerId ->
                        bleTransport?.maximumPayloadBytesPerDelivery(peerId)
                    },
                ),
        )
    private val handshakeState =
        MeshEngineHandshakeState(
            hopSessions = hopSessions,
            pendingInitiatorHandshakes = pendingInitiatorHandshakes,
            pendingResponderHandshakes = pendingResponderHandshakes,
        )
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

    private val mutableState: MutableStateFlow<MeshLinkState> =
        MutableStateFlow(MeshLinkState.Uninitialized)
    private val mutablePeerEvents: MutableSharedFlow<PeerEvent> =
        MutableSharedFlow(extraBufferCapacity = 16)
    private val mutableDiagnostics: MutableSharedFlow<DiagnosticEvent> =
        MutableSharedFlow(extraBufferCapacity = 32)
    private val mutableMessages: MutableSharedFlow<InboundMessage> =
        MutableSharedFlow(extraBufferCapacity = 16)
    private val messageDeliverySupport =
        MeshEngineMessageDeliverySupport(
            localIdentity = localIdentity,
            trustSupport = trustSupport,
            mutableMessages = mutableMessages,
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
                        runPlatformCall(action) { bleTransport?.setDiscoverySuspended(suspended) }
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
                        runPlatformCall(action) { bleTransport?.setDiscoverySuspended(suspended) }
                    },
                    clearQueuedOutboundFrames = { peerId, action ->
                        runPlatformCall(action) { bleTransport?.clearQueuedOutboundFrames(peerId) }
                    },
                ),
            emitDiagnostic = ::emitDiagnostic,
        )
    private val inboundSupport =
        MeshEngineInboundSupport(
            localIdentity = localIdentity,
            hopSessions = hopSessions,
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
                    mutablePeerEvents = mutablePeerEvents,
                    hopSessions = hopSessions,
                    pendingInitiatorHandshakes = pendingInitiatorHandshakes,
                    pendingResponderHandshakes = pendingResponderHandshakes,
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

    override val state: StateFlow<MeshLinkState> = mutableState.asStateFlow()
    override val peerEvents: Flow<PeerEvent> = mutablePeerEvents.asSharedFlow()
    override val diagnosticEvents: Flow<DiagnosticEvent> = mutableDiagnostics.asSharedFlow()
    override val messages: Flow<InboundMessage> = mutableMessages.asSharedFlow()

    override suspend fun start(): StartResult {
        if (mutableState.value === MeshLinkState.Running) {
            return StartResult.AlreadyRunning
        }
        ensureTransportCollector()
        currentPowerPolicy = powerPolicyController.onMeshStarted(powerPolicyNowMillis())
        runPlatformCall("updatePowerPolicy") { bleTransport?.updatePowerPolicy(currentPowerPolicy) }
        runPlatformCall("start") { bleTransport?.start() }
        mutableState.value = MeshLinkState.Running
        emitDiagnostic(
            code = DiagnosticCode.MESH_STARTED,
            severity = DiagnosticSeverity.INFO,
            stage = "lifecycle.start",
            reason = DiagnosticReason.STATE_CHANGE,
        )
        return StartResult.Started
    }

    override suspend fun pause(): PauseResult {
        if (mutableState.value === MeshLinkState.Paused) {
            return PauseResult.AlreadyPaused
        }
        runPlatformCall("pause") { bleTransport?.pause() }
        mutableState.value = MeshLinkState.Paused
        emitDiagnostic(
            code = DiagnosticCode.MESH_PAUSED,
            severity = DiagnosticSeverity.INFO,
            stage = "lifecycle.pause",
            reason = DiagnosticReason.STATE_CHANGE,
        )
        return PauseResult.Paused
    }

    override suspend fun resume(): ResumeResult {
        if (mutableState.value === MeshLinkState.Running) {
            return ResumeResult.AlreadyRunning
        }
        ensureTransportCollector()
        currentPowerPolicy = powerPolicyController.currentPolicy(powerPolicyNowMillis())
        runPlatformCall("updatePowerPolicy") { bleTransport?.updatePowerPolicy(currentPowerPolicy) }
        runPlatformCall("resume") { bleTransport?.resume() }
        mutableState.value = MeshLinkState.Running
        emitDiagnostic(
            code = DiagnosticCode.MESH_RESUMED,
            severity = DiagnosticSeverity.INFO,
            stage = "lifecycle.resume",
            reason = DiagnosticReason.STATE_CHANGE,
        )
        return ResumeResult.Resumed
    }

    override suspend fun stop(): StopResult {
        if (mutableState.value === MeshLinkState.Stopped) {
            return StopResult.AlreadyStopped
        }
        runPlatformCall("stop") { bleTransport?.stop() }
        transportCollectionJob?.cancel()
        transportCollectionJob = null
        hopSessions.clear()
        pendingInitiatorHandshakes.clear()
        pendingResponderHandshakes.clear()
        outboundTransfers.clear()
        inboundTransfers.clear()
        relayTransfers.clear()
        mutableState.value = MeshLinkState.Stopped
        emitDiagnostic(
            code = DiagnosticCode.MESH_STOPPED,
            severity = DiagnosticSeverity.INFO,
            stage = "lifecycle.stop",
            reason = DiagnosticReason.STATE_CHANGE,
        )
        return StopResult.Stopped
    }

    override suspend fun send(
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
    ): SendResult {
        val isPayloadTooLarge = payload.size > MAX_SUPPORTED_PAYLOAD_BYTES
        val transportUnavailable =
            mutableState.value !== MeshLinkState.Running || bleTransport == null
        val shouldUseInlineDelivery =
            payload.size <= INLINE_MESSAGE_PAYLOAD_BYTES ||
                peerFlowSupport.shouldAttemptLargeInlineSend(peerId)

        return when {
            isPayloadTooLarge -> {
                emitDiagnostic(
                    code = DiagnosticCode.SIZE_LIMIT_REJECTED,
                    severity = DiagnosticSeverity.WARN,
                    stage = "delivery.send",
                    peerSuffix = peerId.value.takeLast(6),
                    reason = DiagnosticReason.SIZE_LIMIT,
                    metadata = mapOf("payloadBytes" to payload.size.toString()),
                )
                SendResult.NotSent(SendFailureReason.PAYLOAD_TOO_LARGE)
            }
            transportUnavailable -> {
                scheduleRetryDiagnostic(peerId, priority)
                SendResult.NotSent(SendFailureReason.UNREACHABLE)
            }
            shouldUseInlineDelivery ->
                inlineSendSupport.sendInlinePayload(
                    peerId = peerId,
                    payload = payload,
                    priority = priority,
                )
            else ->
                largeTransferSupport.sendLargePayload(
                    peerId = peerId,
                    payload = payload,
                    priority = priority,
                )
        }
    }

    override suspend fun forgetPeer(peerId: PeerId): ForgetPeerResult {
        val existingTrust = trustStore.read(peerId.value) ?: return ForgetPeerResult.NotFound
        trustStore.delete(peerId.value)
        hopSessions.remove(peerId.value)
        pendingInitiatorHandshakes
            .remove(peerId.value)
            ?.sessionDeferred
            ?.complete(SessionEstablishmentOutcome.Unreachable)
        pendingResponderHandshakes.remove(peerId.value)
        routingSupport.dispatchMutation(
            mutation = routeCoordinator.onPeerDisconnected(peerId),
            stage = "trust.forgetPeer",
            removalCode = DiagnosticCode.ROUTE_RETRACTED,
            metadata =
                mapOf(
                    "forgottenPeerId" to peerId.value,
                    "firstSeenAtEpochMillis" to existingTrust.firstSeenAtEpochMillis.toString(),
                ),
        )
        if (presenceTracker.onPeerDisconnected(peerId)) {
            mutablePeerEvents.emit(PeerEvent.Lost(peerId))
        }
        return ForgetPeerResult.Forgotten
    }

    override fun updateBattery(level: Float, isCharging: Boolean): Unit {
        val clampedLevel = level.coerceIn(0f, 1f)
        val policy =
            powerPolicyController.onBatterySnapshot(
                level = clampedLevel,
                isCharging = isCharging,
                nowMillis = powerPolicyNowMillis(),
            )
        currentPowerPolicy = policy
        if (bleTransport != null) {
            coroutineScope.launch {
                runPlatformCall("updatePowerPolicy") { bleTransport.updatePowerPolicy(policy) }
            }
        }
        emitDiagnostic(
            code = DiagnosticCode.POWER_MODE_CHANGED,
            severity = DiagnosticSeverity.INFO,
            stage = "power.updateBattery",
            reason = DiagnosticReason.POWER_CHANGE,
            metadata =
                powerPolicyMetadata(policy = policy, level = clampedLevel, isCharging = isCharging),
        )
    }

    private suspend fun sendDirectWireFrame(
        peerId: PeerId,
        frame: DirectWireFrame,
        action: String,
        preferredMode: TransportMode? = null,
    ): TransportSendResult {
        val transport =
            bleTransport ?: return TransportSendResult.Dropped("BLE transport is unavailable")
        return runPlatformCall(action) {
            transport.send(
                OutboundFrame(
                    peerId = peerId,
                    payload = frame.encode(),
                    preferredMode = preferredMode,
                )
            )
        }
    }

    private fun emitDiagnostic(
        code: DiagnosticCode,
        severity: DiagnosticSeverity,
        stage: String,
        peerSuffix: String? = null,
        reason: DiagnosticReason? = null,
        metadata: Map<String, String> = emptyMap(),
    ): Unit {
        val event =
            DiagnosticEvent(
                code = code,
                severity = severity,
                stage = stage,
                peerSuffix = peerSuffix,
                reason = reason,
                metadata = metadata,
            )
        mutableDiagnostics.tryEmit(event)
        diagnosticSink?.emit(event)
    }

    private suspend fun <T> runPlatformCall(action: String, block: suspend () -> T): T {
        return try {
            block()
        } catch (exception: PlatformPermissionDeniedException) {
            throw MeshLinkException.PermissionDenied(
                message = "Platform transport denied permission during $action",
                cause = exception,
            )
        } catch (exception: Throwable) {
            throw MeshLinkException.PlatformFailure(
                message = "Platform transport failed during $action",
                cause = exception,
            )
        }
    }

    internal companion object {
        private const val MAX_SUPPORTED_PAYLOAD_BYTES: Int = 64 * 1024
        private const val INLINE_MESSAGE_PAYLOAD_BYTES: Int = 1_024
        private const val LARGE_INLINE_SEND_TRANSPORT_BUDGET_BYTES: Int = 16 * 1024
        private const val TRANSFER_CHUNK_PAYLOAD_BYTES: Int = 392
        private val TRANSFER_ACK_SETTLEMENT_TIMEOUT = 1_500.milliseconds
        private val TRANSFER_ACK_IDLE_WINDOW = 100.milliseconds
        private val HANDSHAKE_TIMEOUT = 1.seconds
        private val INITIAL_BACKOFF = 250.milliseconds

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
