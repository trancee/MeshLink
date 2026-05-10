package ch.trancee.meshlink.test

import ch.trancee.meshlink.diagnostics.DiagnosticEvent
import ch.trancee.meshlink.diagnostics.DiagnosticSink

internal class RecordingDiagnosticSink : DiagnosticSink {
    private val recordedEvents: MutableList<DiagnosticEvent> = mutableListOf()

    override fun emit(event: DiagnosticEvent): Unit {
        recordedEvents += event
    }

    internal fun events(): List<DiagnosticEvent> {
        return recordedEvents.toList()
    }
}
