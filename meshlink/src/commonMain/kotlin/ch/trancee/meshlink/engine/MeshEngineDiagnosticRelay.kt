package ch.trancee.meshlink.engine

import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticEvent
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.diagnostics.DiagnosticSink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal class MeshEngineDiagnosticRelay(private val diagnosticSink: DiagnosticSink? = null) {
    private val mutableDiagnostics: MutableSharedFlow<DiagnosticEvent> =
        MutableSharedFlow(extraBufferCapacity = 32)

    internal val events: Flow<DiagnosticEvent> = mutableDiagnostics.asSharedFlow()

    @Suppress("LongParameterList")
    internal fun emit(
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
}
