package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.PeerConnectionSnapshotState
import ch.trancee.meshlink.reference.model.PeerSnapshot
import ch.trancee.meshlink.reference.model.PeerTrustState
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceSession
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReferenceLiveProofAutomationTest {
    @Test
    fun autoSendStartsOnceAnyPeerIsDiscoveredEvenBeforeTrustIsPinned() {
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
        val actual = shouldAutoSendGuidedHello(snapshot)

        // Assert
        assertTrue(actual)
    }

    @Test
    fun autoSendWaitsWhenNoPeerHasBeenDiscovered() {
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
                timeline = emptyList(),
                activePowerModeLabel = "Automatic",
            )

        // Act
        val actual = shouldAutoSendGuidedHello(snapshot)

        // Assert
        assertFalse(actual)
    }
}
