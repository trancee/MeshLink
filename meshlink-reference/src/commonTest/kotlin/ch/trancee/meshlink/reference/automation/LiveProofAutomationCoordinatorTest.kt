package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.timeline.TechnicalTimelineUiState
import kotlin.test.Test
import kotlin.test.assertTrue

class LiveProofAutomationCoordinatorTest {
    @Test
    fun mixedDirectGuidedSnapshotWithAVisiblePeerAnnouncesDiscovery() {
        // Arrange
        val automationConfig =
            ReferenceAutomationConfig(
                mode = ReferenceAutomationMode.LIVE_PROOF,
                role = ReferenceAutomationRole.SENDER,
                appId = "demo.meshlink.reference.test",
                storageSubdirectory = "coordinator-discovery",
                scenario = ReferenceAutomationScenario.DIRECT_GUIDED,
            )
        val actions = RecordingLiveProofAutomationActions()
        val progress = LiveProofAutomationProgress()
        val coordinator =
            LiveProofAutomationCoordinator(
                automationConfig = automationConfig,
                actions = actions,
                progress = progress,
            )
        val snapshot =
            automationTestSnapshot(
                peers = listOf(automationTestPeer(peerId = "peer-123456")),
            )
        val timelineUiState = TechnicalTimelineUiState(liveSnapshot = snapshot)

        // Act
        coordinator.run(snapshot = snapshot, timelineUiState = timelineUiState)

        // Assert
        assertTrue(
            actions.logs.any {
                log -> log.contains("REFERENCE_AUTOMATION peer.discovered role=SENDER peer=123456")
            }
        )
        assertTrue(actions.logs.any { log -> log.contains("REFERENCE_AUTOMATION peers role=SENDER count=1") })
    }

    @Test
    fun mixedDirectGuidedSnapshotWithoutPeersRequestsMeshStart() {
        // Arrange
        val automationConfig =
            ReferenceAutomationConfig(
                mode = ReferenceAutomationMode.LIVE_PROOF,
                role = ReferenceAutomationRole.PASSIVE,
                appId = "demo.meshlink.reference.test",
                storageSubdirectory = "coordinator-mesh-start",
                scenario = ReferenceAutomationScenario.DIRECT_GUIDED,
            )
        val actions = RecordingLiveProofAutomationActions()
        val progress = LiveProofAutomationProgress()
        val coordinator =
            LiveProofAutomationCoordinator(
                automationConfig = automationConfig,
                actions = actions,
                progress = progress,
            )
        val snapshot = automationTestSnapshot()
        val timelineUiState = TechnicalTimelineUiState(liveSnapshot = snapshot)

        // Act
        coordinator.run(snapshot = snapshot, timelineUiState = timelineUiState)

        // Assert
        assertTrue(actions.meshStartRequests == 1)
        assertTrue(actions.logs.any { log -> log.contains("REFERENCE_AUTOMATION mesh.start.requested role=PASSIVE") })
    }
}
