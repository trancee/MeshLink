package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.reference.model.PeerConnectionSnapshotState
import ch.trancee.meshlink.reference.model.PeerSnapshot
import ch.trancee.meshlink.reference.model.PeerTrustState
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import kotlin.test.Test
import kotlin.test.assertEquals

class ReferenceControllerStateStoreTest {
    @Test
    fun appendEventAddsSequentialTimelineEntries() {
        // Arrange
        val nowProvider = { 2_000L }
        val store =
            ReferenceControllerStateStore(
                initialSnapshot = referenceSnapshot(),
                sessionId = "reference-session",
                nowProvider = nowProvider,
            )

        // Act
        store.appendEvent(
            ReferenceTimelineEvent(
                family = TimelineFamily.MESSAGE,
                severity = TimelineSeverity.SUCCESS,
                title = "Payload sent",
                detail = "Sent 12 bytes",
                peerSuffix = "abc123",
            )
        )

        // Assert
        val timeline = store.currentSnapshot.timeline
        assertEquals(2, timeline.size)
        assertEquals("reference-session-2", timeline.last().entryId)
        assertEquals(2_000L, timeline.last().occurredAtEpochMillis)
        assertEquals("Payload sent Sent 12 bytes abc123", timeline.last().searchText)
    }

    @Test
    fun updateSessionAndPeersPreserveOtherSnapshotState() {
        // Arrange
        val store =
            ReferenceControllerStateStore(
                initialSnapshot = referenceSnapshot(),
                sessionId = "reference-session",
                nowProvider = { 2_000L },
            )

        // Act
        store.updateSession(meshStateLabel = "Paused", lastOutcomeSummary = "Paused")
        store.updatePeers { peers ->
            peers.map { peer ->
                peer.copy(
                    trustState = PeerTrustState.TRUSTED,
                    connectionState = PeerConnectionSnapshotState.CONNECTED,
                )
            }
        }
        store.updateActivePowerModeLabel("Performance")

        // Assert
        val snapshot = store.currentSnapshot
        assertEquals("Paused", snapshot.session.meshStateLabel)
        assertEquals("Paused", snapshot.session.lastOutcomeSummary)
        assertEquals("Performance", snapshot.activePowerModeLabel)
        assertEquals(PeerTrustState.TRUSTED, snapshot.peers.single().trustState)
        assertEquals(PeerConnectionSnapshotState.CONNECTED, snapshot.peers.single().connectionState)
        assertEquals("Initial entry", snapshot.timeline.single().title)
    }
}

private fun referenceSnapshot(): ReferenceControllerSnapshot {
    return ReferenceControllerSnapshot(
        session =
            ReferenceSession(
                sessionId = "reference-session",
                scenarioId = "guided-first-exchange",
                authorityMode = ReferenceAuthorityMode.LIVE,
                startedAtEpochMillis = 1_000L,
                meshStateLabel = "Running",
                historyStatus = ReferenceHistoryStatus.LIVE,
            ),
        peers =
            listOf(
                PeerSnapshot(
                    peerId = "peer-abc123",
                    peerSuffix = "abc123",
                    trustState = PeerTrustState.UNKNOWN,
                    connectionState = PeerConnectionSnapshotState.DISCONNECTED,
                )
            ),
        timeline =
            listOf(
                TimelineEntry(
                    entryId = "reference-session-1",
                    sessionId = "reference-session",
                    occurredAtEpochMillis = 1_000L,
                    family = TimelineFamily.USER,
                    severity = TimelineSeverity.INFO,
                    title = "Initial entry",
                    detail = "Reference state initialized",
                )
            ),
        activePowerModeLabel = "Automatic",
    )
}
