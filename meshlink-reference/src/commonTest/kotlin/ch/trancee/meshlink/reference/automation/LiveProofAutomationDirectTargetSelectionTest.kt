package ch.trancee.meshlink.reference.automation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveProofAutomationDirectTargetSelectionTest {
    @Test
    fun autoSendStartsOnceTargetPeerIndexExists() {
        // Arrange
        val snapshot =
            automationTestSnapshot(
                peers =
                    listOf(
                        automationTestPeer(peerId = "peer-1", peerSuffix = "abc123"),
                        automationTestPeer(peerId = "peer-2", peerSuffix = "def456"),
                    )
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
            automationTestSnapshot(
                peers = listOf(automationTestPeer(peerId = "peer-1", peerSuffix = "abc123"))
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
            automationTestSnapshot(
                peers = listOf(automationTestPeer(peerId = "peer-direct-1", peerSuffix = "abc123"))
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
}
