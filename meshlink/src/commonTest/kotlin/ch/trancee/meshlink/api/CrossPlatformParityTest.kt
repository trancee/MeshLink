package ch.trancee.meshlink.api

import ch.trancee.meshlink.config.PowerMode
import ch.trancee.meshlink.config.meshLinkConfig
import ch.trancee.meshlink.diagnostics.DiagnosticCatalog
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class CrossPlatformParityTest {
    @Test
    fun `android and ios expose matching lifecycle transitions and power diagnostic payloads`() =
        runBlocking {
            // Arrange
            installFactoryTestBridges()
            val config = meshLinkConfig {
                appId = "parity.meshlink.${Random.nextInt()}"
                powerMode = PowerMode.Automatic
            }
            val androidApi = createAndroidFactoryParityApi(config = config)
            val iosApi = createIosFactoryParityApi(config = config)
            val androidDiagnosticsDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(1_000) { androidApi.diagnosticEvents.take(5).toList() }
                }
            val iosDiagnosticsDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(1_000) { iosApi.diagnosticEvents.take(5).toList() }
                }

            // Act
            val androidResults =
                listOf(
                    androidApi.start(),
                    androidApi
                        .also {
                            it.updateBattery(BatterySnapshot(level = 0.20f, isCharging = false))
                        }
                        .state
                        .value,
                    androidApi.pause(),
                    androidApi.resume(),
                    androidApi.stop(),
                )
            val iosResults =
                listOf(
                    iosApi.start(),
                    iosApi
                        .also {
                            it.updateBattery(BatterySnapshot(level = 0.20f, isCharging = false))
                        }
                        .state
                        .value,
                    iosApi.pause(),
                    iosApi.resume(),
                    iosApi.stop(),
                )
            val androidDiagnostics = androidDiagnosticsDeferred.await()
            val iosDiagnostics = iosDiagnosticsDeferred.await()

            // Assert
            assertEquals(
                androidResults.map { it::class.simpleName },
                iosResults.map { it::class.simpleName },
            )
            assertEquals(androidDiagnostics.map { it.code }, iosDiagnostics.map { it.code })
            assertEquals(androidDiagnostics.map { it.severity }, iosDiagnostics.map { it.severity })
            assertEquals(androidDiagnostics.map { it.stage }, iosDiagnostics.map { it.stage })
            assertEquals(androidDiagnostics.map { it.reason }, iosDiagnostics.map { it.reason })
            assertEquals(
                androidDiagnostics.last().metadata.keys,
                iosDiagnostics.last().metadata.keys,
            )
            assertEquals(
                androidDiagnostics.last().metadata["tier"],
                iosDiagnostics.last().metadata["tier"],
            )
        }

    @Test
    fun `shared diagnostic and failure catalogs stay aligned`() {
        // Arrange
        val expectedCodes =
            listOf(
                "MESH_STARTED",
                "MESH_PAUSED",
                "MESH_RESUMED",
                "MESH_STOPPED",
                "TRUST_ESTABLISHED",
                "TRUST_FAILURE",
                "HOP_SESSION_ESTABLISHED",
                "HOP_SESSION_FAILED",
                "ROUTE_DISCOVERED",
                "ROUTE_UPDATED",
                "ROUTE_RETRACTED",
                "ROUTE_EXPIRED",
                "ROUTE_CONVERGED",
                "NO_ROUTE_AVAILABLE",
                "DELIVERY_QUEUED",
                "DELIVERY_RETRY_SCHEDULED",
                "DELIVERY_RETRYING",
                "DELIVERY_SUCCEEDED",
                "DELIVERY_UNREACHABLE",
                "TRANSFER_STARTED",
                "TRANSFER_PROGRESS",
                "TRANSFER_COMPLETED",
                "TRANSFER_FAILED",
                "SIZE_LIMIT_REJECTED",
                "TRANSPORT_MODE_CHANGED",
                "POWER_MODE_CHANGED",
            )
        val expectedSeverities =
            mapOf(
                DiagnosticCode.MESH_STARTED to DiagnosticSeverity.INFO,
                DiagnosticCode.MESH_PAUSED to DiagnosticSeverity.INFO,
                DiagnosticCode.MESH_RESUMED to DiagnosticSeverity.INFO,
                DiagnosticCode.MESH_STOPPED to DiagnosticSeverity.INFO,
                DiagnosticCode.TRUST_ESTABLISHED to DiagnosticSeverity.INFO,
                DiagnosticCode.TRUST_FAILURE to DiagnosticSeverity.ERROR,
                DiagnosticCode.HOP_SESSION_ESTABLISHED to DiagnosticSeverity.DEBUG,
                DiagnosticCode.HOP_SESSION_FAILED to DiagnosticSeverity.WARN,
                DiagnosticCode.ROUTE_DISCOVERED to DiagnosticSeverity.INFO,
                DiagnosticCode.ROUTE_UPDATED to DiagnosticSeverity.DEBUG,
                DiagnosticCode.ROUTE_RETRACTED to DiagnosticSeverity.WARN,
                DiagnosticCode.ROUTE_EXPIRED to DiagnosticSeverity.WARN,
                DiagnosticCode.ROUTE_CONVERGED to DiagnosticSeverity.INFO,
                DiagnosticCode.NO_ROUTE_AVAILABLE to DiagnosticSeverity.WARN,
                DiagnosticCode.DELIVERY_QUEUED to DiagnosticSeverity.INFO,
                DiagnosticCode.DELIVERY_RETRY_SCHEDULED to DiagnosticSeverity.WARN,
                DiagnosticCode.DELIVERY_RETRYING to DiagnosticSeverity.WARN,
                DiagnosticCode.DELIVERY_SUCCEEDED to DiagnosticSeverity.INFO,
                DiagnosticCode.DELIVERY_UNREACHABLE to DiagnosticSeverity.ERROR,
                DiagnosticCode.TRANSFER_STARTED to DiagnosticSeverity.INFO,
                DiagnosticCode.TRANSFER_PROGRESS to DiagnosticSeverity.DEBUG,
                DiagnosticCode.TRANSFER_COMPLETED to DiagnosticSeverity.INFO,
                DiagnosticCode.TRANSFER_FAILED to DiagnosticSeverity.ERROR,
                DiagnosticCode.SIZE_LIMIT_REJECTED to DiagnosticSeverity.WARN,
                DiagnosticCode.TRANSPORT_MODE_CHANGED to DiagnosticSeverity.INFO,
                DiagnosticCode.POWER_MODE_CHANGED to DiagnosticSeverity.INFO,
            )
        val expectedFailureReasons =
            listOf(
                "PAYLOAD_TOO_LARGE",
                "TRANSFER_TIMED_OUT",
                "TRANSFER_ABORTED",
                "UNREACHABLE",
                "TRUST_FAILURE",
            )
        val expectedExceptionCategories =
            listOf(
                "InvalidConfiguration",
                "InvalidStateTransition",
                "PermissionDenied",
                "TransportFailure",
                "StorageFailure",
                "CryptoFailure",
                "PlatformFailure",
            )

        // Act
        val actualCodes = DiagnosticCode.entries.map(DiagnosticCode::name)
        val actualSeverities = DiagnosticCode.entries.associateWith(DiagnosticCatalog::severityFor)
        val actualFailureReasons = SendFailureReason.entries.map(SendFailureReason::name)
        val actualExceptionCategories =
            listOf(
                    MeshLinkException.InvalidConfiguration("x"),
                    MeshLinkException.InvalidStateTransition("x"),
                    MeshLinkException.PermissionDenied("x"),
                    MeshLinkException.TransportFailure("x"),
                    MeshLinkException.StorageFailure("x"),
                    MeshLinkException.CryptoFailure("x"),
                    MeshLinkException.PlatformFailure("x"),
                )
                .mapNotNull { exception -> exception::class.simpleName }

        // Assert
        assertEquals(expectedCodes, actualCodes)
        assertEquals(expectedSeverities, actualSeverities)
        assertEquals(expectedFailureReasons, actualFailureReasons)
        assertEquals(expectedExceptionCategories, actualExceptionCategories)
    }
}
