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
import ch.trancee.meshlink.crypto.MessageSealer
import ch.trancee.meshlink.crypto.NoiseXXHandshakeManager
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticEvent
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.diagnostics.DiagnosticSink
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.identity.hexContentEquals
import ch.trancee.meshlink.platform.PlatformPermissionDeniedException
import ch.trancee.meshlink.platform.currentEpochMillis
import ch.trancee.meshlink.power.PowerPolicy
import ch.trancee.meshlink.power.PowerPolicyController
import ch.trancee.meshlink.presence.PeerPresenceTracker
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.routing.RouteEntry
import ch.trancee.meshlink.storage.InMemorySecureStorage
import ch.trancee.meshlink.storage.SecureStorage
import ch.trancee.meshlink.transfer.InboundChunkAcceptance
import ch.trancee.meshlink.transfer.InboundTransferSession
import ch.trancee.meshlink.transfer.OutboundTransferSession
import ch.trancee.meshlink.transfer.RelayTransferSession
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.trust.TofuTrustStore
import ch.trancee.meshlink.trust.TrustRecord
import ch.trancee.meshlink.wire.WireCodec
import ch.trancee.meshlink.wire.WireFrame
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

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
    private val routingSupport =
        MeshEngineRoutingSupport(
            routeCoordinator = routeCoordinator,
            coroutineScope = coroutineScope,
            emitDiagnostic = ::emitDiagnostic,
            sendEncryptedWireFrame = ::sendEncryptedWireFrame,
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
    private var nextMessageSequenceNumber: Long = 1L
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
            emitHopSessionEstablished = ::emitHopSessionEstablished,
            emitHopSessionFailed = ::emitHopSessionFailed,
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
            emitHopSessionFailed = ::emitHopSessionFailed,
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
                    emitHopSessionFailed = ::emitHopSessionFailed,
                    decryptHopPayload = ::decryptHopPayload,
                ),
            messageCallbacks =
                MeshEngineInboundMessageCallbacks(
                    forwardMessageToNextHop = ::forwardMessageToNextHop,
                    deliverInnerEnvelope = messageDeliverySupport::deliverInnerEnvelope,
                ),
            transferCallbacks =
                MeshEngineInboundTransferCallbacks(
                    handleTransferStart = ::handleTransferStart,
                    handleTransferChunk = ::handleTransferChunk,
                    handleTransferAck = ::handleTransferAck,
                    handleTransferComplete = ::handleTransferComplete,
                    handleTransferAbort = ::handleTransferAbort,
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
                    prewarmHopSession = ::prewarmHopSession,
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
            payload.size <= INLINE_MESSAGE_PAYLOAD_BYTES || shouldAttemptLargeInlineSend(peerId)

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
                scheduleRetryDiagnostic(peerId = peerId, priority = priority)
                SendResult.NotSent(SendFailureReason.UNREACHABLE)
            }
            shouldUseInlineDelivery ->
                sendInlinePayload(peerId = peerId, payload = payload, priority = priority)
            else -> sendLargePayload(peerId = peerId, payload = payload, priority = priority)
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

    private fun ensureTransportCollector(): Unit {
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

    private data class InlineSessionResolution(
        val nextHopPeerId: PeerId,
        val session: HopSession?,
        val result: SendResult? = null,
    )

    private suspend fun sendInlinePayload(
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
    ): SendResult {
        val startedAt = TimeSource.Monotonic.markNow()
        var retryState =
            InlineRetryWakeupState(
                attempt = 0,
                topologyVersion = routeCoordinator.topologyVersion.value,
            )
        val suspendDiscoveryDuringSend = payload.size > INLINE_MESSAGE_PAYLOAD_BYTES

        if (suspendDiscoveryDuringSend) {
            runPlatformCall("inline.discoverySuspend") { bleTransport?.setDiscoverySuspended(true) }
        }

        try {
            while (startedAt.elapsedNow() < config.deliveryRetryDeadline) {
                val sendResult =
                    attemptInlineSend(peerId = peerId, payload = payload, priority = priority)
                if (!sendResult.isRetryableInlineFailure()) {
                    return sendResult
                }
                val nextRetryState =
                    awaitInlineRetryWakeup(
                        peerId = peerId,
                        retryState = retryState,
                        startedAt = startedAt,
                    ) ?: break
                retryState = nextRetryState
            }
            return unreachableInlineSendResult(peerId)
        } finally {
            if (suspendDiscoveryDuringSend) {
                runPlatformCall("inline.discoveryResume") {
                    bleTransport?.setDiscoverySuspended(false)
                }
            }
        }
    }

    private suspend fun attemptInlineSend(
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
    ): SendResult {
        val sessionResolution = resolveInlineSession(peerId = peerId, priority = priority)
        val resolvedResult = sessionResolution.result
        val session = sessionResolution.session

        return if (resolvedResult != null) {
            resolvedResult
        } else {
            val establishedSession = session ?: error("inline session resolution is required")
            val recipientTrust = resolveInlineRecipientTrust(peerId)
            if (recipientTrust == null) {
                SendResult.NotSent(SendFailureReason.TRUST_FAILURE)
            } else {
                val routedMessage =
                    buildInlineRoutedMessage(
                        peerId = peerId,
                        payload = payload,
                        priority = priority,
                        recipientTrust = recipientTrust,
                    )
                if (routedMessage == null) {
                    SendResult.NotSent(SendFailureReason.TRUST_FAILURE)
                } else {
                    dispatchInlineMessage(
                        peerId = peerId,
                        nextHopPeerId = sessionResolution.nextHopPeerId,
                        session = establishedSession,
                        routedMessage = routedMessage,
                        priority = priority,
                    )
                }
            }
        }
    }

    private suspend fun awaitInlineRetryWakeup(
        peerId: PeerId,
        retryState: InlineRetryWakeupState,
        startedAt: TimeMark,
    ): InlineRetryWakeupState? {
        emitDiagnostic(
            code = DiagnosticCode.DELIVERY_RETRY_SCHEDULED,
            severity = DiagnosticSeverity.WARN,
            stage = "delivery.retryScheduled",
            peerSuffix = peerId.value.takeLast(6),
            reason = DiagnosticReason.DELIVERY_RETRY,
            metadata =
                routingSupport.peerRouteMetadata(
                    peerId,
                    metadata = mapOf("attempt" to retryState.attempt.toString()),
                ),
        )

        val wakeupState =
            when (
                val wakeup =
                    deliveryRetryScheduler.awaitRetry(
                        attempt = retryState.attempt,
                        remainingBudget = config.deliveryRetryDeadline - startedAt.elapsedNow(),
                        lastObservedTopologyVersion = retryState.topologyVersion,
                    )
            ) {
                is RetryWakeup.DeadlineExpired -> null
                is RetryWakeup.TimerElapsed ->
                    InlineRetryWakeupState(
                        attempt = retryState.attempt + 1,
                        topologyVersion = wakeup.topologyVersion,
                    )
                is RetryWakeup.TopologyChanged ->
                    InlineRetryWakeupState(attempt = 0, topologyVersion = wakeup.topologyVersion)
            }
        if (wakeupState != null) {
            emitDiagnostic(
                code = DiagnosticCode.DELIVERY_RETRYING,
                severity = DiagnosticSeverity.WARN,
                stage = "delivery.retrying",
                peerSuffix = peerId.value.takeLast(6),
                reason = DiagnosticReason.DELIVERY_RETRY,
                metadata =
                    routingSupport.peerRouteMetadata(
                        peerId,
                        metadata = mapOf("attempt" to wakeupState.attempt.toString()),
                    ),
            )
        }
        return wakeupState
    }

    private suspend fun resolveInlineSession(
        peerId: PeerId,
        priority: DeliveryPriority,
    ): InlineSessionResolution {
        val nextHopPeerId = routeCoordinator.nextHopFor(peerId) ?: peerId
        return when (val sessionOutcome = ensureHopSession(nextHopPeerId)) {
            is SessionEstablishmentOutcome.Established ->
                InlineSessionResolution(
                    nextHopPeerId = nextHopPeerId,
                    session = sessionOutcome.session,
                )
            SessionEstablishmentOutcome.TrustFailure ->
                InlineSessionResolution(
                    nextHopPeerId = nextHopPeerId,
                    session = null,
                    result = SendResult.NotSent(SendFailureReason.TRUST_FAILURE),
                )
            SessionEstablishmentOutcome.Unreachable -> {
                scheduleRetryDiagnostic(peerId = peerId, priority = priority)
                InlineSessionResolution(
                    nextHopPeerId = nextHopPeerId,
                    session = null,
                    result = SendResult.NotSent(SendFailureReason.UNREACHABLE),
                )
            }
        }
    }

    private suspend fun resolveInlineRecipientTrust(peerId: PeerId): TrustRecord? {
        val recipientTrust = resolveRecipientTrust(peerId)
        if (recipientTrust == null) {
            emitDiagnostic(
                code = DiagnosticCode.TRUST_FAILURE,
                severity = DiagnosticSeverity.ERROR,
                stage = "delivery.send.noTrust",
                peerSuffix = peerId.value.takeLast(6),
                reason = DiagnosticReason.TRUST_FAILURE,
            )
        }
        return recipientTrust
    }

    private fun buildInlineRoutedMessage(
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
        recipientTrust: TrustRecord,
    ): WireFrame.Message? {
        val sealedPayload =
            runCatching {
                    MessageSealer.seal(
                        plaintext = payload,
                        senderIdentity = localIdentity,
                        recipientTrust = recipientTrust,
                    )
                }
                .getOrElse { exception ->
                    emitHopSessionFailed(
                        peerId = peerId,
                        stage = "delivery.send.encrypt",
                        reason = DiagnosticReason.TRUST_FAILURE,
                        metadata = mapOf("cause" to exception::class.simpleName.orEmpty()),
                    )
                    return null
                }

        val innerEnvelope =
            DirectMessageEnvelope(
                    senderPeerId = localIdentity.peerId,
                    senderFingerprintBytes = localIdentity.identityFingerprintBytes,
                    senderEd25519PublicKey = localIdentity.ed25519PublicKey,
                    senderX25519PublicKey = localIdentity.x25519PublicKey,
                    ciphertext = sealedPayload,
                )
                .encode()
        return WireFrame.Message(
            messageId = createMessageId(),
            originPeerId = localIdentity.peerId,
            destinationPeerId = peerId,
            priority = priority,
            ttlMillis = ttlMillisFor(priority),
            encryptedPayload = innerEnvelope,
        )
    }

    private suspend fun dispatchInlineMessage(
        peerId: PeerId,
        nextHopPeerId: PeerId,
        session: HopSession,
        routedMessage: WireFrame.Message,
        priority: DeliveryPriority,
    ): SendResult {
        return when (
            runCatching {
                    sendEncryptedDirectWireFrame(
                        peerId = nextHopPeerId,
                        session = session,
                        frame = routedMessage,
                        action = "send.data",
                    )
                }
                .getOrElse { exception ->
                    emitHopSessionFailed(
                        peerId = nextHopPeerId,
                        stage = "delivery.send.transportEncrypt",
                        reason = DiagnosticReason.DELIVERY_FAILURE,
                        metadata = mapOf("cause" to exception::class.simpleName.orEmpty()),
                    )
                    return SendResult.NotSent(SendFailureReason.UNREACHABLE)
                }
        ) {
            TransportSendResult.Delivered -> {
                emitDiagnostic(
                    code = DiagnosticCode.DELIVERY_SUCCEEDED,
                    severity = DiagnosticSeverity.INFO,
                    stage = "delivery.send",
                    peerSuffix = peerId.value.takeLast(6),
                    metadata = routingSupport.peerRouteMetadata(peerId),
                )
                SendResult.Sent
            }
            is TransportSendResult.Dropped -> {
                scheduleRetryDiagnostic(peerId = peerId, priority = priority)
                SendResult.NotSent(SendFailureReason.UNREACHABLE)
            }
        }
    }

    private fun unreachableInlineSendResult(peerId: PeerId): SendResult {
        emitDiagnostic(
            code = DiagnosticCode.DELIVERY_UNREACHABLE,
            severity = DiagnosticSeverity.ERROR,
            stage = "delivery.retryExpired",
            peerSuffix = peerId.value.takeLast(6),
            reason = DiagnosticReason.DELIVERY_FAILURE,
            metadata = routingSupport.peerRouteMetadata(peerId),
        )
        return SendResult.NotSent(SendFailureReason.UNREACHABLE)
    }

    private suspend fun resolveRecipientTrust(peerId: PeerId): TrustRecord? {
        val existingTrust = trustStore.read(peerId.value)
        val route = routeCoordinator.routeFor(peerId)

        return when {
            existingTrust != null -> existingTrust
            route == null -> null
            else -> learnRecipientTrustFromRoute(peerId = peerId, route = route)
        }
    }

    private suspend fun learnRecipientTrustFromRoute(
        peerId: PeerId,
        route: RouteEntry,
    ): TrustRecord {
        val learnedAtEpochMillis = currentEpochMillis()
        val learnedTrust =
            TrustRecord(
                peerIdValue = route.destinationPeerId.value,
                identityFingerprintBytes =
                    localIdentity.cryptoProvider.sha256(
                        route.ed25519PublicKey + route.x25519PublicKey
                    ),
                firstSeenAtEpochMillis = learnedAtEpochMillis,
                lastVerifiedAtEpochMillis = learnedAtEpochMillis,
                ed25519PublicKey = route.ed25519PublicKey,
                x25519PublicKey = route.x25519PublicKey,
            )
        trustStore.write(learnedTrust)
        emitDiagnostic(
            code = DiagnosticCode.TRUST_ESTABLISHED,
            severity = DiagnosticSeverity.INFO,
            stage = "trust.routeUpdate",
            peerSuffix = peerId.value.takeLast(6),
            reason = DiagnosticReason.STATE_CHANGE,
        )
        return learnedTrust
    }

    private suspend fun prepareOutboundTransferSession(
        peerId: PeerId,
        payload: ByteArray,
    ): OutboundTransferPreparation {
        val recipientTrust = resolveRecipientTrust(peerId)

        return if (recipientTrust == null) {
            OutboundTransferPreparation.PendingRoute
        } else {
            prepareResolvedOutboundTransferSession(
                peerId = peerId,
                payload = payload,
                recipientTrust = recipientTrust,
            )
        }
    }

    private suspend fun prepareResolvedOutboundTransferSession(
        peerId: PeerId,
        payload: ByteArray,
        recipientTrust: TrustRecord,
    ): OutboundTransferPreparation {
        val sealedPayload = sealOutboundTransferPayload(peerId, payload, recipientTrust)
        return if (sealedPayload == null) {
            OutboundTransferPreparation.Failed(SendResult.NotSent(SendFailureReason.TRUST_FAILURE))
        } else {
            val session = createOutboundTransferSession(peerId, sealedPayload)
            outboundTransfers[session.transferId] = session
            emitTransferStartedDiagnostic(peerId)
            OutboundTransferPreparation.Ready(session)
        }
    }

    private fun sealOutboundTransferPayload(
        peerId: PeerId,
        payload: ByteArray,
        recipientTrust: TrustRecord,
    ): ByteArray? {
        return runCatching {
                MessageSealer.seal(
                    plaintext = payload,
                    senderIdentity = localIdentity,
                    recipientTrust = recipientTrust,
                )
            }
            .getOrElse { exception ->
                emitHopSessionFailed(
                    peerId = peerId,
                    stage = "transfer.encrypt",
                    reason = DiagnosticReason.TRUST_FAILURE,
                    metadata = mapOf("cause" to exception::class.simpleName.orEmpty()),
                )
                null
            }
    }

    private fun createOutboundTransferSession(
        peerId: PeerId,
        sealedPayload: ByteArray,
    ): OutboundTransferSession {
        val innerEnvelope =
            DirectMessageEnvelope(
                    senderPeerId = localIdentity.peerId,
                    senderFingerprintBytes = localIdentity.identityFingerprintBytes,
                    senderEd25519PublicKey = localIdentity.ed25519PublicKey,
                    senderX25519PublicKey = localIdentity.x25519PublicKey,
                    ciphertext = sealedPayload,
                )
                .encode()
        return OutboundTransferSession.fromOwnedChunks(
            transferId = createTransferId(),
            messageId = createMessageId(),
            originPeerId = localIdentity.peerId,
            destinationPeerId = peerId,
            chunks = chunkTransferPayload(innerEnvelope, TRANSFER_CHUNK_PAYLOAD_BYTES),
            totalBytes = innerEnvelope.size,
            maxChunkPayloadBytes = TRANSFER_CHUNK_PAYLOAD_BYTES,
        )
    }

    private fun emitTransferStartedDiagnostic(peerId: PeerId): Unit {
        emitDiagnostic(
            code = DiagnosticCode.TRANSFER_STARTED,
            severity = DiagnosticSeverity.INFO,
            stage = "transfer.send.start",
            peerSuffix = peerId.value.takeLast(6),
            metadata = routingSupport.peerRouteMetadata(peerId),
        )
    }

    private suspend fun sendLargePayload(
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
    ): SendResult {
        val startedAt = TimeSource.Monotonic.markNow()
        var attempt = 0
        var topologyVersion = routeCoordinator.topologyVersion.value
        var activeSession: OutboundTransferSession? = null
        var lastRouteAvailable = false

        runPlatformCall("transfer.discoverySuspend") { bleTransport?.setDiscoverySuspended(true) }
        try {
            while (startedAt.elapsedNow() < config.deliveryRetryDeadline) {
                val loopResult =
                    advanceLargeTransferSendLoop(
                        activeSession = activeSession,
                        peerId = peerId,
                        payload = payload,
                        priority = priority,
                        startedAt = startedAt,
                    )
                val completedResult = loopResult.result
                if (completedResult != null) {
                    return completedResult
                }
                activeSession = loopResult.session
                lastRouteAvailable = loopResult.lastRouteAvailable

                if (loopResult.transferProgressObserved) {
                    attempt = 0
                } else {
                    val wakeupState =
                        awaitLargeTransferRetryWakeup(
                            peerId = peerId,
                            attempt = attempt,
                            topologyVersion = topologyVersion,
                            startedAt = startedAt,
                            noRouteRetry = !lastRouteAvailable,
                        ) ?: break
                    topologyVersion = wakeupState.topologyVersion
                    attempt = wakeupState.attempt
                }
            }
            return finishFailedLargeTransfer(
                activeSession = activeSession,
                peerId = peerId,
                lastRouteAvailable = lastRouteAvailable,
            )
        } finally {
            runPlatformCall("transfer.discoveryResume") {
                bleTransport?.setDiscoverySuspended(false)
            }
        }
    }

    private suspend fun advanceLargeTransferSendLoop(
        activeSession: OutboundTransferSession?,
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
        startedAt: TimeMark,
    ): LargeTransferLoopResult {
        val sessionResolution =
            resolveLargeTransferSession(
                activeSession = activeSession,
                peerId = peerId,
                payload = payload,
                priority = priority,
            )
        val resolvedResult = sessionResolution.result
        val session = sessionResolution.session

        return if (resolvedResult != null) {
            LargeTransferLoopResult(
                session = null,
                lastRouteAvailable = sessionResolution.lastRouteAvailable,
                result = resolvedResult,
            )
        } else if (session == null) {
            LargeTransferLoopResult(
                session = null,
                lastRouteAvailable = sessionResolution.lastRouteAvailable,
            )
        } else {
            val iterationResult =
                sendLargeTransferIteration(
                    session = session,
                    priority = priority,
                    startedAt = startedAt,
                )
            LargeTransferLoopResult(
                session = session,
                lastRouteAvailable = iterationResult.lastRouteAvailable,
                transferProgressObserved = iterationResult.transferProgressObserved,
                result = iterationResult.result,
            )
        }
    }

    private suspend fun resolveLargeTransferSession(
        activeSession: OutboundTransferSession?,
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
    ): LargeTransferSessionResolution {
        if (activeSession != null) {
            return LargeTransferSessionResolution(
                session = activeSession,
                lastRouteAvailable = true,
            )
        }

        return when (
            val preparation = prepareOutboundTransferSession(peerId = peerId, payload = payload)
        ) {
            OutboundTransferPreparation.PendingRoute -> {
                scheduleRetryDiagnostic(peerId = peerId, priority = priority)
                LargeTransferSessionResolution(session = null, lastRouteAvailable = false)
            }
            is OutboundTransferPreparation.Ready ->
                LargeTransferSessionResolution(
                    session = preparation.session,
                    lastRouteAvailable = true,
                )
            is OutboundTransferPreparation.Failed ->
                LargeTransferSessionResolution(
                    session = null,
                    lastRouteAvailable = true,
                    result = preparation.result,
                )
        }
    }

    private suspend fun sendLargeTransferIteration(
        session: OutboundTransferSession,
        priority: DeliveryPriority,
        startedAt: TimeMark,
    ): LargeTransferIterationResult {
        var routeAvailable =
            sendTransferTowardsDestination(
                session.destinationPeerId,
                session.asStartFrame(),
                "transfer.start",
            )
        var transferProgressObserved = false
        val result =
            if (session.isComplete()) {
                completeOutboundTransferSession(session)
            } else {
                session.forEachMissingChunkIndex { chunkIndex ->
                    routeAvailable =
                        sendTransferTowardsDestination(
                            session.destinationPeerId,
                            WireFrame.TransferChunk(
                                transferId = session.transferId,
                                chunkIndex = chunkIndex,
                                payload = session.chunks[chunkIndex],
                            ),
                            "transfer.chunk",
                        ) || routeAvailable
                }
                emitLargeTransferProgressDiagnostic(session)
                if (!routeAvailable) {
                    scheduleRetryDiagnostic(peerId = session.destinationPeerId, priority = priority)
                    null
                } else {
                    transferProgressObserved =
                        awaitLargeTransferAcknowledgementProgress(
                            session = session,
                            startedAt = startedAt,
                        )
                    if (session.isComplete()) completeOutboundTransferSession(session) else null
                }
            }

        return LargeTransferIterationResult(
            lastRouteAvailable = routeAvailable,
            transferProgressObserved = transferProgressObserved,
            result = result,
        )
    }

    private fun emitLargeTransferProgressDiagnostic(session: OutboundTransferSession): Unit {
        emitDiagnostic(
            code = DiagnosticCode.TRANSFER_PROGRESS,
            severity = DiagnosticSeverity.DEBUG,
            stage = "transfer.send.progress",
            peerSuffix = session.destinationPeerId.value.takeLast(6),
            metadata =
                routingSupport.peerRouteMetadata(
                    session.destinationPeerId,
                    metadata =
                        mapOf(
                            "ackedChunks" to session.acknowledgedChunkCount().toString(),
                            "totalChunks" to session.totalChunks.toString(),
                        ),
                ),
        )
    }

    private suspend fun awaitLargeTransferAcknowledgementProgress(
        session: OutboundTransferSession,
        startedAt: TimeMark,
    ): Boolean {
        val acknowledgedChunkCountBeforeSettlement = session.acknowledgedChunkCount()
        val acknowledgedChunkCountAfterSettlement =
            session.awaitAcknowledgementSettlement(
                maximumWait =
                    (config.deliveryRetryDeadline - startedAt.elapsedNow()).coerceAtMost(
                        TRANSFER_ACK_SETTLEMENT_TIMEOUT
                    ),
                idleWindow = TRANSFER_ACK_IDLE_WINDOW,
            )
        return acknowledgedChunkCountAfterSettlement > acknowledgedChunkCountBeforeSettlement
    }

    private suspend fun finishFailedLargeTransfer(
        activeSession: OutboundTransferSession?,
        peerId: PeerId,
        lastRouteAvailable: Boolean,
    ): SendResult {
        activeSession?.let { session ->
            outboundTransfers.remove(session.transferId)
            runPlatformCall("transfer.clearQueuedFramesOnFailure") {
                bleTransport?.clearQueuedOutboundFrames(session.destinationPeerId)
            }
        }
        return if (!lastRouteAvailable) {
            emitDiagnostic(
                code = DiagnosticCode.DELIVERY_UNREACHABLE,
                severity = DiagnosticSeverity.ERROR,
                stage = "transfer.retryExpired",
                peerSuffix = peerId.value.takeLast(6),
                reason = DiagnosticReason.DELIVERY_FAILURE,
                metadata = routingSupport.peerRouteMetadata(peerId),
            )
            SendResult.NotSent(SendFailureReason.UNREACHABLE)
        } else {
            emitDiagnostic(
                code = DiagnosticCode.TRANSFER_FAILED,
                severity = DiagnosticSeverity.ERROR,
                stage = "transfer.send.timeout",
                peerSuffix = peerId.value.takeLast(6),
                reason = DiagnosticReason.TRANSFER_FAILURE,
                metadata = routingSupport.peerRouteMetadata(peerId),
            )
            SendResult.NotSent(SendFailureReason.TRANSFER_TIMED_OUT)
        }
    }

    private suspend fun awaitLargeTransferRetryWakeup(
        peerId: PeerId,
        attempt: Int,
        topologyVersion: Long,
        startedAt: TimeMark,
        noRouteRetry: Boolean,
    ): LargeTransferRetryWakeupState? {
        if (noRouteRetry) {
            emitDiagnostic(
                code = DiagnosticCode.DELIVERY_RETRY_SCHEDULED,
                severity = DiagnosticSeverity.WARN,
                stage = "transfer.retryScheduled",
                peerSuffix = peerId.value.takeLast(6),
                reason = DiagnosticReason.DELIVERY_RETRY,
                metadata =
                    routingSupport.peerRouteMetadata(
                        peerId,
                        metadata = mapOf("attempt" to attempt.toString()),
                    ),
            )
        }

        val wakeupState =
            when (
                val wakeup =
                    deliveryRetryScheduler.awaitRetry(
                        attempt = attempt,
                        remainingBudget = config.deliveryRetryDeadline - startedAt.elapsedNow(),
                        lastObservedTopologyVersion = topologyVersion,
                    )
            ) {
                is RetryWakeup.DeadlineExpired -> null
                is RetryWakeup.TimerElapsed ->
                    LargeTransferRetryWakeupState(
                        attempt = attempt + 1,
                        topologyVersion = wakeup.topologyVersion,
                    )
                is RetryWakeup.TopologyChanged ->
                    LargeTransferRetryWakeupState(
                        attempt = 0,
                        topologyVersion = wakeup.topologyVersion,
                    )
            }
        if (noRouteRetry && wakeupState != null) {
            emitDiagnostic(
                code = DiagnosticCode.DELIVERY_RETRYING,
                severity = DiagnosticSeverity.WARN,
                stage = "transfer.retrying",
                peerSuffix = peerId.value.takeLast(6),
                reason = DiagnosticReason.DELIVERY_RETRY,
                metadata =
                    routingSupport.peerRouteMetadata(
                        peerId,
                        metadata = mapOf("attempt" to wakeupState.attempt.toString()),
                    ),
            )
        }
        return wakeupState
    }

    private suspend fun completeOutboundTransferSession(
        session: OutboundTransferSession
    ): SendResult {
        runPlatformCall("transfer.clearQueuedFrames") {
            bleTransport?.clearQueuedOutboundFrames(session.destinationPeerId)
        }
        sendTransferTowardsDestination(
            session.destinationPeerId,
            WireFrame.TransferComplete(session.transferId),
            "transfer.complete",
        )
        outboundTransfers.remove(session.transferId)
        emitDiagnostic(
            code = DiagnosticCode.TRANSFER_COMPLETED,
            severity = DiagnosticSeverity.INFO,
            stage = "transfer.send.complete",
            peerSuffix = session.destinationPeerId.value.takeLast(6),
            metadata = routingSupport.peerRouteMetadata(session.destinationPeerId),
        )
        return SendResult.Sent
    }

    private suspend fun sendTransferTowardsDestination(
        destinationPeerId: PeerId,
        frame: WireFrame,
        action: String,
    ): Boolean {
        val nextHopPeerId = routeCoordinator.nextHopFor(destinationPeerId) ?: destinationPeerId
        return sendEncryptedWireFrame(nextHopPeerId, frame, action)
    }

    private suspend fun handleTransferStart(peerId: PeerId, frame: WireFrame.TransferStart): Unit {
        if (isLocalPeerId(frame.destinationPeerId)) {
            val existingSession = inboundTransfers[frame.transferId]
            val inboundSession =
                if (existingSession != null) {
                    existingSession.upstreamPeerId = peerId
                    existingSession
                } else {
                    InboundTransferSession(
                            transferId = frame.transferId,
                            messageId = frame.messageId,
                            originPeerId = frame.originPeerId,
                            destinationPeerId = frame.destinationPeerId,
                            upstreamPeerId = peerId,
                            totalBytes = frame.totalBytes,
                            totalChunks = frame.totalChunks,
                            maxChunkPayloadBytes = frame.maxChunkPayloadBytes,
                        )
                        .also { session -> inboundTransfers[frame.transferId] = session }
                }
            val preparedAck = inboundSession.prepareAck()
            emitInboundTransferProgress(
                stage = "transfer.receive.start",
                peerId = peerId,
                session = inboundSession,
                metadata =
                    mapOf(
                        "existingSession" to (existingSession != null).toString(),
                        "receivedChunks" to preparedAck.receivedChunkCount.toString(),
                        "ackHighestContiguous" to preparedAck.highestContiguousAck.toString(),
                    ),
            )
            val ackSent = sendEncryptedWireFrame(peerId, preparedAck.frame, "transfer.ack.start")
            emitInboundTransferProgress(
                stage = "transfer.ack.start",
                peerId = peerId,
                session = inboundSession,
                metadata =
                    inboundAckMetadata(preparedAck, ackSent) +
                        mapOf("existingSession" to (existingSession != null).toString()),
            )
            return
        }

        val relaySession = relayTransfers[frame.transferId]
        if (relaySession != null) {
            relaySession.upstreamPeerId = peerId
        } else {
            relayTransfers[frame.transferId] =
                RelayTransferSession(
                    transferId = frame.transferId,
                    messageId = frame.messageId,
                    originPeerId = frame.originPeerId,
                    destinationPeerId = frame.destinationPeerId,
                    upstreamPeerId = peerId,
                )
        }
        sendTransferTowardsDestination(frame.destinationPeerId, frame, "transfer.forward.start")
    }

    private suspend fun handleTransferChunk(peerId: PeerId, frame: WireFrame.TransferChunk): Unit {
        val inboundSession = inboundTransfers[frame.transferId]
        if (inboundSession != null) {
            handleInboundTransferChunk(peerId = peerId, frame = frame, session = inboundSession)
            return
        }
        val relaySession = relayTransfers[frame.transferId] ?: return
        sendTransferTowardsDestination(
            relaySession.destinationPeerId,
            frame,
            "transfer.forward.chunk",
        )
    }

    private suspend fun handleInboundTransferChunk(
        peerId: PeerId,
        frame: WireFrame.TransferChunk,
        session: InboundTransferSession,
    ): Unit {
        val acceptance = session.acceptChunk(frame)
        emitInboundTransferProgress(
            stage = inboundTransferChunkStage(acceptance),
            peerId = peerId,
            session = session,
            metadata = inboundTransferChunkMetadata(acceptance),
        )
        acknowledgeInboundTransferChunkIfNeeded(
            peerId = peerId,
            frame = frame,
            session = session,
            acceptance = acceptance,
        )
        completeInboundTransferChunkIfNeeded(
            peerId = peerId,
            frame = frame,
            session = session,
            acceptance = acceptance,
        )
    }

    private suspend fun acknowledgeInboundTransferChunkIfNeeded(
        peerId: PeerId,
        frame: WireFrame.TransferChunk,
        session: InboundTransferSession,
        acceptance: InboundChunkAcceptance,
    ): Unit {
        if (!acceptance.shouldAcknowledge) {
            return
        }
        val preparedAck = session.prepareAck()
        val ackSent =
            sendEncryptedWireFrame(session.upstreamPeerId, preparedAck.frame, "transfer.ack.chunk")
        emitInboundTransferProgress(
            stage = "transfer.ack.chunk",
            peerId = peerId,
            session = session,
            metadata =
                inboundAckMetadata(preparedAck, ackSent) +
                    mapOf(
                        "triggerChunkIndex" to frame.chunkIndex.toString(),
                        "triggerDuplicateChunk" to acceptance.duplicateChunk.toString(),
                    ),
        )
    }

    private suspend fun completeInboundTransferChunkIfNeeded(
        peerId: PeerId,
        frame: WireFrame.TransferChunk,
        session: InboundTransferSession,
        acceptance: InboundChunkAcceptance,
    ): Unit {
        if (!acceptance.complete) {
            return
        }
        inboundTransfers.remove(frame.transferId)
        emitInboundTransferProgress(
            stage = "transfer.receive.complete",
            peerId = peerId,
            session = session,
            metadata =
                mapOf(
                    "complete" to "true",
                    "receivedChunks" to session.receivedChunkCount().toString(),
                    "triggerChunkIndex" to frame.chunkIndex.toString(),
                ),
        )
        messageDeliverySupport.deliverInnerEnvelope(
            immediatePeerId = peerId,
            originPeerId = session.originPeerId,
            encryptedPayload = session.assembledPayload(),
            priority = DeliveryPriority.NORMAL,
        )
    }

    private fun handleTransferAck(peerId: PeerId, frame: WireFrame.TransferAck): Unit {
        val outboundSession = outboundTransfers[frame.transferId]
        if (outboundSession != null) {
            outboundSession.markAcknowledged(frame)
            return
        }
        val relaySession = relayTransfers[frame.transferId] ?: return
        coroutineScope.launch {
            sendEncryptedWireFrame(relaySession.upstreamPeerId, frame, "transfer.forward.ack")
        }
    }

    private suspend fun handleTransferComplete(
        peerId: PeerId,
        frame: WireFrame.TransferComplete,
    ): Unit {
        val inboundSession = inboundTransfers.remove(frame.transferId)
        if (inboundSession != null) {
            emitInboundTransferProgress(
                stage = "transfer.receive.complete",
                peerId = peerId,
                session = inboundSession,
                metadata =
                    mapOf(
                        "complete" to inboundSession.isComplete().toString(),
                        "receivedChunks" to inboundSession.receivedChunkCount().toString(),
                    ),
            )
            if (inboundSession.isComplete()) {
                messageDeliverySupport.deliverInnerEnvelope(
                    immediatePeerId = peerId,
                    originPeerId = inboundSession.originPeerId,
                    encryptedPayload = inboundSession.assembledPayload(),
                    priority = DeliveryPriority.NORMAL,
                )
            }
            return
        }
        val relaySession = relayTransfers.remove(frame.transferId) ?: return
        sendTransferTowardsDestination(
            relaySession.destinationPeerId,
            frame,
            "transfer.forward.complete",
        )
    }

    private suspend fun handleTransferAbort(peerId: PeerId, frame: WireFrame.TransferAbort): Unit {
        inboundTransfers.remove(frame.transferId)
        val relaySession = relayTransfers.remove(frame.transferId)
        if (relaySession != null) {
            sendTransferTowardsDestination(
                relaySession.destinationPeerId,
                frame,
                "transfer.forward.abort",
            )
            return
        }
        val outboundSession = outboundTransfers.remove(frame.transferId)
        if (outboundSession != null) {
            emitDiagnostic(
                code = DiagnosticCode.TRANSFER_FAILED,
                severity = DiagnosticSeverity.ERROR,
                stage = "transfer.abort",
                peerSuffix = peerId.value.takeLast(6),
                reason = DiagnosticReason.TRANSFER_FAILURE,
                metadata = mapOf("reasonCode" to frame.reasonCode.toString()),
            )
        }
    }

    private fun emitInboundTransferProgress(
        stage: String,
        peerId: PeerId,
        session: InboundTransferSession,
        metadata: Map<String, String> = emptyMap(),
    ): Unit {
        emitDiagnostic(
            code = DiagnosticCode.TRANSFER_PROGRESS,
            severity = DiagnosticSeverity.DEBUG,
            stage = stage,
            peerSuffix = peerId.value.takeLast(6),
            metadata =
                routingSupport.peerRouteMetadata(
                    peerId = peerId,
                    metadata = inboundTransferMetadata(session) + metadata,
                ),
        )
    }

    private fun prewarmHopSession(peerId: PeerId): Unit {
        if (localIdentity.peerId.value >= peerId.value) {
            return
        }
        coroutineScope.launch { ensureHopSession(peerId) }
    }

    private fun forwardMessageToNextHop(frame: WireFrame.Message): Unit {
        val nextHopPeerId = routeCoordinator.nextHopFor(frame.destinationPeerId) ?: return
        coroutineScope.launch {
            sendEncryptedWireFrame(
                peerId = nextHopPeerId,
                frame = frame,
                action = "forward.message",
            )
        }
    }

    private fun shouldAttemptLargeInlineSend(peerId: PeerId): Boolean {
        val nextHopPeerId = routeCoordinator.nextHopFor(peerId) ?: peerId
        return nextHopPeerId.value == peerId.value &&
            (bleTransport?.maximumPayloadBytesPerDelivery(nextHopPeerId)?.let { transportBudget ->
                transportBudget >= LARGE_INLINE_SEND_TRANSPORT_BUDGET_BYTES
            } ?: false)
    }

    private fun isLocalPeerId(peerId: PeerId): Boolean {
        return peerId.value == localIdentity.peerId.value ||
            peerId.value.hexContentEquals(localIdentity.advertisementKeyHash)
    }

    private suspend fun sendEncryptedWireFrame(
        peerId: PeerId,
        frame: WireFrame,
        action: String,
    ): Boolean {
        val session = establishedHopSession(peerId)
        val transportResult =
            if (session != null) {
                sendEncryptedWireFrameWithSession(
                    peerId = peerId,
                    session = session,
                    frame = frame,
                    action = action,
                )
            } else {
                null
            }

        return when (transportResult) {
            TransportSendResult.Delivered -> true
            is TransportSendResult.Dropped -> {
                emitHopSessionFailed(
                    peerId = peerId,
                    stage = "$action.send",
                    reason = DiagnosticReason.DELIVERY_FAILURE,
                )
                false
            }
            null -> false
        }
    }

    private suspend fun establishedHopSession(peerId: PeerId): HopSession? {
        return (ensureHopSession(peerId) as? SessionEstablishmentOutcome.Established)?.session
    }

    private suspend fun sendEncryptedWireFrameWithSession(
        peerId: PeerId,
        session: HopSession,
        frame: WireFrame,
        action: String,
    ): TransportSendResult? {
        return runCatching {
                sendEncryptedDirectWireFrame(
                    peerId = peerId,
                    session = session,
                    frame = frame,
                    action = action,
                )
            }
            .getOrElse { exception ->
                emitHopSessionFailed(
                    peerId = peerId,
                    stage = "$action.encrypt",
                    reason = DiagnosticReason.DELIVERY_FAILURE,
                    metadata = mapOf("cause" to exception::class.simpleName.orEmpty()),
                )
                null
            }
    }

    private suspend fun ensureHopSession(peerId: PeerId): SessionEstablishmentOutcome {
        val existingSession = hopSessions[peerId.value]
        val pendingHandshake = pendingInitiatorHandshakes[peerId.value]

        return when {
            existingSession != null -> SessionEstablishmentOutcome.Established(existingSession)
            pendingHandshake != null ->
                awaitSessionEstablishment(peerId, pendingHandshake.sessionDeferred)
            bleTransport == null -> SessionEstablishmentOutcome.Unreachable
            else -> initiateHopSession(peerId)
        }
    }

    private suspend fun initiateHopSession(peerId: PeerId): SessionEstablishmentOutcome {
        val manager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
        val message1 = manager.createMessage1(localIdentity.noiseIdentity)
        val sessionDeferred = CompletableDeferred<SessionEstablishmentOutcome>()
        pendingInitiatorHandshakes[peerId.value] =
            PendingInitiatorHandshake(manager, sessionDeferred)

        return when (
            sendDirectWireFrame(
                peerId = peerId,
                frame = DirectWireFrame.HandshakeMessage1(message1),
                action = "handshake.message1",
            )
        ) {
            TransportSendResult.Delivered -> awaitSessionEstablishment(peerId, sessionDeferred)
            is TransportSendResult.Dropped -> {
                pendingInitiatorHandshakes.remove(peerId.value)
                emitHopSessionFailed(
                    peerId = peerId,
                    stage = "transport.handshake.message1.send",
                    reason = DiagnosticReason.DELIVERY_FAILURE,
                )
                SessionEstablishmentOutcome.Unreachable
            }
        }
    }

    private suspend fun awaitSessionEstablishment(
        peerId: PeerId,
        sessionDeferred: CompletableDeferred<SessionEstablishmentOutcome>,
    ): SessionEstablishmentOutcome {
        return try {
            withTimeout(HANDSHAKE_TIMEOUT.inWholeMilliseconds) { sessionDeferred.await() }
        } catch (_: TimeoutCancellationException) {
            pendingInitiatorHandshakes.remove(peerId.value)
            emitHopSessionFailed(
                peerId = peerId,
                stage = "transport.handshake.timeout",
                reason = DiagnosticReason.DELIVERY_FAILURE,
            )
            SessionEstablishmentOutcome.Unreachable
        }
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

    private suspend fun sendEncryptedDirectWireFrame(
        peerId: PeerId,
        session: HopSession,
        frame: WireFrame,
        action: String,
    ): TransportSendResult {
        val encodedFrame = WireCodec.encode(frame)
        return session.outboundMutex.withLock {
            val encryptedFrame =
                encryptHopPayload(
                    sendKey = session.sendKey,
                    sendNonce = session.sendNonce,
                    plaintext = encodedFrame,
                )
            val sendResult =
                sendDirectWireFrame(
                    peerId = peerId,
                    frame = DirectWireFrame.Data(encryptedFrame),
                    action = action,
                    preferredMode = preferredTransportModeForEncryptedFrame(frame),
                )
            if (sendResult is TransportSendResult.Delivered) {
                session.sendNonce += 1uL
            }
            sendResult
        }
    }

    private fun encryptHopPayload(
        sendKey: ByteArray,
        sendNonce: ULong,
        plaintext: ByteArray,
    ): ByteArray {
        return localIdentity.cryptoProvider.chacha20Poly1305Seal(
            key = sendKey,
            nonce = noiseNonce(sendNonce),
            aad = byteArrayOf(),
            plaintext = plaintext,
        )
    }

    private fun decryptHopPayload(session: HopSession, ciphertext: ByteArray): ByteArray {
        val plaintext =
            localIdentity.cryptoProvider.chacha20Poly1305Open(
                key = session.receiveKey,
                nonce = noiseNonce(session.receiveNonce),
                aad = byteArrayOf(),
                ciphertext = ciphertext,
            )
        session.receiveNonce += 1u
        return plaintext
    }

    private fun emitHopSessionEstablished(peerId: PeerId, stage: String): Unit {
        emitDiagnostic(
            code = DiagnosticCode.HOP_SESSION_ESTABLISHED,
            severity = DiagnosticSeverity.DEBUG,
            stage = stage,
            peerSuffix = peerId.value.takeLast(6),
            reason = DiagnosticReason.STATE_CHANGE,
            metadata = routingSupport.peerRouteMetadata(peerId),
        )
    }

    private fun emitHopSessionFailed(
        peerId: PeerId,
        stage: String,
        reason: DiagnosticReason,
        metadata: Map<String, String> = emptyMap(),
    ): Unit {
        emitDiagnostic(
            code = DiagnosticCode.HOP_SESSION_FAILED,
            severity = DiagnosticSeverity.WARN,
            stage = stage,
            peerSuffix = peerId.value.takeLast(6),
            reason = reason,
            metadata = routingSupport.peerRouteMetadata(peerId, metadata = metadata),
        )
    }

    private fun powerPolicyNowMillis(): Long {
        return engineClock.elapsedNow().inWholeMilliseconds
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

    private fun scheduleRetryDiagnostic(peerId: PeerId, priority: DeliveryPriority): Unit {
        emitDiagnostic(
            code = DiagnosticCode.NO_ROUTE_AVAILABLE,
            severity = DiagnosticSeverity.WARN,
            stage = "delivery.noRoute",
            peerSuffix = peerId.value.takeLast(6),
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

    private fun createMessageId(): String {
        val current = nextMessageSequenceNumber
        nextMessageSequenceNumber += 1L
        return "${localIdentity.peerId.value.takeLast(6)}-$current"
    }

    private fun createTransferId(): String {
        val current = nextMessageSequenceNumber
        nextMessageSequenceNumber += 1L
        return "transfer-${localIdentity.peerId.value.takeLast(6)}-$current"
    }

    private fun ttlMillisFor(priority: DeliveryPriority): Int {
        return when (priority) {
            DeliveryPriority.HIGH -> 45 * 60 * 1_000
            DeliveryPriority.NORMAL -> 15 * 60 * 1_000
            DeliveryPriority.LOW -> 5 * 60 * 1_000
        }
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

        private fun noiseNonce(value: ULong): ByteArray {
            val nonce = ByteArray(12)
            repeat(8) { index -> nonce[4 + index] = ((value shr (index * 8)) and 0xFFu).toByte() }
            return nonce
        }

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
