package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.PeerConnectionSnapshotState
import ch.trancee.meshlink.reference.model.PeerSnapshot
import ch.trancee.meshlink.reference.model.PeerTrustState
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PassiveTrustPredicateTest {
    @Test
    fun trustedSelectedPeerCountsAsTrustedEvenWithoutDiagnosticEvent() {
        val snapshot =
            ReferenceControllerSnapshot(
                session =
                    ReferenceSession(
                        sessionId = "session-1",
                        scenarioId = "direct-guided",
                        authorityMode = "LIVE",
                        startedAtEpochMillis = 1L,
                        meshStateLabel = MeshLinkState.Running.toString(),
                        selectedPeerId = "peer-123",
                        historyStatus = ReferenceHistoryStatus.LIVE,
                    ),
                peers =
                    listOf(
                        PeerSnapshot(
                            peerId = "peer-123",
                            peerSuffix = "peer-123",
                            trustState = PeerTrustState.TRUSTED,
                            connectionState = PeerConnectionSnapshotState.CONNECTED,
                        )
                    ),
                timeline = emptyList(),
                activePowerModeLabel = "automatic",
            )

        assertTrue(hasTrustedSelectedPeer(snapshot))
        assertFalse(hasTimelineEntry(snapshot, title = "TRUST_ESTABLISHED"))
    }

    @Test
    fun untrustedSelectedPeerDoesNotCountAsTrusted() {
        val snapshot =
            ReferenceControllerSnapshot(
                session =
                    ReferenceSession(
                        sessionId = "session-2",
                        scenarioId = "direct-guided",
                        authorityMode = "LIVE",
                        startedAtEpochMillis = 1L,
                        meshStateLabel = MeshLinkState.Running.toString(),
                        selectedPeerId = "peer-456",
                        historyStatus = ReferenceHistoryStatus.LIVE,
                    ),
                peers =
                    listOf(
                        PeerSnapshot(
                            peerId = "peer-456",
                            peerSuffix = "peer-456",
                            trustState = PeerTrustState.UNKNOWN,
                            connectionState = PeerConnectionSnapshotState.CONNECTED,
                        )
                    ),
                timeline =
                    listOf(
                        TimelineEntry(
                            entryId = "entry-1",
                            sessionId = "session-2",
                            occurredAtEpochMillis = 2L,
                            family = TimelineFamily.DIAGNOSTIC,
                            severity = TimelineSeverity.INFO,
                            title = "NEUTRAL_EVENT",
                            detail = "Selected peer is not trusted yet",
                            peerSuffix = "peer-456",
                        )
                    ),
                activePowerModeLabel = "automatic",
            )

        assertFalse(hasTrustedSelectedPeer(snapshot))
        assertFalse(hasTimelineEntry(snapshot, title = "TRUST_ESTABLISHED"))
    }
}
