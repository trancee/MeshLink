package ch.trancee.meshlink.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosticCatalogTest {
    @Test
    fun `severityFor maps informational codes to info`() {
        // Arrange
        val informationalCodes =
            listOf(
                DiagnosticCode.MESH_STARTED,
                DiagnosticCode.MESH_PAUSED,
                DiagnosticCode.MESH_RESUMED,
                DiagnosticCode.MESH_STOPPED,
                DiagnosticCode.TRUST_ESTABLISHED,
                DiagnosticCode.ROUTE_DISCOVERED,
                DiagnosticCode.ROUTE_CONVERGED,
                DiagnosticCode.DELIVERY_QUEUED,
                DiagnosticCode.DELIVERY_SUCCEEDED,
                DiagnosticCode.TRANSFER_STARTED,
                DiagnosticCode.TRANSFER_COMPLETED,
                DiagnosticCode.TRANSPORT_MODE_CHANGED,
                DiagnosticCode.POWER_MODE_CHANGED,
            )

        // Act / Assert
        informationalCodes.forEach { code ->
            assertEquals(DiagnosticSeverity.INFO, DiagnosticCatalog.severityFor(code), code.name)
        }
    }

    @Test
    fun `severityFor maps debug codes to debug`() {
        // Arrange
        val debugCodes =
            listOf(
                DiagnosticCode.HOP_SESSION_ESTABLISHED,
                DiagnosticCode.ROUTE_UPDATED,
                DiagnosticCode.TRANSFER_PROGRESS,
            )

        // Act / Assert
        debugCodes.forEach { code ->
            assertEquals(DiagnosticSeverity.DEBUG, DiagnosticCatalog.severityFor(code), code.name)
        }
    }

    @Test
    fun `severityFor maps warning codes to warn`() {
        // Arrange
        val warningCodes =
            listOf(
                DiagnosticCode.HOP_SESSION_FAILED,
                DiagnosticCode.ROUTE_RETRACTED,
                DiagnosticCode.ROUTE_EXPIRED,
                DiagnosticCode.NO_ROUTE_AVAILABLE,
                DiagnosticCode.DELIVERY_RETRY_SCHEDULED,
                DiagnosticCode.DELIVERY_RETRYING,
                DiagnosticCode.SIZE_LIMIT_REJECTED,
            )

        // Act / Assert
        warningCodes.forEach { code ->
            assertEquals(DiagnosticSeverity.WARN, DiagnosticCatalog.severityFor(code), code.name)
        }
    }

    @Test
    fun `severityFor maps failure codes to error`() {
        // Arrange
        val failureCodes =
            listOf(
                DiagnosticCode.TRUST_FAILURE,
                DiagnosticCode.DELIVERY_UNREACHABLE,
                DiagnosticCode.TRANSFER_FAILED,
            )

        // Act / Assert
        failureCodes.forEach { code ->
            assertEquals(DiagnosticSeverity.ERROR, DiagnosticCatalog.severityFor(code), code.name)
        }
    }
}
