package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.ForgetPeerResult
import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.PauseResult
import ch.trancee.meshlink.api.PeerConnectionState
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
import ch.trancee.meshlink.identity.toHexString
import ch.trancee.meshlink.presence.PeerPresenceTracker
import ch.trancee.meshlink.presence.PresenceTransition
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.routing.RoutingAdvertisement
import ch.trancee.meshlink.storage.InMemorySecureStorage
import ch.trancee.meshlink.storage.SecureStorage
import ch.trancee.meshlink.transfer.InboundTransferSession
import ch.trancee.meshlink.transfer.OutboundTransferSession
import ch.trancee.meshlink.transfer.RelayTransferSession
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.trust.TofuTrustStore
import ch.trancee.meshlink.trust.TrustRecord
import ch.trancee.meshlink.wire.WireCodec
import ch.trancee.meshlink.wire.WireFrame
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal class MeshEngine private constructor(
    private val config: MeshLinkConfig,
    private val platformContext: Any?,
    private val localIdentity: LocalIdentity,
    secureStorage: SecureStorage,
    private val bleTransport: BleTransport? = null,
    private val diagnosticSink: DiagnosticSink? = null,
) : MeshLinkApi {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val trustStore = TofuTrustStore(secureStorage)
    private val presenceTracker = PeerPresenceTracker()
    private val routeCoordinator = RouteCoordinator(localIdentity.peerId)
    private var transportCollectionJob: Job? = null
    private val hopSessions: MutableMap<String, HopSession> = linkedMapOf()
    private val pendingInitiatorHandshakes: MutableMap<String, PendingInitiatorHandshake> = linkedMapOf()
    private val pendingResponderHandshakes: MutableMap<String, PendingResponderHandshake> = linkedMapOf()
    private val outboundTransfers: MutableMap<String, OutboundTransferSession> = linkedMapOf()
    private val inboundTransfers: MutableMap<String, InboundTransferSession> = linkedMapOf()
    private val relayTransfers: MutableMap<String, RelayTransferSession> = linkedMapOf()
    private var nextMessageSequenceNumber: Long = 1L

    private val mutableState: MutableStateFlow<MeshLinkState> =
        MutableStateFlow(MeshLinkState.Uninitialized)
    private val mutablePeerEvents: MutableSharedFlow<PeerEvent> =
        MutableSharedFlow(extraBufferCapacity = 16)
    private val mutableDiagnostics: MutableSharedFlow<DiagnosticEvent> =
        MutableSharedFlow(extraBufferCapacity = 32)
    private val mutableMessages: MutableSharedFlow<InboundMessage> =
        MutableSharedFlow(extraBufferCapacity = 16)

    override val state: StateFlow<MeshLinkState> = mutableState.asStateFlow()
    override val peerEvents: Flow<PeerEvent> = mutablePeerEvents.asSharedFlow()
    override val diagnosticEvents: Flow<DiagnosticEvent> = mutableDiagnostics.asSharedFlow()
    override val messages: Flow<InboundMessage> = mutableMessages.asSharedFlow()

    override suspend fun start(): StartResult {
        if (mutableState.value === MeshLinkState.Running) {
            return StartResult.AlreadyRunning
        }
        ensureTransportCollector()
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
        if (payload.size > MAX_SUPPORTED_PAYLOAD_BYTES) {
            emitDiagnostic(
                code = DiagnosticCode.SIZE_LIMIT_REJECTED,
                severity = DiagnosticSeverity.WARN,
                stage = "delivery.send",
                peerSuffix = peerId.value.takeLast(6),
                reason = DiagnosticReason.SIZE_LIMIT,
                metadata = mapOf("payloadBytes" to payload.size.toString()),
            )
            return SendResult.NotSent(SendFailureReason.PAYLOAD_TOO_LARGE)
        }

        if (mutableState.value !== MeshLinkState.Running || bleTransport == null) {
            scheduleRetryDiagnostic(peerId = peerId, priority = priority)
            return SendResult.NotSent(SendFailureReason.UNREACHABLE)
        }
        if (payload.size > INLINE_MESSAGE_PAYLOAD_BYTES) {
            return sendLargePayload(peerId = peerId, payload = payload, priority = priority)
        }

        val nextHopPeerId = routeCoordinator.nextHopFor(peerId) ?: peerId
        return when (val sessionOutcome = ensureHopSession(nextHopPeerId)) {
            is SessionEstablishmentOutcome.Established -> {
                val recipientTrust = trustStore.read(peerId.value) ?: routeCoordinator.routeFor(peerId)?.let { route ->
                    val learnedTrust = TrustRecord(
                        peerIdValue = route.destinationPeerId.value,
                        identityFingerprint = localIdentity.cryptoProvider.sha256(
                            route.ed25519PublicKey + route.x25519PublicKey,
                        ).toHexString(),
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
                    learnedTrust
                }
                if (recipientTrust == null) {
                    emitDiagnostic(
                        code = DiagnosticCode.TRUST_FAILURE,
                        severity = DiagnosticSeverity.ERROR,
                        stage = "delivery.send.noTrust",
                        peerSuffix = peerId.value.takeLast(6),
                        reason = DiagnosticReason.TRUST_FAILURE,
                    )
                    return SendResult.NotSent(SendFailureReason.TRUST_FAILURE)
                }

                val sealedPayload = runCatching {
                    MessageSealer.seal(
                        plaintext = payload,
                        senderIdentity = localIdentity,
                        recipientTrust = recipientTrust,
                    )
                }.getOrElse { exception ->
                    emitHopSessionFailed(
                        peerId = peerId,
                        stage = "delivery.send.encrypt",
                        reason = DiagnosticReason.TRUST_FAILURE,
                        metadata = mapOf("cause" to exception::class.simpleName.orEmpty()),
                    )
                    return SendResult.NotSent(SendFailureReason.TRUST_FAILURE)
                }

                val innerEnvelope = DirectMessageEnvelope(
                    senderPeerId = localIdentity.peerId,
                    senderFingerprint = localIdentity.identityFingerprint,
                    senderEd25519PublicKey = localIdentity.ed25519PublicKey,
                    senderX25519PublicKey = localIdentity.x25519PublicKey,
                    ciphertext = sealedPayload,
                ).encode()
                val routedMessage = WireFrame.Message(
                    messageId = createMessageId(),
                    originPeerId = localIdentity.peerId,
                    destinationPeerId = peerId,
                    priority = priority,
                    ttlMillis = ttlMillisFor(priority),
                    encryptedPayload = innerEnvelope,
                )
                val encryptedFrame = runCatching {
                    encryptHopPayload(sessionOutcome.session, WireCodec.encode(routedMessage))
                }.getOrElse { exception ->
                    emitHopSessionFailed(
                        peerId = nextHopPeerId,
                        stage = "delivery.send.transportEncrypt",
                        reason = DiagnosticReason.DELIVERY_FAILURE,
                        metadata = mapOf("cause" to exception::class.simpleName.orEmpty()),
                    )
                    return SendResult.NotSent(SendFailureReason.UNREACHABLE)
                }

                when (
                    sendDirectWireFrame(
                        peerId = nextHopPeerId,
                        frame = DirectWireFrame.Data(encryptedFrame),
                        action = "send.data",
                    )
                ) {
                    TransportSendResult.Delivered -> {
                        emitDiagnostic(
                            code = DiagnosticCode.DELIVERY_SUCCEEDED,
                            severity = DiagnosticSeverity.INFO,
                            stage = "delivery.send",
                            peerSuffix = peerId.value.takeLast(6),
                        )
                        SendResult.Sent
                    }

                    is TransportSendResult.Dropped -> {
                        scheduleRetryDiagnostic(peerId = peerId, priority = priority)
                        SendResult.NotSent(SendFailureReason.UNREACHABLE)
                    }
                }
            }

            SessionEstablishmentOutcome.TrustFailure -> SendResult.NotSent(SendFailureReason.TRUST_FAILURE)
            SessionEstablishmentOutcome.Unreachable -> {
                scheduleRetryDiagnostic(peerId = peerId, priority = priority)
                SendResult.NotSent(SendFailureReason.UNREACHABLE)
            }
        }
    }

    override suspend fun forgetPeer(peerId: PeerId): ForgetPeerResult {
        emitDiagnostic(
            code = DiagnosticCode.ROUTE_RETRACTED,
            severity = DiagnosticSeverity.WARN,
            stage = "trust.forgetPeer",
            peerSuffix = peerId.value.takeLast(6),
            reason = DiagnosticReason.ROUTE_CHANGE,
        )
        return ForgetPeerResult.Forgotten
    }

    override fun updateBattery(level: Float, isCharging: Boolean): Unit {
        val clampedLevel = level.coerceIn(0f, 1f)
        emitDiagnostic(
            code = DiagnosticCode.POWER_MODE_CHANGED,
            severity = DiagnosticSeverity.INFO,
            stage = "power.updateBattery",
            reason = DiagnosticReason.POWER_CHANGE,
            metadata = mapOf(
                "level" to clampedLevel.toString(),
                "isCharging" to isCharging.toString(),
            ),
        )
    }

    private fun ensureTransportCollector(): Unit {
        if (bleTransport == null || transportCollectionJob != null) {
            return
        }
        transportCollectionJob = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            bleTransport.events.collect { event -> handleTransportEvent(event) }
        }
    }

    private suspend fun handleTransportEvent(event: TransportEvent): Unit {
        when (event) {
            is TransportEvent.FrameReceived -> handleInboundFrame(event)
            is TransportEvent.PeerDiscovered -> {
                when (presenceTracker.onPeerConnected(event.peerId)) {
                    PresenceTransition.FOUND -> {
                        mutablePeerEvents.emit(PeerEvent.Found(event.peerId, PeerConnectionState.CONNECTED))
                    }
                    PresenceTransition.STATE_CHANGED -> {
                        mutablePeerEvents.emit(PeerEvent.StateChanged(event.peerId, PeerConnectionState.CONNECTED))
                    }
                }
                prewarmHopSession(event.peerId)
                emitDiagnostic(
                    code = DiagnosticCode.ROUTE_DISCOVERED,
                    severity = DiagnosticSeverity.INFO,
                    stage = "transport.peerDiscovered",
                    peerSuffix = event.peerId.value.takeLast(6),
                    reason = DiagnosticReason.ROUTE_CHANGE,
                )
            }
            is TransportEvent.PeerLost -> {
                hopSessions.remove(event.peerId.value)
                pendingInitiatorHandshakes.remove(event.peerId.value)?.sessionDeferred?.complete(
                    SessionEstablishmentOutcome.Unreachable,
                )
                pendingResponderHandshakes.remove(event.peerId.value)
                dispatchRoutingAdvertisements(routeCoordinator.onPeerDisconnected(event.peerId))
                if (presenceTracker.onPeerDisconnected(event.peerId)) {
                    mutablePeerEvents.emit(PeerEvent.Lost(event.peerId))
                }
                emitDiagnostic(
                    code = DiagnosticCode.ROUTE_EXPIRED,
                    severity = DiagnosticSeverity.WARN,
                    stage = "transport.peerLost",
                    peerSuffix = event.peerId.value.takeLast(6),
                    reason = DiagnosticReason.ROUTE_CHANGE,
                )
            }
            is TransportEvent.TransportModeChanged -> {
                emitDiagnostic(
                    code = DiagnosticCode.TRANSPORT_MODE_CHANGED,
                    severity = DiagnosticSeverity.INFO,
                    stage = "transport.modeChanged",
                    peerSuffix = event.peerId.value.takeLast(6),
                    reason = DiagnosticReason.TRANSPORT_CHANGE,
                )
            }
        }
    }

    private suspend fun handleInboundFrame(event: TransportEvent.FrameReceived): Unit {
        when (val frame = DirectWireFrame.decode(event.payload)) {
            is DirectWireFrame.HandshakeMessage1 -> handleHandshakeMessage1(event.peerId, frame.payload)
            is DirectWireFrame.HandshakeMessage2 -> handleHandshakeMessage2(event.peerId, frame.payload)
            is DirectWireFrame.HandshakeMessage3 -> handleHandshakeMessage3(event.peerId, frame.payload)
            is DirectWireFrame.Data -> handleEncryptedDataFrame(event.peerId, frame.payload)
        }
    }

    private suspend fun handleHandshakeMessage1(peerId: PeerId, payload: ByteArray): Unit {
        val manager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
        val message2 = runCatching {
            manager.processMessage1AndCreateMessage2(localIdentity.noiseIdentity, payload)
        }.getOrElse { exception ->
            emitHopSessionFailed(
                peerId = peerId,
                stage = "transport.handshake.message1",
                reason = DiagnosticReason.DELIVERY_FAILURE,
                metadata = mapOf("cause" to exception::class.simpleName.orEmpty()),
            )
            return
        }
        pendingResponderHandshakes[peerId.value] = PendingResponderHandshake(manager)
        when (
            sendDirectWireFrame(
                peerId = peerId,
                frame = DirectWireFrame.HandshakeMessage2(message2),
                action = "handshake.message2",
            )
        ) {
            TransportSendResult.Delivered -> Unit
            is TransportSendResult.Dropped -> {
                pendingResponderHandshakes.remove(peerId.value)
                emitHopSessionFailed(
                    peerId = peerId,
                    stage = "transport.handshake.message2.send",
                    reason = DiagnosticReason.DELIVERY_FAILURE,
                )
            }
        }
    }

    private suspend fun handleHandshakeMessage2(peerId: PeerId, payload: ByteArray): Unit {
        val pending = pendingInitiatorHandshakes[peerId.value] ?: run {
            emitHopSessionFailed(
                peerId = peerId,
                stage = "transport.handshake.message2.unexpected",
                reason = DiagnosticReason.DELIVERY_FAILURE,
            )
            return
        }
        val result = runCatching {
            pending.manager.processMessage2AndCreateMessage3(localIdentity.noiseIdentity, payload)
        }.getOrElse { exception ->
            pendingInitiatorHandshakes.remove(peerId.value)
            pending.sessionDeferred.complete(SessionEstablishmentOutcome.Unreachable)
            emitHopSessionFailed(
                peerId = peerId,
                stage = "transport.handshake.message2.process",
                reason = DiagnosticReason.DELIVERY_FAILURE,
                metadata = mapOf("cause" to exception::class.simpleName.orEmpty()),
            )
            return
        }
        val trustRecord = verifyAndPersistTrust(
            peerId = peerId,
            remoteEd25519PublicKey = result.remoteEd25519PublicKey,
            remoteX25519PublicKey = result.remoteStaticPublicKey,
        )
        if (trustRecord == null) {
            pendingInitiatorHandshakes.remove(peerId.value)
            pending.sessionDeferred.complete(SessionEstablishmentOutcome.TrustFailure)
            emitHopSessionFailed(
                peerId = peerId,
                stage = "transport.handshake.message2.trust",
                reason = DiagnosticReason.TRUST_FAILURE,
            )
            return
        }

        when (
            sendDirectWireFrame(
                peerId = peerId,
                frame = DirectWireFrame.HandshakeMessage3(result.message3),
                action = "handshake.message3",
            )
        ) {
            TransportSendResult.Delivered -> {
                val session = HopSession(sendKey = result.sendKey, receiveKey = result.receiveKey)
                hopSessions[peerId.value] = session
                pendingInitiatorHandshakes.remove(peerId.value)
                pending.sessionDeferred.complete(SessionEstablishmentOutcome.Established(session))
                dispatchRoutingAdvertisements(routeCoordinator.onPeerConnected(peerId, trustRecord))
                emitHopSessionEstablished(peerId, stage = "transport.handshake.message2.complete")
            }

            is TransportSendResult.Dropped -> {
                pendingInitiatorHandshakes.remove(peerId.value)
                pending.sessionDeferred.complete(SessionEstablishmentOutcome.Unreachable)
                emitHopSessionFailed(
                    peerId = peerId,
                    stage = "transport.handshake.message3.send",
                    reason = DiagnosticReason.DELIVERY_FAILURE,
                )
            }
        }
    }

    private suspend fun handleHandshakeMessage3(peerId: PeerId, payload: ByteArray): Unit {
        val pending = pendingResponderHandshakes.remove(peerId.value) ?: run {
            emitHopSessionFailed(
                peerId = peerId,
                stage = "transport.handshake.message3.unexpected",
                reason = DiagnosticReason.DELIVERY_FAILURE,
            )
            return
        }
        val result = runCatching {
            pending.manager.processMessage3(localIdentity.noiseIdentity, payload)
        }.getOrElse { exception ->
            emitHopSessionFailed(
                peerId = peerId,
                stage = "transport.handshake.message3.process",
                reason = DiagnosticReason.DELIVERY_FAILURE,
                metadata = mapOf("cause" to exception::class.simpleName.orEmpty()),
            )
            return
        }
        val trustRecord = verifyAndPersistTrust(
            peerId = peerId,
            remoteEd25519PublicKey = result.remoteEd25519PublicKey,
            remoteX25519PublicKey = result.remoteStaticPublicKey,
        )
        if (trustRecord == null) {
            emitHopSessionFailed(
                peerId = peerId,
                stage = "transport.handshake.message3.trust",
                reason = DiagnosticReason.TRUST_FAILURE,
            )
            return
        }
        hopSessions[peerId.value] = HopSession(sendKey = result.sendKey, receiveKey = result.receiveKey)
        dispatchRoutingAdvertisements(routeCoordinator.onPeerConnected(peerId, trustRecord))
        emitHopSessionEstablished(peerId, stage = "transport.handshake.message3.complete")
    }

    private suspend fun handleEncryptedDataFrame(peerId: PeerId, payload: ByteArray): Unit {
        val session = hopSessions[peerId.value] ?: run {
            emitHopSessionFailed(
                peerId = peerId,
                stage = "transport.data.noSession",
                reason = DiagnosticReason.DELIVERY_FAILURE,
            )
            return
        }
        val decryptedEnvelopeBytes = runCatching {
            decryptHopPayload(session, payload)
        }.getOrElse { exception ->
            emitHopSessionFailed(
                peerId = peerId,
                stage = "transport.data.decrypt",
                reason = DiagnosticReason.DELIVERY_FAILURE,
                metadata = mapOf("cause" to exception::class.simpleName.orEmpty()),
            )
            return
        }
        val frame = runCatching {
            WireCodec.decode(decryptedEnvelopeBytes)
        }.getOrElse { exception ->
            emitHopSessionFailed(
                peerId = peerId,
                stage = "transport.data.decode",
                reason = DiagnosticReason.DELIVERY_FAILURE,
                metadata = mapOf("cause" to exception::class.simpleName.orEmpty()),
            )
            return
        }

        when (frame) {
            is WireFrame.Message -> handleRoutedMessageFrame(peerId = peerId, frame = frame)
            is WireFrame.RouteUpdate -> dispatchRoutingAdvertisements(routeCoordinator.onRouteUpdate(peerId, frame))
            is WireFrame.RouteRetraction -> dispatchRoutingAdvertisements(routeCoordinator.onRouteRetraction(peerId, frame))
            is WireFrame.RouteDigest -> routeCoordinator.onRouteDigest(peerId, frame)
            is WireFrame.Hello -> Unit
            is WireFrame.Ihu -> Unit
            is WireFrame.SeqNoRequest -> Unit
            is WireFrame.TransferStart -> handleTransferStart(peerId, frame)
            is WireFrame.TransferChunk -> handleTransferChunk(peerId, frame)
            is WireFrame.TransferAck -> handleTransferAck(peerId, frame)
            is WireFrame.TransferComplete -> handleTransferComplete(peerId, frame)
            is WireFrame.TransferAbort -> handleTransferAbort(peerId, frame)
        }
    }

    private suspend fun handleRoutedMessageFrame(peerId: PeerId, frame: WireFrame.Message): Unit {
        if (frame.destinationPeerId.value != localIdentity.peerId.value) {
            forwardMessageToNextHop(frame)
            return
        }

        deliverInnerEnvelope(
            immediatePeerId = peerId,
            originPeerId = frame.originPeerId,
            encryptedPayload = frame.encryptedPayload,
            priority = frame.priority,
        )
    }

    private suspend fun deliverInnerEnvelope(
        immediatePeerId: PeerId,
        originPeerId: PeerId,
        encryptedPayload: ByteArray,
        priority: DeliveryPriority,
    ): Unit {
        val envelope = runCatching {
            DirectMessageEnvelope.decode(encryptedPayload)
        }.getOrElse { exception ->
            emitHopSessionFailed(
                peerId = immediatePeerId,
                stage = "transport.data.messageEnvelope",
                reason = DiagnosticReason.DELIVERY_FAILURE,
                metadata = mapOf("cause" to exception::class.simpleName.orEmpty()),
            )
            return
        }
        val senderTrust = verifyAndPersistTrust(
            peerId = envelope.senderPeerId,
            remoteEd25519PublicKey = envelope.senderEd25519PublicKey,
            remoteX25519PublicKey = envelope.senderX25519PublicKey,
            expectedFingerprint = envelope.senderFingerprint,
        ) ?: return
        val plaintext = runCatching {
            MessageSealer.open(
                sealedPayload = envelope.ciphertext,
                recipientIdentity = localIdentity,
                senderTrust = senderTrust,
            )
        }.getOrElse { exception ->
            emitDiagnostic(
                code = DiagnosticCode.TRUST_FAILURE,
                severity = DiagnosticSeverity.ERROR,
                stage = "transport.data.open",
                peerSuffix = envelope.senderPeerId.value.takeLast(6),
                reason = DiagnosticReason.TRUST_FAILURE,
                metadata = mapOf("cause" to exception::class.simpleName.orEmpty()),
            )
            return
        }
        mutableMessages.emit(
            InboundMessage(
                originPeerId = originPeerId,
                payload = plaintext,
                receivedAtEpochMillis = 0L,
                priority = priority,
            ),
        )
    }

    private suspend fun sendLargePayload(
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
    ): SendResult {
        val recipientTrust = trustStore.read(peerId.value) ?: routeCoordinator.routeFor(peerId)?.let { route ->
            val learnedTrust = TrustRecord(
                peerIdValue = route.destinationPeerId.value,
                identityFingerprint = localIdentity.cryptoProvider.sha256(
                    route.ed25519PublicKey + route.x25519PublicKey,
                ).toHexString(),
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
            learnedTrust
        }
        if (recipientTrust == null) {
            scheduleRetryDiagnostic(peerId = peerId, priority = priority)
            return SendResult.NotSent(SendFailureReason.UNREACHABLE)
        }

        val sealedPayload = runCatching {
            MessageSealer.seal(
                plaintext = payload,
                senderIdentity = localIdentity,
                recipientTrust = recipientTrust,
            )
        }.getOrElse { exception ->
            emitHopSessionFailed(
                peerId = peerId,
                stage = "transfer.encrypt",
                reason = DiagnosticReason.TRUST_FAILURE,
                metadata = mapOf("cause" to exception::class.simpleName.orEmpty()),
            )
            return SendResult.NotSent(SendFailureReason.TRUST_FAILURE)
        }
        val innerEnvelope = DirectMessageEnvelope(
            senderPeerId = localIdentity.peerId,
            senderFingerprint = localIdentity.identityFingerprint,
            senderEd25519PublicKey = localIdentity.ed25519PublicKey,
            senderX25519PublicKey = localIdentity.x25519PublicKey,
            ciphertext = sealedPayload,
        ).encode()
        val chunks = innerEnvelope.asList().chunked(TRANSFER_CHUNK_PAYLOAD_BYTES).map { chunk -> chunk.toByteArray() }
        val session = OutboundTransferSession(
            transferId = createTransferId(),
            messageId = createMessageId(),
            originPeerId = localIdentity.peerId,
            destinationPeerId = peerId,
            chunks = chunks,
            totalBytes = innerEnvelope.size,
            maxChunkPayloadBytes = TRANSFER_CHUNK_PAYLOAD_BYTES,
        )
        outboundTransfers[session.transferId] = session
        emitDiagnostic(
            code = DiagnosticCode.TRANSFER_STARTED,
            severity = DiagnosticSeverity.INFO,
            stage = "transfer.send.start",
            peerSuffix = peerId.value.takeLast(6),
        )

        val startedAt = TimeSource.Monotonic.markNow()
        var retryDelay = TRANSFER_INITIAL_RETRY_DELAY
        while (startedAt.elapsedNow() < config.deliveryRetryDeadline) {
            sendTransferTowardsDestination(session.destinationPeerId, session.asStartFrame(), "transfer.start")
            val missingChunks = session.missingChunkIndices()
            if (missingChunks.isEmpty()) {
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
                    peerSuffix = peerId.value.takeLast(6),
                )
                return SendResult.Sent
            }
            missingChunks.forEach { chunkIndex ->
                sendTransferTowardsDestination(
                    session.destinationPeerId,
                    WireFrame.TransferChunk(
                        transferId = session.transferId,
                        chunkIndex = chunkIndex,
                        payload = session.chunks[chunkIndex],
                    ),
                    "transfer.chunk",
                )
            }
            emitDiagnostic(
                code = DiagnosticCode.TRANSFER_PROGRESS,
                severity = DiagnosticSeverity.DEBUG,
                stage = "transfer.send.progress",
                peerSuffix = peerId.value.takeLast(6),
                metadata = mapOf(
                    "ackedChunks" to (session.totalChunks - missingChunks.size).toString(),
                    "totalChunks" to session.totalChunks.toString(),
                ),
            )
            delay(retryDelay)
            retryDelay = (retryDelay * 2).coerceAtMost(MAX_TRANSFER_RETRY_DELAY)
        }

        outboundTransfers.remove(session.transferId)
        emitDiagnostic(
            code = DiagnosticCode.TRANSFER_FAILED,
            severity = DiagnosticSeverity.ERROR,
            stage = "transfer.send.timeout",
            peerSuffix = peerId.value.takeLast(6),
            reason = DiagnosticReason.TRANSFER_FAILURE,
        )
        return SendResult.NotSent(SendFailureReason.TRANSFER_TIMED_OUT)
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
        if (frame.destinationPeerId.value == localIdentity.peerId.value) {
            val existingSession = inboundTransfers[frame.transferId]
            if (existingSession != null) {
                existingSession.upstreamPeerId = peerId
            } else {
                inboundTransfers[frame.transferId] = InboundTransferSession(
                    transferId = frame.transferId,
                    messageId = frame.messageId,
                    originPeerId = frame.originPeerId,
                    destinationPeerId = frame.destinationPeerId,
                    upstreamPeerId = peerId,
                    totalBytes = frame.totalBytes,
                    totalChunks = frame.totalChunks,
                    maxChunkPayloadBytes = frame.maxChunkPayloadBytes,
                )
            }
            val ackFrame = inboundTransfers[frame.transferId]?.asAckFrame() ?: return
            sendEncryptedWireFrame(peerId, ackFrame, "transfer.ack.start")
            return
        }

        val relaySession = relayTransfers[frame.transferId]
        if (relaySession != null) {
            relaySession.upstreamPeerId = peerId
        } else {
            relayTransfers[frame.transferId] = RelayTransferSession(
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
            inboundSession.acceptChunk(frame)
            sendEncryptedWireFrame(inboundSession.upstreamPeerId, inboundSession.asAckFrame(), "transfer.ack.chunk")
            return
        }
        val relaySession = relayTransfers[frame.transferId] ?: return
        sendTransferTowardsDestination(relaySession.destinationPeerId, frame, "transfer.forward.chunk")
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

    private suspend fun handleTransferComplete(peerId: PeerId, frame: WireFrame.TransferComplete): Unit {
        val inboundSession = inboundTransfers.remove(frame.transferId)
        if (inboundSession != null) {
            if (inboundSession.isComplete()) {
                deliverInnerEnvelope(
                    immediatePeerId = peerId,
                    originPeerId = inboundSession.originPeerId,
                    encryptedPayload = inboundSession.assembledPayload(),
                    priority = DeliveryPriority.NORMAL,
                )
            }
            return
        }
        val relaySession = relayTransfers.remove(frame.transferId) ?: return
        sendTransferTowardsDestination(relaySession.destinationPeerId, frame, "transfer.forward.complete")
    }

    private suspend fun handleTransferAbort(peerId: PeerId, frame: WireFrame.TransferAbort): Unit {
        inboundTransfers.remove(frame.transferId)
        val relaySession = relayTransfers.remove(frame.transferId)
        if (relaySession != null) {
            sendTransferTowardsDestination(relaySession.destinationPeerId, frame, "transfer.forward.abort")
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

    private fun prewarmHopSession(peerId: PeerId): Unit {
        if (localIdentity.peerId.value >= peerId.value) {
            return
        }
        coroutineScope.launch {
            ensureHopSession(peerId)
        }
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

    private fun dispatchRoutingAdvertisements(advertisements: List<RoutingAdvertisement>): Unit {
        advertisements.forEach { advertisement ->
            coroutineScope.launch {
                sendEncryptedWireFrame(
                    peerId = advertisement.targetPeerId,
                    frame = advertisement.frame,
                    action = "routing.advertise",
                )
            }
        }
    }

    private suspend fun sendEncryptedWireFrame(
        peerId: PeerId,
        frame: WireFrame,
        action: String,
    ): Boolean {
        val sessionOutcome = ensureHopSession(peerId)
        val session = (sessionOutcome as? SessionEstablishmentOutcome.Established)?.session ?: return false
        val encryptedFrame = runCatching {
            encryptHopPayload(session, WireCodec.encode(frame))
        }.getOrElse { exception ->
            emitHopSessionFailed(
                peerId = peerId,
                stage = "$action.encrypt",
                reason = DiagnosticReason.DELIVERY_FAILURE,
                metadata = mapOf("cause" to exception::class.simpleName.orEmpty()),
            )
            return false
        }
        return when (
            sendDirectWireFrame(
                peerId = peerId,
                frame = DirectWireFrame.Data(encryptedFrame),
                action = action,
            )
        ) {
            TransportSendResult.Delivered -> true
            is TransportSendResult.Dropped -> {
                emitHopSessionFailed(
                    peerId = peerId,
                    stage = "$action.send",
                    reason = DiagnosticReason.DELIVERY_FAILURE,
                )
                false
            }
        }
    }

    private suspend fun ensureHopSession(peerId: PeerId): SessionEstablishmentOutcome {
        hopSessions[peerId.value]?.let { session ->
            return SessionEstablishmentOutcome.Established(session)
        }
        pendingInitiatorHandshakes[peerId.value]?.let { pending ->
            return awaitSessionEstablishment(peerId, pending.sessionDeferred)
        }
        if (bleTransport == null) {
            return SessionEstablishmentOutcome.Unreachable
        }

        val manager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
        val message1 = manager.createMessage1(localIdentity.noiseIdentity)
        val sessionDeferred = CompletableDeferred<SessionEstablishmentOutcome>()
        pendingInitiatorHandshakes[peerId.value] = PendingInitiatorHandshake(manager, sessionDeferred)
        when (
            sendDirectWireFrame(
                peerId = peerId,
                frame = DirectWireFrame.HandshakeMessage1(message1),
                action = "handshake.message1",
            )
        ) {
            TransportSendResult.Delivered -> return awaitSessionEstablishment(peerId, sessionDeferred)
            is TransportSendResult.Dropped -> {
                pendingInitiatorHandshakes.remove(peerId.value)
                emitHopSessionFailed(
                    peerId = peerId,
                    stage = "transport.handshake.message1.send",
                    reason = DiagnosticReason.DELIVERY_FAILURE,
                )
                return SessionEstablishmentOutcome.Unreachable
            }
        }
    }

    private suspend fun awaitSessionEstablishment(
        peerId: PeerId,
        sessionDeferred: CompletableDeferred<SessionEstablishmentOutcome>,
    ): SessionEstablishmentOutcome {
        return try {
            withTimeout(HANDSHAKE_TIMEOUT.inWholeMilliseconds) {
                sessionDeferred.await()
            }
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

    private suspend fun verifyAndPersistTrust(
        peerId: PeerId,
        remoteEd25519PublicKey: ByteArray,
        remoteX25519PublicKey: ByteArray,
        expectedFingerprint: String? = null,
    ): TrustRecord? {
        val fingerprint = localIdentity.cryptoProvider.sha256(
            remoteEd25519PublicKey + remoteX25519PublicKey,
        ).toHexString()
        if (expectedFingerprint != null && expectedFingerprint != fingerprint) {
            emitDiagnostic(
                code = DiagnosticCode.TRUST_FAILURE,
                severity = DiagnosticSeverity.ERROR,
                stage = "trust.verify.fingerprint",
                peerSuffix = peerId.value.takeLast(6),
                reason = DiagnosticReason.TRUST_FAILURE,
            )
            return null
        }

        val existingTrust = trustStore.read(peerId.value)
        if (existingTrust == null) {
            val trustRecord = TrustRecord(
                peerIdValue = peerId.value,
                identityFingerprint = fingerprint,
                ed25519PublicKey = remoteEd25519PublicKey,
                x25519PublicKey = remoteX25519PublicKey,
            )
            trustStore.write(trustRecord)
            emitDiagnostic(
                code = DiagnosticCode.TRUST_ESTABLISHED,
                severity = DiagnosticSeverity.INFO,
                stage = "trust.pin",
                peerSuffix = peerId.value.takeLast(6),
                reason = DiagnosticReason.STATE_CHANGE,
            )
            return trustRecord
        }
        if (
            existingTrust.identityFingerprint != fingerprint ||
            !existingTrust.ed25519PublicKey.contentEquals(remoteEd25519PublicKey) ||
            !existingTrust.x25519PublicKey.contentEquals(remoteX25519PublicKey)
        ) {
            emitDiagnostic(
                code = DiagnosticCode.TRUST_FAILURE,
                severity = DiagnosticSeverity.ERROR,
                stage = "trust.verify",
                peerSuffix = peerId.value.takeLast(6),
                reason = DiagnosticReason.TRUST_FAILURE,
            )
            return null
        }
        return existingTrust
    }

    private suspend fun sendDirectWireFrame(
        peerId: PeerId,
        frame: DirectWireFrame,
        action: String,
    ): TransportSendResult {
        val transport = bleTransport ?: return TransportSendResult.Dropped("BLE transport is unavailable")
        return runPlatformCall(action) {
            transport.send(
                OutboundFrame(
                    peerId = peerId,
                    payload = frame.encode(),
                ),
            )
        }
    }

    private fun encryptHopPayload(session: HopSession, plaintext: ByteArray): ByteArray {
        val ciphertext = localIdentity.cryptoProvider.chacha20Poly1305Seal(
            key = session.sendKey,
            nonce = noiseNonce(session.sendNonce),
            aad = byteArrayOf(),
            plaintext = plaintext,
        )
        session.sendNonce += 1u
        return ciphertext
    }

    private fun decryptHopPayload(session: HopSession, ciphertext: ByteArray): ByteArray {
        val plaintext = localIdentity.cryptoProvider.chacha20Poly1305Open(
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
            metadata = metadata,
        )
    }

    private fun emitDiagnostic(
        code: DiagnosticCode,
        severity: DiagnosticSeverity,
        stage: String,
        peerSuffix: String? = null,
        reason: DiagnosticReason? = null,
        metadata: Map<String, String> = emptyMap(),
    ): Unit {
        val event = DiagnosticEvent(
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
            metadata = mapOf(
                "priority" to priority.name,
                "retryDeadlineMs" to config.deliveryRetryDeadline.inWholeMilliseconds.toString(),
                "retryBackoffBaseMs" to INITIAL_BACKOFF.inWholeMilliseconds.toString(),
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
        private const val TRANSFER_CHUNK_PAYLOAD_BYTES: Int = 256
        private val HANDSHAKE_TIMEOUT = 1.seconds
        private val INITIAL_BACKOFF = 250.milliseconds
        private val TRANSFER_INITIAL_RETRY_DELAY = 100.milliseconds
        private val MAX_TRANSFER_RETRY_DELAY = 500.milliseconds

        private fun noiseNonce(value: ULong): ByteArray {
            val nonce = ByteArray(12)
            repeat(8) { index ->
                nonce[4 + index] = ((value shr (index * 8)) and 0xFFu).toByte()
            }
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

private sealed class SessionEstablishmentOutcome {
    internal class Established internal constructor(
        internal val session: HopSession,
    ) : SessionEstablishmentOutcome()

    internal data object TrustFailure : SessionEstablishmentOutcome()

    internal data object Unreachable : SessionEstablishmentOutcome()
}

private class HopSession internal constructor(
    sendKey: ByteArray,
    receiveKey: ByteArray,
) {
    internal val sendKey: ByteArray = sendKey.copyOf()
    internal val receiveKey: ByteArray = receiveKey.copyOf()
    internal var sendNonce: ULong = 0u
    internal var receiveNonce: ULong = 0u
}

private class PendingInitiatorHandshake internal constructor(
    internal val manager: NoiseXXHandshakeManager,
    internal val sessionDeferred: CompletableDeferred<SessionEstablishmentOutcome>,
)

private class PendingResponderHandshake internal constructor(
    internal val manager: NoiseXXHandshakeManager,
)
