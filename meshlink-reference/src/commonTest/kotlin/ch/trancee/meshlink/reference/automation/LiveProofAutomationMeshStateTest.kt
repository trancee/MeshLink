package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.redactedSuffix
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveProofAutomationMeshStateTest {
    @Test
    fun terminalSenderFailureOutcomeMatchesNotSentSummaries() {
        // Arrange
        val failureSummary = "SendResult.NotSent(UNREACHABLE)"
        val successSummary = "SendResult.Sent"

        // Act
        val failureDetected = isTerminalSenderFailureOutcome(failureSummary)
        val successDetected = isTerminalSenderFailureOutcome(successSummary)

        // Assert
        assertTrue(failureDetected)
        assertFalse(successDetected)
    }

    @Test
    fun senderBootstrapAndTargetSendWaitForTheRunningMeshState() {
        // Arrange
        val startingState = "Uninitialized"
        val runningState = "Running"

        // Act
        val blocked = isMeshRunning(startingState)
        val allowed = isMeshRunning(runningState)

        // Assert
        assertFalse(blocked)
        assertTrue(allowed)
    }

    @Test
    fun meshStartWaitsUntilNoLifecycleStateOrStartupBlockerIsPresent() {
        // Arrange
        val snapshot = automationTestSnapshot(meshStateLabel = "Uninitialized")

        // Act
        val allowed =
            shouldRequestLiveProofMeshStart(
                meshStartRequested = false,
                snapshot = snapshot,
                readinessBlockers = emptyList(),
                benchmarkTransport = "meshlink",
            )
        val blockedByState =
            shouldRequestLiveProofMeshStart(
                meshStartRequested = false,
                snapshot =
                    snapshot.copy(session = snapshot.session.copy(meshStateLabel = "Running")),
                readinessBlockers = emptyList(),
                benchmarkTransport = "meshlink",
            )
        val blockedByReadiness =
            shouldRequestLiveProofMeshStart(
                meshStartRequested = false,
                snapshot = snapshot,
                readinessBlockers = listOf("Enable Bluetooth"),
                benchmarkTransport = "meshlink",
            )
        val blockedByTransport =
            shouldRequestLiveProofMeshStart(
                meshStartRequested = false,
                snapshot = snapshot,
                readinessBlockers = emptyList(),
                benchmarkTransport = "gatt-notify",
            )

        // Assert
        assertTrue(allowed)
        assertFalse(blockedByState)
        assertFalse(blockedByReadiness)
        assertFalse(blockedByTransport)
    }

    @Test
    fun lifecycleHelpersRecognizePauseAndResumeOutcomes() {
        // Arrange
        val pausedSnapshot =
            automationTestSnapshot(
                meshStateLabel = "Paused",
                lastOutcomeSummary = "PauseResult.Paused",
                timeline =
                    listOf(
                        automationTestEntry(
                            title = "Mesh paused",
                            detail = "mesh.pause() -> Paused",
                            family = TimelineFamily.LIFECYCLE,
                            severity = TimelineSeverity.SUCCESS,
                        )
                    ),
            )
        val resumedSnapshot =
            pausedSnapshot.copy(
                session =
                    pausedSnapshot.session.copy(
                        meshStateLabel = "Running",
                        lastOutcomeSummary = "ResumeResult.Resumed",
                    ),
                timeline =
                    pausedSnapshot.timeline +
                        automationTestEntry(
                            entryId = "session-1-2",
                            title = "Mesh resumed",
                            detail = "mesh.resume() -> Resumed",
                            family = TimelineFamily.LIFECYCLE,
                            severity = TimelineSeverity.SUCCESS,
                            occurredAtEpochMillis = 3L,
                        ),
            )

        // Act
        val pauseObserved = hasPauseObserved(pausedSnapshot)
        val resumeObserved = hasResumeObserved(resumedSnapshot)

        // Assert
        assertTrue(pauseObserved)
        assertTrue(resumeObserved)
    }

    @Test
    fun pauseResumeAutomationWaitsForARouteBeforePausingAndOnlySendsAgainAfterResume() {
        // Arrange
        val peerId = "peer-selected-abcdef"
        val resumedSnapshotWithoutRoute = automationTestSnapshot(meshStateLabel = "Running")
        val resumedSnapshotWithRoute =
            resumedSnapshotWithoutRoute.copy(
                timeline =
                    listOf(
                        automationTestEntry(
                            title = "ROUTE_DISCOVERED",
                            detail =
                                "ROUTE_DISCOVERED @ transport.handshake.message2.complete.routeAvailable {peerId=$peerId, routeAvailable=true}",
                            family = TimelineFamily.DIAGNOSTIC,
                            peerSuffix = redactedSuffix(peerId),
                        )
                    )
            )

        // Act
        val pauseBlocked =
            shouldRequestPauseForPauseResume(
                pauseRequested = false,
                snapshot = resumedSnapshotWithoutRoute,
                targetPeerId = peerId,
            )
        val pauseAllowed =
            shouldRequestPauseForPauseResume(
                pauseRequested = false,
                snapshot = resumedSnapshotWithRoute,
                targetPeerId = peerId,
            )
        val sendBlocked =
            shouldSendAfterPauseResumeRecovery(
                resumeObserved = false,
                snapshot = resumedSnapshotWithRoute,
            )
        val sendAllowed =
            shouldSendAfterPauseResumeRecovery(
                resumeObserved = true,
                snapshot = resumedSnapshotWithoutRoute,
            )

        // Assert
        assertFalse(pauseBlocked)
        assertTrue(pauseAllowed)
        assertFalse(sendBlocked)
        assertTrue(sendAllowed)
    }

    @Test
    fun largeTransferPayloadBuilderProducesAnOversizedPhysicalPayload() {
        // Arrange
        val platformName = "Android"

        // Act
        val payload = buildLargeTransferPayload(platformName)
        val payloadBytes = payload.encodeToByteArray().size

        // Assert
        assertTrue(payloadBytes > 4_096)
        assertTrue(payload.contains(platformName))
    }
}
