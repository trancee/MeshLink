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
}

private fun referenceStartupPlatformServices(
    controller: RecordingReferenceMeshLinkController
): RecordingStartupPlatformServices {
    return RecordingStartupPlatformServices(controller = controller)
}

private class RecordingStartupPlatformServices(
    override val meshLinkController: RecordingReferenceMeshLinkController
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

    override fun start() {
        startCount += 1
    }
}
