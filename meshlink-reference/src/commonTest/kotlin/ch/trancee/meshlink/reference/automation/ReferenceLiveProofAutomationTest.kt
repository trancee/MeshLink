package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.redactedSuffix
import ch.trancee.meshlink.reference.model.PeerConnectionSnapshotState
import ch.trancee.meshlink.reference.model.PeerSnapshot
import ch.trancee.meshlink.reference.model.PeerTrustState
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReferenceLiveProofAutomationTest {
    @Test
    fun autoSendStartsOnceTargetPeerIndexExists() {
        // Arrange
        val snapshot =
            ReferenceControllerSnapshot(
                session =
                    ReferenceSession(
                        sessionId = "session-1",
                        scenarioId = "guided-first-exchange",
                        authorityMode = ReferenceAuthorityMode.LIVE,
                        startedAtEpochMillis = 1L,
                    ),
                peers =
                    listOf(
                        PeerSnapshot(
                            peerId = "peer-1",
                            peerSuffix = "abc123",
                            trustState = PeerTrustState.UNKNOWN,
                            connectionState = PeerConnectionSnapshotState.CONNECTED,
                        ),
                        PeerSnapshot(
                            peerId = "peer-2",
                            peerSuffix = "def456",
                            trustState = PeerTrustState.UNKNOWN,
                            connectionState = PeerConnectionSnapshotState.CONNECTED,
                        ),
                    ),
                timeline = emptyList(),
                activePowerModeLabel = "Automatic",
            )

        // Act
        val actual =
            shouldAutoSendGuidedHello(
                snapshot = snapshot,
                requiredPeerCount = 2,
                targetPeerIndex = 1,
            )

        // Assert
        assertTrue(actual)
    }

    @Test
    fun autoSendWaitsWhenRequiredPeerCountOrTargetIndexIsMissing() {
        // Arrange
        val snapshot =
            ReferenceControllerSnapshot(
                session =
                    ReferenceSession(
                        sessionId = "session-1",
                        scenarioId = "guided-first-exchange",
                        authorityMode = ReferenceAuthorityMode.LIVE,
                        startedAtEpochMillis = 1L,
                    ),
                peers =
                    listOf(
                        PeerSnapshot(
                            peerId = "peer-1",
                            peerSuffix = "abc123",
                            trustState = PeerTrustState.UNKNOWN,
                            connectionState = PeerConnectionSnapshotState.CONNECTED,
                        )
                    ),
                timeline = emptyList(),
                activePowerModeLabel = "Automatic",
            )

        // Act
        val blockedByPeerCount =
            shouldAutoSendGuidedHello(
                snapshot = snapshot,
                requiredPeerCount = 2,
                targetPeerIndex = 0,
            )
        val blockedByTargetIndex =
            shouldAutoSendGuidedHello(
                snapshot = snapshot,
                requiredPeerCount = 1,
                targetPeerIndex = 1,
            )

        // Assert
        assertFalse(blockedByPeerCount)
        assertFalse(blockedByTargetIndex)
    }

    @Test
    fun autoSendDoesNotFallBackToADirectPeerWhenARoutedTargetIsConfiguredButUnavailable() {
        // Arrange
        val routedPeerId = "peer-routed-target-abcdef"
        val snapshot =
            ReferenceControllerSnapshot(
                session =
                    ReferenceSession(
                        sessionId = "session-1",
                        scenarioId = "guided-first-exchange",
                        authorityMode = ReferenceAuthorityMode.LIVE,
                        startedAtEpochMillis = 1L,
                    ),
                peers =
                    listOf(
                        PeerSnapshot(
                            peerId = "peer-direct-1",
                            peerSuffix = "abc123",
                            trustState = PeerTrustState.UNKNOWN,
                            connectionState = PeerConnectionSnapshotState.CONNECTED,
                        )
                    ),
                timeline = emptyList(),
                activePowerModeLabel = "Automatic",
            )

        // Act
        val actual =
            shouldAutoSendGuidedHello(
                snapshot = snapshot,
                requiredPeerCount = 1,
                targetPeerIndex = 0,
                targetPeerId = routedPeerId,
            )
        val selectedTarget =
            autoSendTargetPeer(
                snapshot = snapshot,
                requiredPeerCount = 1,
                targetPeerIndex = 0,
                targetPeerId = routedPeerId,
            )

        // Assert
        assertFalse(actual)
        assertTrue(selectedTarget == null)
    }

    @Test
    fun bootstrapSelectsTheFirstDirectPeerWhileARoutedTargetIsStillUnavailable() {
        // Arrange
        val routedPeerId = "peer-routed-target-abcdef"
        val snapshot =
            ReferenceControllerSnapshot(
                session =
                    ReferenceSession(
                        sessionId = "session-1",
                        scenarioId = "guided-first-exchange",
                        authorityMode = ReferenceAuthorityMode.LIVE,
                        startedAtEpochMillis = 1L,
                    ),
                peers =
                    listOf(
                        PeerSnapshot(
                            peerId = "peer-direct-1",
                            peerSuffix = "abc123",
                            trustState = PeerTrustState.UNKNOWN,
                            connectionState = PeerConnectionSnapshotState.CONNECTED,
                        )
                    ),
                timeline = emptyList(),
                activePowerModeLabel = "Automatic",
            )

        // Act
        val bootstrapPeer = bootstrapTargetPeer(snapshot = snapshot, targetPeerId = routedPeerId)

        // Assert
        assertNotNull(bootstrapPeer)
        assertEquals("peer-direct-1", bootstrapPeer.peerId)
        assertEquals("abc123", bootstrapPeer.peerSuffix)
    }

    @Test
    fun bootstrapWaitsOnceTheRoutedTargetAlreadyHasARoute() {
        // Arrange
        val routedPeerId = "peer-routed-target-abcdef"
        val snapshot =
            ReferenceControllerSnapshot(
                session =
                    ReferenceSession(
                        sessionId = "session-1",
                        scenarioId = "guided-first-exchange",
                        authorityMode = ReferenceAuthorityMode.LIVE,
                        startedAtEpochMillis = 1L,
                    ),
                peers =
                    listOf(
                        PeerSnapshot(
                            peerId = "peer-direct-1",
                            peerSuffix = "abc123",
                            trustState = PeerTrustState.UNKNOWN,
                            connectionState = PeerConnectionSnapshotState.CONNECTED,
                        )
                    ),
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
                                "ROUTE_DISCOVERED @ routing.routeAvailable {peerId=$routedPeerId,routeAvailable=true,nextHopPeerId=peer-direct-1,routeIsDirect=false}",
                            peerSuffix = redactedSuffix(routedPeerId),
                        )
                    ),
                activePowerModeLabel = "Automatic",
            )

        // Act
        val bootstrapPeer = bootstrapTargetPeer(snapshot = snapshot, targetPeerId = routedPeerId)

        // Assert
        assertTrue(bootstrapPeer == null)
    }

    @Test
    fun autoSendCanTargetARoutedPeerIdOnceRouteDiagnosticsAppear() {
        // Arrange
        val routedPeerId = "peer-routed-target-abcdef"
        val snapshot =
            ReferenceControllerSnapshot(
                session =
                    ReferenceSession(
                        sessionId = "session-1",
                        scenarioId = "guided-first-exchange",
                        authorityMode = ReferenceAuthorityMode.LIVE,
                        startedAtEpochMillis = 1L,
                    ),
                peers =
                    listOf(
                        PeerSnapshot(
                            peerId = "peer-direct-1",
                            peerSuffix = "abc123",
                            trustState = PeerTrustState.UNKNOWN,
                            connectionState = PeerConnectionSnapshotState.CONNECTED,
                        )
                    ),
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
                                "ROUTE_DISCOVERED @ routing.routeAvailable {peerId=$routedPeerId,routeAvailable=true,nextHopPeerId=peer-direct-1,routeIsDirect=false}",
                            peerSuffix = redactedSuffix(routedPeerId),
                        )
                    ),
                activePowerModeLabel = "Automatic",
            )

        // Act
        val actual =
            shouldAutoSendGuidedHello(
                snapshot = snapshot,
                requiredPeerCount = 1,
                targetPeerIndex = 0,
                targetPeerId = routedPeerId,
            )
        val selectedTarget =
            autoSendTargetPeer(
                snapshot = snapshot,
                requiredPeerCount = 1,
                targetPeerIndex = 0,
                targetPeerId = routedPeerId,
            )

        // Assert
        assertTrue(actual)
        assertNotNull(selectedTarget)
        assertEquals(routedPeerId, selectedTarget.peerId)
        assertEquals(redactedSuffix(routedPeerId), selectedTarget.peerSuffix)
    }

    @Test
    fun latestAutomationObservationUsesTheNewestMatchingPeerEntry() {
        // Arrange
        val routedPeerId = "peer-routed-target-abcdef"
        val targetPeerSuffix = redactedSuffix(routedPeerId)
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
                            title = "ROUTE_DISCOVERED",
                            detail = "route available",
                            peerSuffix = targetPeerSuffix,
                        ),
                        TimelineEntry(
                            entryId = "session-1-2",
                            sessionId = "session-1",
                            occurredAtEpochMillis = 3L,
                            family = TimelineFamily.MESSAGE,
                            severity = TimelineSeverity.ERROR,
                            title = "Guided message not sent",
                            detail = "send unreachable",
                            peerSuffix = targetPeerSuffix,
                        ),
                    ),
                activePowerModeLabel = "Automatic",
            )

        // Act
        val observation =
            latestAutomationObservation(snapshot = snapshot, peerSuffix = targetPeerSuffix)

        // Assert
        assertNotNull(observation)
        assertEquals("Guided message not sent", observation.title)
        assertEquals("send unreachable", observation.detail)
    }

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
    fun inboundHelpersTrackRecoveryCountsAndLargestPayloadBytes() {
        // Arrange
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
                            family = TimelineFamily.MESSAGE,
                            severity = TimelineSeverity.SUCCESS,
                            title = "Inbound message",
                            detail = "Received 32 bytes from abc123.",
                            peerSuffix = "abc123",
                            payloadSizeBytes = 32,
                        ),
                        TimelineEntry(
                            entryId = "session-1-2",
                            sessionId = "session-1",
                            occurredAtEpochMillis = 3L,
                            family = TimelineFamily.MESSAGE,
                            severity = TimelineSeverity.SUCCESS,
                            title = "Inbound message",
                            detail = "Received 8192 bytes from abc123.",
                            peerSuffix = "abc123",
                            payloadSizeBytes = 8_192,
                        ),
                    ),
                activePowerModeLabel = "Automatic",
            )

        // Act
        val inboundCount = timelineEntryCount(snapshot, title = "Inbound message")
        val largestInboundBytes = largestInboundPayloadBytes(snapshot)

        // Assert
        assertEquals(2, inboundCount)
        assertEquals(8_192, largestInboundBytes)
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

    @Test
    fun automationScenarioParserMapsWireValues() {
        // Arrange
        val largeTransferWireValue = "direct-large-transfer"
        val relayWireValue = "relay-constrained"

        // Act
        val largeTransferScenario = largeTransferWireValue.toReferenceAutomationScenario()
        val relayScenario = relayWireValue.toReferenceAutomationScenario()

        // Assert
        assertEquals(ReferenceAutomationScenario.DIRECT_LARGE_TRANSFER, largeTransferScenario)
        assertEquals(ReferenceAutomationScenario.RELAY_CONSTRAINED, relayScenario)
    }
}
