package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.session.InMemoryReferenceDocumentStore
import ch.trancee.meshlink.reference.session.ReferenceDocumentStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher

class ReferenceStartupCoordinatorTest {
    @Test
    fun startLiveProofIsIdempotentAndRequestsMeshStartOnce() {
        // Arrange
        val controller = RecordingReferenceMeshLinkController()
        val services = referenceStartupPlatformServices(controller)
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val coordinator = ReferenceStartupCoordinator(platformServices = services, scope = scope)

        // Act
        coordinator.startLiveProofIfNeeded()
        coordinator.startLiveProofIfNeeded()

        // Assert
        assertEquals(1, controller.startCount)
        assertTrue(
            services.automationLogs.any { log ->
                log.contains("REFERENCE_AUTOMATION startup.coordinator.requested")
            }
        )
        assertTrue(
            services.automationLogs.any { log ->
                log.contains("REFERENCE_AUTOMATION startup.coordinator.dispatch")
            }
        )
    }

    @Test
    fun startLiveProofSkipsMeshStartForNonMeshlinkBenchmarkTransport() {
        // Arrange
        val controller = RecordingReferenceMeshLinkController()
        val services =
            referenceStartupPlatformServices(controller = controller, benchmarkTransport = "gatt")
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val coordinator = ReferenceStartupCoordinator(platformServices = services, scope = scope)

        // Act
        coordinator.startLiveProofIfNeeded()

        // Assert
        assertEquals(0, controller.startCount)
        assertTrue(
            services.automationLogs.any { log ->
                log.contains("REFERENCE_AUTOMATION startup.coordinator.skipped") &&
                    log.contains("benchmarkTransport=gatt") &&
                    log.contains("reason=non-meshlink-benchmark-transport")
            }
        )
    }
}

private fun referenceStartupPlatformServices(
    controller: RecordingReferenceMeshLinkController,
    benchmarkTransport: String = "meshlink",
): RecordingStartupPlatformServices {
    return RecordingStartupPlatformServices(
        meshLinkController = controller,
        benchmarkTransport = benchmarkTransport,
    )
}

private class RecordingStartupPlatformServices(
    override val meshLinkController: RecordingReferenceMeshLinkController,
    benchmarkTransport: String,
) : PlatformServices {
    override val platformName: String = "Test"
    override val defaultAuthorityMode: ReferenceAuthorityMode = ReferenceAuthorityMode.LIVE
    override val readinessGuidance: List<String> = emptyList()
    override val readinessBlockers: List<String> = emptyList()
    override val automationConfig: ReferenceAutomationConfig? =
        ReferenceAutomationConfig(
            mode = ReferenceAutomationMode.LIVE_PROOF,
            role = ReferenceAutomationRole.PASSIVE,
            appId = "demo.meshlink.reference.test",
            storageSubdirectory = "startup-coordinator-tests",
            benchmarkTransport = benchmarkTransport,
        )
    override val powerMitigationStatus: String? = null
    override val documentStore: ReferenceDocumentStore = InMemoryReferenceDocumentStore()
    val automationLogs: MutableList<String> = mutableListOf()

    override fun stopPowerMitigation() = Unit

    override fun currentTimeMillis(): Long = 0L

    override fun emitAutomationLog(message: String) {
        automationLogs += message
    }
}

private class RecordingReferenceMeshLinkController : ReferenceMeshLinkController {
    var startCount: Int = 0

    override val snapshot =
        kotlinx.coroutines.flow.MutableStateFlow(
            ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot(
                session =
                    ch.trancee.meshlink.reference.model.ReferenceSession(
                        sessionId = "session-test",
                        scenarioId = "startup-coordinator",
                        authorityMode = ReferenceAuthorityMode.LIVE,
                        startedAtEpochMillis = 0L,
                    ),
                peers = emptyList(),
                timeline = emptyList(),
                activePowerModeLabel = "Automatic",
            )
        )

    override suspend fun start() {
        startCount += 1
    }

    override suspend fun pause() = Unit

    override suspend fun resume() = Unit

    override suspend fun stop() = Unit

    override suspend fun sendPayload(
        peerId: String,
        payloadText: String,
        priority: ch.trancee.meshlink.api.DeliveryPriority,
    ) = Unit

    override suspend fun forgetPeer(peerId: String) = Unit
}
