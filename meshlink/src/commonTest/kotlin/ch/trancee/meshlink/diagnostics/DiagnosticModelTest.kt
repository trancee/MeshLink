package ch.trancee.meshlink.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosticModelTest {
    @Test
    fun `diagnostic event copies metadata and exposes a stable summary string`() {
        // Arrange
        val sourceMetadata = linkedMapOf("peerId" to "peer-123", "routeAvailable" to "true")
        val event =
            DiagnosticEvent(
                code = DiagnosticCode.ROUTE_DISCOVERED,
                severity = DiagnosticSeverity.INFO,
                stage = "routing.routeUpdate",
                peerSuffix = "123456",
                reason = DiagnosticReason.ROUTE_CHANGE,
                metadata = sourceMetadata,
            )
        sourceMetadata["peerId"] = "mutated"

        // Act
        val metadata = event.metadata
        val summary = event.toString()

        // Assert
        assertEquals("peer-123", metadata["peerId"])
        assertEquals("true", metadata["routeAvailable"])
        assertEquals(
            "DiagnosticEvent(code=ROUTE_DISCOVERED, severity=INFO, stage=routing.routeUpdate, peerSuffix=123456, reason=ROUTE_CHANGE)",
            summary,
        )
    }
}
