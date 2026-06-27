package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.PeerConnectionState
import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.reference.model.PeerConnectionSnapshotState
import ch.trancee.meshlink.reference.model.ReferenceSession
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveReferencePeerEventsTest {
    @Test
    fun handlePeerFoundAutoSelectsFirstAcceptedPeerWhenTargetDoesNotMatch(): Unit {
        // Arrange
        val stateStore =
            ReferenceControllerStateStore(
                initialSnapshot = initialSnapshot(),
                sessionId = "session-1",
                nowProvider = { 1234L },
                automationTargetPeerId = "target-peer",
            )

        // Act
        applyPeerEvent(
            stateStore = stateStore,
            nowProvider = { 1234L },
            event =
                PeerEvent.Found(peerId = PeerId("peer-1"), state = PeerConnectionState.CONNECTED),
        )

        // Assert
        val snapshot = stateStore.currentSnapshot
        assertEquals("peer-1", snapshot.session.selectedPeerId)
        assertEquals(1, snapshot.peers.size)
        assertEquals("peer-1", snapshot.peers.first().peerId)
        assertEquals(PeerConnectionSnapshotState.CONNECTED, snapshot.peers.first().connectionState)
    }

    @Test
    fun handlePeerFoundKeepsFirstSelectedPeerWhenAnotherPeerArrivesLater(): Unit {
        // Arrange
        val stateStore =
            ReferenceControllerStateStore(
                initialSnapshot = initialSnapshot(),
                sessionId = "session-1",
                nowProvider = { 1234L },
                automationTargetPeerId = "target-peer",
            )
        applyPeerEvent(
            stateStore = stateStore,
            nowProvider = { 1234L },
            event =
                PeerEvent.Found(peerId = PeerId("peer-1"), state = PeerConnectionState.CONNECTED),
        )

        // Act
        applyPeerEvent(
            stateStore = stateStore,
            nowProvider = { 1235L },
            event =
                PeerEvent.Found(peerId = PeerId("peer-2"), state = PeerConnectionState.CONNECTED),
        )

        // Assert
        val snapshot = stateStore.currentSnapshot
        assertEquals("peer-1", snapshot.session.selectedPeerId)
        assertEquals(listOf("peer-1"), snapshot.peers.map { it.peerId })
    }

    private fun initialSnapshot(): ReferenceControllerSnapshot {
        return ReferenceControllerSnapshot(
            session =
                ReferenceSession(
                    sessionId = "session-1",
                    scenarioId = "direct-guided",
                    authorityMode = "LIVE",
                    startedAtEpochMillis = 0L,
                    endedAtEpochMillis = null,
                    meshStateLabel = "Uninitialized",
                    selectedPeerId = null,
                    configurationSnapshot = emptyMap(),
                    lastOutcomeSummary = null,
                ),
            peers = emptyList(),
            timeline = emptyList(),
            activePowerModeLabel = "BALANCED",
        )
    }
}
