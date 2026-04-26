package ch.trancee.meshlink.api

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Zero-overhead [DiagnosticSinkApi] used when [DiagnosticsConfig.enabled] is false. All [emit]
 * calls are discarded without constructing a payload or touching the flow.
 */
internal object NoOpDiagnosticSink : DiagnosticSinkApi {

    private val _emptyFlow = MutableSharedFlow<DiagnosticEvent>(replay = 0)

    override val events: SharedFlow<DiagnosticEvent> = _emptyFlow

    override fun emit(code: DiagnosticCode, payloadProvider: () -> DiagnosticPayload) {
        // intentionally no-op: diagnostics disabled
    }
}
