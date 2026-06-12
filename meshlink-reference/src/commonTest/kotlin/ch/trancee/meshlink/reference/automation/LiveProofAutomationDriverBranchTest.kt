package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.timeline.TechnicalTimelineUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow

class LiveProofAutomationDriverBranchTest {
    @Test
    fun startDoesNothingWithoutAConfigOrWhenTheModeIsNotLiveProof() {
        // Arrange
        val noConfigActions = RecordingLiveProofAutomationActions()
        val noConfigDriver =
            LiveProofAutomationDriver(
                automationConfig = null,
                timelineUiStateFlow =
                    MutableStateFlow(
                        TechnicalTimelineUiState(liveSnapshot = automationTestSnapshot())
                    ),
                actions = noConfigActions,
            )
        val previewActions = RecordingLiveProofAutomationActions()
        val previewDriver =
            LiveProofAutomationDriver(
                automationConfig =
                    ReferenceAutomationConfig(
                        mode = ReferenceAutomationMode.SCRIPTED_UI,
                        role = ReferenceAutomationRole.SENDER,
                        appId = "demo.meshlink.reference.preview",
                        storageSubdirectory = "branch-preview",
                    ),
                timelineUiStateFlow =
                    MutableStateFlow(
                        TechnicalTimelineUiState(liveSnapshot = automationTestSnapshot())
                    ),
                actions = previewActions,
            )

        // Act
        noConfigDriver.start()
        previewDriver.start()

        // Assert
        assertTrue(noConfigActions.logs.isEmpty())
        assertTrue(noConfigActions.meshStartRequests == 0)
        assertTrue(previewActions.logs.isEmpty())
        assertTrue(previewActions.meshStartRequests == 0)
    }

    @Test
    fun senderStartRequestsMeshStartOnlyOnce() {
        // Arrange
        val timelineUiStateFlow =
            MutableStateFlow(TechnicalTimelineUiState(liveSnapshot = automationTestSnapshot()))
        val actions = RecordingLiveProofAutomationActions()
        val driver =
            LiveProofAutomationDriver(
                automationConfig =
                    ReferenceAutomationConfig(
                        mode = ReferenceAutomationMode.LIVE_PROOF,
                        role = ReferenceAutomationRole.SENDER,
                        appId = "demo.meshlink.reference.sender",
                        storageSubdirectory = "branch-sender",
                    ),
                timelineUiStateFlow = timelineUiStateFlow,
                actions = actions,
            )

        // Act
        driver.start()
        driver.start()

        // Assert
        assertEquals(1, actions.meshStartRequests)
        assertTrue(actions.logs.any { log -> log.contains("mesh.start.requested role=SENDER") })
    }

    @Test
    fun senderHandleWithNoPeersLogsWaitingAndRequestsBootstrapWhenTargetPeerIsExplicit() {
        // Arrange
        val automationConfig =
            ReferenceAutomationConfig(
                mode = ReferenceAutomationMode.LIVE_PROOF,
                role = ReferenceAutomationRole.SENDER,
                appId = "demo.meshlink.reference.sender",
                storageSubdirectory = "branch-bootstrap",
                targetPeerId = "peer-123456",
            )
        val snapshot =
            automationTestSnapshot(
                meshStateLabel = "Running",
                peers = listOf(automationTestPeer(peerId = "peer-bootstrap-1")),
                timeline = emptyList(),
            )
        val timelineUiStateFlow =
            MutableStateFlow(TechnicalTimelineUiState(liveSnapshot = snapshot))
        val actions = RecordingLiveProofAutomationActions()
        val driver =
            LiveProofAutomationDriver(
                automationConfig = automationConfig,
                timelineUiStateFlow = timelineUiStateFlow,
                actions = actions,
            )

        // Act
        driver.handle(TechnicalTimelineUiState(liveSnapshot = snapshot))

        // Assert
        assertTrue(
            actions.logs.any { log -> log.contains("sender.waiting role=sender reason=no-peers") }
        )
        assertTrue(actions.logs.any { log -> log.contains("bootstrap.requested role=sender") })
        assertEquals(1, actions.sendRequests.size)
        assertEquals("peer-bootstrap-1", actions.sendRequests.single().peerId)
        assertTrue(actions.sendRequests.single().payloadText.contains("hello mesh from Android"))
        assertEquals(
            ch.trancee.meshlink.api.DeliveryPriority.NORMAL,
            actions.sendRequests.single().priority,
        )
    }
}
