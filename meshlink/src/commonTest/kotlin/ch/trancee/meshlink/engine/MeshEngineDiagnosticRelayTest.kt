package ch.trancee.meshlink.engine

import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.test.RecordingDiagnosticSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class MeshEngineDiagnosticRelayTest {
    @Test
    fun `emit publishes the diagnostic event to the flow and sink`() = runBlocking {
        // Arrange
        val diagnosticSink = RecordingDiagnosticSink()
        val relay = MeshEngineDiagnosticRelay(diagnosticSink = diagnosticSink)
        val eventDeferred =
            async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(1_000) { relay.events.first() }
            }

        // Act
        relay.emit(
            code = DiagnosticCode.DELIVERY_SUCCEEDED,
            severity = DiagnosticSeverity.INFO,
            stage = "delivery.completed",
            peerSuffix = "abcdef",
            reason = DiagnosticReason.DELIVERY_FAILURE,
            metadata = mapOf("routeAvailable" to "true"),
        )
        val event = eventDeferred.await()

        // Assert
        assertEquals(DiagnosticCode.DELIVERY_SUCCEEDED, event.code)
        assertEquals(DiagnosticSeverity.INFO, event.severity)
        assertEquals("delivery.completed", event.stage)
        assertEquals("abcdef", event.peerSuffix)
        assertEquals(DiagnosticReason.DELIVERY_FAILURE, event.reason)
        assertEquals(mapOf("routeAvailable" to "true"), event.metadata)
        assertEquals(listOf(event), diagnosticSink.events())
    }

    @Test
    fun `emit works without a diagnostic sink`() = runBlocking {
        // Arrange
        val relay = MeshEngineDiagnosticRelay()
        val eventDeferred =
            async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(1_000) { relay.events.first() }
            }

        // Act
        relay.emit(
            code = DiagnosticCode.MESH_STARTED,
            severity = DiagnosticSeverity.INFO,
            stage = "lifecycle.start",
        )
        val event = eventDeferred.await()

        // Assert
        assertEquals(DiagnosticCode.MESH_STARTED, event.code)
        assertEquals(DiagnosticSeverity.INFO, event.severity)
        assertEquals("lifecycle.start", event.stage)
        assertNull(event.peerSuffix)
        assertNull(event.reason)
        assertEquals(emptyMap(), event.metadata)
    }
}
