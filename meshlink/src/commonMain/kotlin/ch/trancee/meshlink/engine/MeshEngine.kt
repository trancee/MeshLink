package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.ForgetPeerResult
import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.PauseResult
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.ResumeResult
import ch.trancee.meshlink.api.SendFailureReason
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.api.StartResult
import ch.trancee.meshlink.api.StopResult
import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticEvent
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.diagnostics.DiagnosticSink
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportSendResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal class MeshEngine private constructor(
    private val config: MeshLinkConfig,
    private val platformContext: Any?,
    private val bleTransport: BleTransport? = null,
    private val diagnosticSink: DiagnosticSink? = null,
) : MeshLinkApi {
    private val mutableState: MutableStateFlow<MeshLinkState> =
        MutableStateFlow(MeshLinkState.Uninitialized)
    private val mutablePeerEvents: MutableSharedFlow<PeerEvent> = MutableSharedFlow(extraBufferCapacity = 16)
    private val mutableDiagnostics: MutableSharedFlow<DiagnosticEvent> = MutableSharedFlow(extraBufferCapacity = 32)
    private val mutableMessages: MutableSharedFlow<InboundMessage> = MutableSharedFlow(extraBufferCapacity = 16)

    override val state: StateFlow<MeshLinkState> = mutableState.asStateFlow()
    override val peerEvents: Flow<PeerEvent> = mutablePeerEvents.asSharedFlow()
    override val diagnosticEvents: Flow<DiagnosticEvent> = mutableDiagnostics.asSharedFlow()
    override val messages: Flow<InboundMessage> = mutableMessages.asSharedFlow()

    override suspend fun start(): StartResult {
        if (mutableState.value === MeshLinkState.Running) {
            return StartResult.AlreadyRunning
        }
        bleTransport?.start()
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
        bleTransport?.pause()
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
        bleTransport?.resume()
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
        bleTransport?.stop()
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

        return when (
            bleTransport.send(
                OutboundFrame(
                    peerId = peerId,
                    payload = payload,
                ),
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
        val deadline = TimeSource.Monotonic.markNow() + config.deliveryRetryDeadline
        emitDiagnostic(
            code = DiagnosticCode.NO_ROUTE_AVAILABLE,
            severity = DiagnosticSeverity.WARN,
            stage = "delivery.noRoute",
            peerSuffix = peerId.value.takeLast(6),
            reason = DiagnosticReason.DELIVERY_FAILURE,
            metadata = mapOf(
                "priority" to priority.name,
                "retryDeadlineMs" to deadline.elapsedNow().plus(config.deliveryRetryDeadline).inWholeMilliseconds.toString(),
                "retryBackoffBaseMs" to INITIAL_BACKOFF.inWholeMilliseconds.toString(),
            ),
        )
    }

    internal companion object {
        private const val MAX_SUPPORTED_PAYLOAD_BYTES: Int = 64 * 1024
        private val INITIAL_BACKOFF = 250.milliseconds

        internal fun create(
            config: MeshLinkConfig,
            platformContext: Any? = null,
            bleTransport: BleTransport? = null,
            diagnosticSink: DiagnosticSink? = null,
        ): MeshLinkApi {
            return MeshEngine(
                config = config,
                platformContext = platformContext,
                bleTransport = bleTransport,
                diagnosticSink = diagnosticSink,
            )
        }
    }
}
