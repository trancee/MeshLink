package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.PeerConnectionState
import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.reference.model.PeerConnectionSnapshotState
import ch.trancee.meshlink.reference.model.PeerSnapshot
import ch.trancee.meshlink.reference.model.PeerTrustState
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceSession
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveReferencePeerEventsTest {
    @Test
    fun foundEventAddsAndSelectsTheDiscoveredPeer() {
        // Arrange
        val store = stateStore()
        val event = PeerEvent.Found(PeerId("peer-123456"), PeerConnectionState.CONNECTED)

        // Act
        applyPeerEvent(stateStore = store, nowProvider = { NOW }, event = event)

        // Assert
        val snapshot = store.currentSnapshot
        assertEquals(1, snapshot.peers.size)
        assertEquals("peer-123456", snapshot.peers.single().peerId)
        assertEquals("123456", snapshot.peers.single().peerSuffix)
        assertEquals(PeerConnectionSnapshotState.CONNECTED, snapshot.peers.single().connectionState)
        assertEquals(NOW, snapshot.peers.single().lastSeenAtEpochMillis)
        assertEquals("Peer found", snapshot.session.lastOutcomeSummary)
        assertEquals("peer-123456", snapshot.session.selectedPeerId)
        assertEquals("Peer found", snapshot.timeline.single().title)
    }

    @Test
    fun stateChangedEventRefreshesConnectionStateAndTimestamp() {
        // Arrange
        val store =
            stateStore(
                peers =
                    listOf(
                        peerSnapshot(
                            peerId = "peer-123456",
                            connectionState = PeerConnectionSnapshotState.DISCONNECTED,
                            lastSeenAtEpochMillis = 1L,
                        )
                    )
            )
        val event = PeerEvent.StateChanged(PeerId("peer-123456"), PeerConnectionState.CONNECTED)

        // Act
        applyPeerEvent(stateStore = store, nowProvider = { NOW }, event = event)

        // Assert
        val peer = store.currentSnapshot.peers.single()
        assertEquals(PeerConnectionSnapshotState.CONNECTED, peer.connectionState)
        assertEquals(NOW, peer.lastSeenAtEpochMillis)
        assertEquals("Peer state changed", store.currentSnapshot.timeline.single().title)
    }

    @Test
    fun lostEventMarksThePeerAsLost() {
        // Arrange
        val store =
            stateStore(
                peers =
                    listOf(
                        peerSnapshot(
                            peerId = "peer-123456",
                            connectionState = PeerConnectionSnapshotState.CONNECTED,
                        )
                    )
            )
        val event = PeerEvent.Lost(PeerId("peer-123456"))

        // Act
        applyPeerEvent(stateStore = store, nowProvider = { NOW }, event = event)

        // Assert
        val peer = store.currentSnapshot.peers.single()
        assertEquals(PeerConnectionSnapshotState.LOST, peer.connectionState)
        assertEquals(NOW, peer.lastSeenAtEpochMillis)
        assertEquals("Peer lost", store.currentSnapshot.timeline.single().title)
    }

    @Test
    fun trustUpdatesApplyOnlyToTheMatchingPeerSuffix() {
        // Arrange
        val store =
            stateStore(
                peers =
                    listOf(
                        peerSnapshot(peerId = "peer-123456", trustState = PeerTrustState.UNKNOWN),
                        peerSnapshot(peerId = "peer-abcdef", trustState = PeerTrustState.UNKNOWN),
                    )
            )

        // Act
        updatePeerTrustState(
            stateStore = store,
            peerSuffix = "123456",
            trustState = PeerTrustState.TRUSTED,
        )

        // Assert
        val peersById = store.currentSnapshot.peers.associateBy { peer -> peer.peerId }
        assertEquals(PeerTrustState.TRUSTED, peersById.getValue("peer-123456").trustState)
        assertEquals(PeerTrustState.UNKNOWN, peersById.getValue("peer-abcdef").trustState)
    }
}

private fun stateStore(peers: List<PeerSnapshot> = emptyList()): ReferenceControllerStateStore {
    return ReferenceControllerStateStore(
        initialSnapshot =
            ReferenceControllerSnapshot(
                session =
                    ReferenceSession(
                        sessionId = "session-1",
                        scenarioId = "guided-first-exchange",
                        authorityMode = ReferenceAuthorityMode.LIVE,
                        startedAtEpochMillis = NOW,
                    ),
                peers = peers,
                timeline = emptyList(),
                activePowerModeLabel = "Automatic",
            ),
        sessionId = "session-1",
        nowProvider = { NOW },
    )
}

private fun peerSnapshot(
    peerId: String,
    connectionState: PeerConnectionSnapshotState = PeerConnectionSnapshotState.CONNECTED,
    trustState: PeerTrustState = PeerTrustState.UNKNOWN,
    lastSeenAtEpochMillis: Long? = null,
): PeerSnapshot {
    return PeerSnapshot(
        peerId = peerId,
        peerSuffix = peerId.takeLast(6),
        trustState = trustState,
        connectionState = connectionState,
        lastSeenAtEpochMillis = lastSeenAtEpochMillis,
    )
}

private const val NOW: Long = 1234L
