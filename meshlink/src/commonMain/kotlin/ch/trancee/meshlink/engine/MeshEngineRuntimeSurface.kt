package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticEvent
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.diagnostics.DiagnosticSink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface MeshEnginePublishedRuntimeSurface {
    val state: StateFlow<MeshLinkState>
    val peerEvents: Flow<PeerEvent>
    val diagnosticEvents: Flow<DiagnosticEvent>
    val messages: Flow<InboundMessage>
}

internal interface MeshEngineCompatibilityRuntimeSurface {
    val mutableState: MutableStateFlow<MeshLinkState>
    val mutablePeerEvents: MutableSharedFlow<PeerEvent>
    val mutableMessages: MutableSharedFlow<InboundMessage>

    @Suppress("LongParameterList")
    fun emitDiagnostic(
        code: DiagnosticCode,
        severity: DiagnosticSeverity,
        stage: String,
        peerSuffix: String? = null,
        reason: DiagnosticReason? = null,
        metadata: Map<String, String> = emptyMap(),
    ): Unit
}

internal class MeshEngineRuntimeSurface(diagnosticSink: DiagnosticSink? = null) :
    MeshEnginePublishedRuntimeSurface, MeshEngineCompatibilityRuntimeSurface {
    override val mutableState: MutableStateFlow<MeshLinkState> =
        MutableStateFlow(MeshLinkState.Uninitialized)
    override val mutablePeerEvents: MutableSharedFlow<PeerEvent> =
        MutableSharedFlow(extraBufferCapacity = 16)
    override val mutableMessages: MutableSharedFlow<InboundMessage> =
        MutableSharedFlow(extraBufferCapacity = 16)
    private val mutableDiagnostics: MutableSharedFlow<DiagnosticEvent> =
        MutableSharedFlow(extraBufferCapacity = 32)
    private val diagnosticSink: DiagnosticSink? = diagnosticSink

    override val state: StateFlow<MeshLinkState> = mutableState.asStateFlow()
    override val peerEvents: Flow<PeerEvent> = mutablePeerEvents.asSharedFlow()
    override val diagnosticEvents: Flow<DiagnosticEvent> = mutableDiagnostics.asSharedFlow()
    override val messages: Flow<InboundMessage> = mutableMessages.asSharedFlow()

    @Suppress("LongParameterList")
    override fun emitDiagnostic(
        code: DiagnosticCode,
        severity: DiagnosticSeverity,
        stage: String,
        peerSuffix: String?,
        reason: DiagnosticReason?,
        metadata: Map<String, String>,
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
}
