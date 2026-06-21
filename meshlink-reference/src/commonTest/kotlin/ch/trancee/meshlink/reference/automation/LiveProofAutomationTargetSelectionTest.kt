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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveProofAutomationTargetSelectionTest {
    @Test
    fun a065_nam_lx9_discovery_marker_does_not_count_as_a_route_on_its_own() {
        val targetPeerId = "Iz4CzZ99uLSYYXolDgb0WTI+fgHc4ri9Rd9GIAQDVjk="
        val targetPeerSuffix = targetPeerId.takeLast(6)
        val snapshot =
            ReferenceControllerSnapshot(
                session =
                    ReferenceSession(
                        sessionId = "session-1",
                        scenarioId = "direct-guided",
                        authorityMode = "LIVE",
                        startedAtEpochMillis = 1L,
                        meshStateLabel = MeshLinkState.Running.toString(),
                        selectedPeerId = targetPeerId,
                        historyStatus = ReferenceHistoryStatus.LIVE,
                    ),
                peers =
                    listOf(
                        PeerSnapshot(
                            peerId = "bootstrap-peer",
                            peerSuffix = "ootstrap",
                            trustState = PeerTrustState.TRUSTED,
                            connectionState = PeerConnectionSnapshotState.CONNECTED,
                        ),
                        PeerSnapshot(
                            peerId = targetPeerId,
                            peerSuffix = targetPeerSuffix,
                            trustState = PeerTrustState.TRUSTED,
                            connectionState = PeerConnectionSnapshotState.CONNECTED,
                        ),
                    ),
                timeline =
                    listOf(
                        TimelineEntry(
                            entryId = "entry-1",
                            sessionId = "session-1",
                            occurredAtEpochMillis = 2L,
                            family = TimelineFamily.DIAGNOSTIC,
                            severity = TimelineSeverity.INFO,
                            title = "peer.discovered",
                            detail = "peerId=$targetPeerId peer.discovered routeAvailable=false",
                            peerSuffix = targetPeerSuffix,
                        )
                    ),
                activePowerModeLabel = "automatic",
            )

        assertFalse(hasAvailableRouteForPeer(snapshot, targetPeerId))
        assertEquals("bootstrap-peer", bootstrapTargetPeer(snapshot, targetPeerId)?.peerId)
        assertTrue(shouldAutoSendGuidedHello(snapshot))
    }

    @Test
    fun explicit_route_available_marker_stays_available() {
        val targetPeerId = "Iz4CzZ99uLSYYXolDgb0WTI+fgHc4ri9Rd9GIAQDVjk="
        val targetPeerSuffix = targetPeerId.takeLast(6)
        val snapshot =
            ReferenceControllerSnapshot(
                session =
                    ReferenceSession(
                        sessionId = "session-2",
                        scenarioId = "direct-guided",
                        authorityMode = "LIVE",
                        startedAtEpochMillis = 1L,
                        meshStateLabel = MeshLinkState.Running.toString(),
                        selectedPeerId = targetPeerId,
                        historyStatus = ReferenceHistoryStatus.LIVE,
                    ),
                peers =
                    listOf(
                        PeerSnapshot(
                            peerId = targetPeerId,
                            peerSuffix = targetPeerSuffix,
                            trustState = PeerTrustState.TRUSTED,
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
                            title = "route.available",
                            detail = "peerId=$targetPeerId routeAvailable=true",
                            peerSuffix = targetPeerSuffix,
                        )
                    ),
                activePowerModeLabel = "automatic",
            )

        assertTrue(hasAvailableRouteForPeer(snapshot, targetPeerId))
        assertEquals(null, bootstrapTargetPeer(snapshot, targetPeerId))
    }
}
