package ch.trancee.meshlink.test

import ch.trancee.meshlink.diagnostics.DiagnosticEvent
import ch.trancee.meshlink.diagnostics.DiagnosticSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class RecordingDiagnosticSink : DiagnosticSink {
    private val recordedEvents: MutableStateFlow<List<DiagnosticEvent>> =
        MutableStateFlow(emptyList())

    override fun emit(event: DiagnosticEvent): Unit {
        recordedEvents.update { events -> events + event }
    }

    internal fun events(): List<DiagnosticEvent> {
        return recordedEvents.value
    }
}
