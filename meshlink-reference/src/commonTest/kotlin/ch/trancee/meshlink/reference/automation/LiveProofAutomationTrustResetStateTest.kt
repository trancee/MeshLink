package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.redactedSuffix
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import kotlin.test.Test
import kotlin.test.assertTrue

class LiveProofAutomationTrustResetStateTest {
    @Test
    fun trustResetHelperFindsResetEventsForTheSelectedPeer() {
        // Arrange
        val peerId = "peer-selected-abcdef"
        val snapshot =
            automationTestSnapshot(
                timeline =
                    listOf(
                        automationTestEntry(
                            title = "Peer trust reset",
                            detail = "forgetPeer(${redactedSuffix(peerId)}) -> Forgotten",
                            family = TimelineFamily.PEER,
                            severity = TimelineSeverity.SUCCESS,
                            peerSuffix = redactedSuffix(peerId),
                        )
                    )
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
            automationTestSnapshot(
                timeline =
                    listOf(
                        automationTestEntry(
                            title = "ROUTE_RETRACTED",
                            detail = "ROUTE_RETRACTED @ trust.forgetPeer.routeRetracted",
                            family = TimelineFamily.DIAGNOSTIC,
                            peerSuffix = redactedSuffix(peerId),
                        )
                    )
            )

        // Act
        val recoveryReady =
            hasTrustResetRecoveryReady(snapshot, peerSuffix = redactedSuffix(peerId))

        // Assert
        assertTrue(recoveryReady)
    }
}
