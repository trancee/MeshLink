package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.redactedSuffix
import ch.trancee.meshlink.reference.model.TimelineFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LiveProofAutomationRoutedTargetSelectionTest {
    @Test
    fun bootstrapSelectsTheFirstDirectPeerWhileARoutedTargetIsStillUnavailable() {
        // Arrange
        val routedPeerId = "peer-routed-target-abcdef"
        val snapshot =
            automationTestSnapshot(
                peers = listOf(automationTestPeer(peerId = "peer-direct-1", peerSuffix = "abc123"))
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
            automationTestSnapshot(
                peers = listOf(automationTestPeer(peerId = "peer-direct-1", peerSuffix = "abc123")),
                timeline =
                    listOf(
                        automationTestEntry(
                            title = "ROUTE_DISCOVERED",
                            detail =
                                "ROUTE_DISCOVERED @ routing.routeAvailable {peerId=$routedPeerId,routeAvailable=true,nextHopPeerId=peer-direct-1,routeIsDirect=false}",
                            family = TimelineFamily.DIAGNOSTIC,
                            peerSuffix = redactedSuffix(routedPeerId),
                        )
                    ),
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
            automationTestSnapshot(
                peers = listOf(automationTestPeer(peerId = "peer-direct-1", peerSuffix = "abc123")),
                timeline =
                    listOf(
                        automationTestEntry(
                            title = "ROUTE_DISCOVERED",
                            detail =
                                "ROUTE_DISCOVERED @ routing.routeAvailable {peerId=$routedPeerId,routeAvailable=true,nextHopPeerId=peer-direct-1,routeIsDirect=false}",
                            family = TimelineFamily.DIAGNOSTIC,
                            peerSuffix = redactedSuffix(routedPeerId),
                        )
                    ),
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
