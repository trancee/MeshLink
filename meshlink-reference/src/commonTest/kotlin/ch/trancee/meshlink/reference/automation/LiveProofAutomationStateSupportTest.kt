package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.redactedSuffix
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveProofAutomationStateSupportTest {
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
        val snapshot =
            ReferenceControllerSnapshot(
                session =
                    ReferenceSession(
                        sessionId = "session-1",
                        scenarioId = "guided-first-exchange",
                        authorityMode = ReferenceAuthorityMode.LIVE,
                        startedAtEpochMillis = 1L,
                        meshStateLabel = "Uninitialized",
                    ),
                peers = emptyList(),
                timeline = emptyList(),
                activePowerModeLabel = "Automatic",
            )

        // Act
        val allowed =
            shouldRequestLiveProofMeshStart(
                meshStartRequested = false,
                snapshot = snapshot,
                readinessBlockers = emptyList(),
            )
        val blockedByState =
            shouldRequestLiveProofMeshStart(
                meshStartRequested = false,
                snapshot =
                    snapshot.copy(session = snapshot.session.copy(meshStateLabel = "Running")),
                readinessBlockers = emptyList(),
            )
        val blockedByReadiness =
            shouldRequestLiveProofMeshStart(
                meshStartRequested = false,
                snapshot = snapshot,
                readinessBlockers = listOf("Enable Bluetooth"),
            )

        // Assert
        assertTrue(allowed)
        assertFalse(blockedByState)
        assertFalse(blockedByReadiness)
    }

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
                retainRequested = false,
                exportRequested = exportRequested,
                retainedSessionCount = 1,
            )

        // Assert
        assertTrue(retainReady)
        assertFalse(retainBlocked)
        assertTrue(exportReady)
        assertFalse(exportBlocked)
    }

    @Test
    fun lifecycleHelpersRecognizePauseAndResumeOutcomes() {
        // Arrange
        val pausedSnapshot =
            ReferenceControllerSnapshot(
                session =
                    ReferenceSession(
                        sessionId = "session-1",
                        scenarioId = "guided-first-exchange",
                        authorityMode = ReferenceAuthorityMode.LIVE,
                        startedAtEpochMillis = 1L,
                        meshStateLabel = "Paused",
                        lastOutcomeSummary = "PauseResult.Paused",
                    ),
                peers = emptyList(),
                timeline =
                    listOf(
                        TimelineEntry(
                            entryId = "session-1-1",
                            sessionId = "session-1",
                            occurredAtEpochMillis = 2L,
                            family = TimelineFamily.LIFECYCLE,
                            severity = TimelineSeverity.SUCCESS,
                            title = "Mesh paused",
                            detail = "mesh.pause() -> Paused",
                        )
                    ),
                activePowerModeLabel = "Automatic",
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
                        TimelineEntry(
                            entryId = "session-1-2",
                            sessionId = "session-1",
                            occurredAtEpochMillis = 3L,
                            family = TimelineFamily.LIFECYCLE,
                            severity = TimelineSeverity.SUCCESS,
                            title = "Mesh resumed",
                            detail = "mesh.resume() -> Resumed",
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
        val resumedSnapshotWithoutRoute =
            ReferenceControllerSnapshot(
                session =
                    ReferenceSession(
                        sessionId = "session-1",
                        scenarioId = "guided-first-exchange",
                        authorityMode = ReferenceAuthorityMode.LIVE,
                        startedAtEpochMillis = 1L,
                        meshStateLabel = "Running",
                    ),
                peers = emptyList(),
                timeline = emptyList(),
                activePowerModeLabel = "Automatic",
            )
        val resumedSnapshotWithRoute =
            resumedSnapshotWithoutRoute.copy(
                timeline =
                    listOf(
                        TimelineEntry(
                            entryId = "session-1-1",
                            sessionId = "session-1",
                            occurredAtEpochMillis = 2L,
                            family = TimelineFamily.DIAGNOSTIC,
                            severity = TimelineSeverity.INFO,
                            title = "ROUTE_DISCOVERED",
                            detail =
                                "ROUTE_DISCOVERED @ transport.handshake.message2.complete.routeAvailable {peerId=$peerId, routeAvailable=true}",
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
    fun trustResetHelperFindsResetEventsForTheSelectedPeer() {
        // Arrange
        val peerId = "peer-selected-abcdef"
        val snapshot =
            ReferenceControllerSnapshot(
                session =
                    ReferenceSession(
                        sessionId = "session-1",
                        scenarioId = "guided-first-exchange",
                        authorityMode = ReferenceAuthorityMode.LIVE,
                        startedAtEpochMillis = 1L,
                    ),
                peers = emptyList(),
                timeline =
                    listOf(
                        TimelineEntry(
                            entryId = "session-1-1",
                            sessionId = "session-1",
                            occurredAtEpochMillis = 2L,
                            family = TimelineFamily.PEER,
                            severity = TimelineSeverity.SUCCESS,
                            title = "Peer trust reset",
                            detail = "forgetPeer(${redactedSuffix(peerId)}) -> Forgotten",
                            peerSuffix = redactedSuffix(peerId),
                        )
                    ),
                activePowerModeLabel = "Automatic",
            )

        // Act
        val actual = hasPeerTrustReset(snapshot, peerSuffix = redactedSuffix(peerId))
        val recoveryReady =
            hasTrustResetRecoveryReady(snapshot, peerSuffix = redactedSuffix(peerId))

        // Assert
        assertTrue(actual)
        assertTrue(recoveryReady)
    }

    @Test
    fun trustResetRecoveryAlsoAcceptsRouteRetractionForTheSelectedPeer() {
        // Arrange
        val peerId = "peer-selected-abcdef"
        val snapshot =
            ReferenceControllerSnapshot(
                session =
                    ReferenceSession(
                        sessionId = "session-1",
                        scenarioId = "guided-first-exchange",
                        authorityMode = ReferenceAuthorityMode.LIVE,
                        startedAtEpochMillis = 1L,
                    ),
                peers = emptyList(),
                timeline =
                    listOf(
                        TimelineEntry(
                            entryId = "session-1-1",
                            sessionId = "session-1",
                            occurredAtEpochMillis = 2L,
                            family = TimelineFamily.DIAGNOSTIC,
                            severity = TimelineSeverity.INFO,
                            title = "ROUTE_RETRACTED",
                            detail = "ROUTE_RETRACTED @ trust.forgetPeer.routeRetracted",
                            peerSuffix = redactedSuffix(peerId),
                        )
                    ),
                activePowerModeLabel = "Automatic",
            )

        // Act
        val recoveryReady =
            hasTrustResetRecoveryReady(snapshot, peerSuffix = redactedSuffix(peerId))

        // Assert
        assertTrue(recoveryReady)
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
