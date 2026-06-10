package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineUiState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveProofAutomationPassiveStateTest {
    @Test
    fun passiveProofRetainAndExportOnlyAdvanceWhenPrerequisitesExist() {
        // Arrange
        val retainRequested = false
        val exportRequested = false

        // Act
        val retainReady =
            shouldRetainPassiveLiveProof(
                retainRequested = retainRequested,
                hasTrustEstablished = true,
                hasInboundMessage = true,
            )
        val retainBlocked =
            shouldRetainPassiveLiveProof(
                retainRequested = retainRequested,
                hasTrustEstablished = true,
                hasInboundMessage = false,
            )
        val exportReady =
            shouldExportPassiveLiveProof(
                retainRequested = true,
                exportRequested = exportRequested,
                retainedSessionCount = 1,
            )
        val exportBlocked =
            shouldExportPassiveLiveProof(
                retainRequested = true,
                exportRequested = exportRequested,
                retainedSessionCount = 0,
            )

        // Assert
        assertTrue(retainReady)
        assertFalse(retainBlocked)
        assertTrue(exportReady)
        assertFalse(exportBlocked)
    }

    @Test
    fun passiveBaselineDoesNotAdvanceWhenPrerequisitesAreMissing() {
        // Arrange
        val snapshot =
            ReferenceControllerSnapshot(
                session =
                    ReferenceSession(
                        sessionId = "session-passive-timeout",
                        scenarioId = "passive-proof",
                        authorityMode = ReferenceAuthorityMode.LIVE,
                        startedAtEpochMillis = 1L,
                    ),
                peers = emptyList(),
                timeline = emptyList(),
                activePowerModeLabel = "Automatic",
            )
        val timelineUiState =
            TechnicalTimelineUiState(
                liveSnapshot = snapshot,
                retainedSessions = emptyList(),
                lastExportPath = null,
            )
        val actions = RecordingLiveProofAutomationActions()
        val progress = LiveProofAutomationProgress()

        // Act
        runPassiveBaselineAutomationStep(
            snapshot = snapshot,
            timelineUiState = timelineUiState,
            actions = actions,
            progress = progress,
        )

        // Assert
        assertTrue(actions.logs.none { log -> log.contains("proof.complete") })
        assertTrue(actions.logs.none { log -> log.contains("export.requested") })
        assertTrue(actions.logs.none { log -> log.contains("sender.started") })
        assertTrue(actions.logs.none { log -> log.contains("peer.discovered") })
    }
}
