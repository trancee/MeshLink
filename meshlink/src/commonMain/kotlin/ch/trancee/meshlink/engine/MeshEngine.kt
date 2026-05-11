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
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticEvent
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.diagnostics.DiagnosticSink
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.storage.InMemorySecureStorage
import ch.trancee.meshlink.storage.SecureStorage
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.trust.TofuTrustStore
import ch.trancee.meshlink.trust.TrustRecord
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
import kotlin.time.Duration.Companion.milliseconds

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
    private var transportCollectionJob: Job? = null

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

        val ciphertext = MessageSealer.seal(
            plaintext = payload,
            identityFingerprint = localIdentity.identityFingerprint,
        )
        val frame = DirectMessageEnvelope(
            senderPeerId = localIdentity.peerId,
            senderFingerprint = localIdentity.identityFingerprint,
            senderEd25519PublicKey = localIdentity.ed25519PublicKey,
            senderX25519PublicKey = localIdentity.x25519PublicKey,
            ciphertext = ciphertext,
        ).encode()

        val sendResult = runPlatformCall("send") {
            bleTransport.send(
                OutboundFrame(
                    peerId = peerId,
                    payload = frame,
                ),
            )
        }

        return when (sendResult) {
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
                mutablePeerEvents.emit(PeerEvent.Found(event.peerId, PeerConnectionState.CONNECTED))
                emitDiagnostic(
                    code = DiagnosticCode.ROUTE_DISCOVERED,
                    severity = DiagnosticSeverity.INFO,
                    stage = "transport.peerDiscovered",
                    peerSuffix = event.peerId.value.takeLast(6),
                    reason = DiagnosticReason.ROUTE_CHANGE,
                )
            }
            is TransportEvent.PeerLost -> {
                mutablePeerEvents.emit(PeerEvent.Lost(event.peerId))
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
        val envelope = DirectMessageEnvelope.decode(event.payload)
        val existingTrust = trustStore.read(envelope.senderPeerId.value)
        if (existingTrust == null) {
            trustStore.write(
                TrustRecord(
                    peerIdValue = envelope.senderPeerId.value,
                    identityFingerprint = envelope.senderFingerprint,
                    ed25519PublicKey = envelope.senderEd25519PublicKey,
                    x25519PublicKey = envelope.senderX25519PublicKey,
                ),
            )
            emitDiagnostic(
                code = DiagnosticCode.TRUST_ESTABLISHED,
                severity = DiagnosticSeverity.INFO,
                stage = "trust.pin",
                peerSuffix = envelope.senderPeerId.value.takeLast(6),
                reason = DiagnosticReason.STATE_CHANGE,
            )
        } else if (
            existingTrust.identityFingerprint != envelope.senderFingerprint ||
            !existingTrust.ed25519PublicKey.contentEquals(envelope.senderEd25519PublicKey) ||
            !existingTrust.x25519PublicKey.contentEquals(envelope.senderX25519PublicKey)
        ) {
            emitDiagnostic(
                code = DiagnosticCode.TRUST_FAILURE,
                severity = DiagnosticSeverity.ERROR,
                stage = "trust.verify",
                peerSuffix = envelope.senderPeerId.value.takeLast(6),
                reason = DiagnosticReason.TRUST_FAILURE,
            )
            return
        }

        emitDiagnostic(
            code = DiagnosticCode.HOP_SESSION_ESTABLISHED,
            severity = DiagnosticSeverity.DEBUG,
            stage = "transport.receive",
            peerSuffix = envelope.senderPeerId.value.takeLast(6),
            reason = DiagnosticReason.STATE_CHANGE,
        )

        val plaintext = MessageSealer.open(
            ciphertext = envelope.ciphertext,
            identityFingerprint = envelope.senderFingerprint,
        )
        mutableMessages.emit(
            InboundMessage(
                originPeerId = envelope.senderPeerId,
                payload = plaintext,
                receivedAtEpochMillis = 0L,
                priority = DeliveryPriority.NORMAL,
            ),
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
