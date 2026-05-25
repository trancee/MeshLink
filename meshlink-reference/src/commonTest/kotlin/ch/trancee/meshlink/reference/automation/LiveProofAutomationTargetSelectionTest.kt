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

class LiveProofAutomationTargetSelectionTest {
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
}
