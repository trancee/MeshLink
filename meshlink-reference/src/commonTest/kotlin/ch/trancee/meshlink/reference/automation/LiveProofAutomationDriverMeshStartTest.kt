package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.timeline.TechnicalTimelineUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow

class LiveProofAutomationDriverMeshStartTest {
    @Test
    fun driverRequestsMeshStartFromTheTimelineStateWithoutCompose() {
        // Arrange
        val automationConfig =
            ReferenceAutomationConfig(
                mode = ReferenceAutomationMode.LIVE_PROOF,
                role = ReferenceAutomationRole.PASSIVE,
                appId = "demo.meshlink.reference.test",
                storageSubdirectory = "driver-mesh-start",
            )
        val timelineUiStateFlow =
            MutableStateFlow(
                TechnicalTimelineUiState(
                    liveSnapshot =
                        automationTestSnapshot(
                            meshStateLabel = "Uninitialized",
                            timeline = emptyList(),
                        )
                )
            )
        val actions = RecordingLiveProofAutomationActions()
        val driver =
            LiveProofAutomationDriver(
                automationConfig = automationConfig,
                timelineUiStateFlow = timelineUiStateFlow,
                actions = actions,
            )

        // Act
        driver.handle(timelineUiStateFlow.value)

        // Assert
        assertEquals(1, actions.meshStartRequests)
        assertTrue(actions.logs.any { log -> log.contains("REFERENCE_AUTOMATION started") })
        assertTrue(
            actions.logs.any { log ->
                log.contains("REFERENCE_AUTOMATION mesh.start.requested role=PASSIVE")
            }
        )
    }
}
