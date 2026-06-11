package ch.trancee.meshlink.reference.guided

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.PeerConnectionSnapshotState
import ch.trancee.meshlink.reference.model.PeerSnapshot
import ch.trancee.meshlink.reference.model.PeerTrustState
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuidedFirstExchangeViewModelJvmTest {
    @Test
    fun guidedFlowMovesFromUninitializedToPeerReadyAndRetainsFailureReasonInReadinessState() {
        // Arrange
        val store =
            GuidedFirstExchangeStateStore(
                platformName = "JVM",
                readinessGuidance =
                    listOf("Connect both devices", "Confirm the first peer is visible"),
                readinessBlockers = emptyList(),
                initialSnapshot =
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
                        activePowerModeLabel = "balanced",
                    ),
            )

        // Act + Assert: initial state reflects the uninitialized guided surface.
        assertFalse(store.uiState.value.readiness.isBlocked)
        assertEquals("Start MeshLink", store.uiState.value.nextActionLabel)
        assertFalse(store.uiState.value.canSendHello)
        assertEquals("Connect both devices", store.uiState.value.readiness.items.first().detail)

        val readySnapshot =
            ReferenceControllerSnapshot(
                session =
                    ReferenceSession(
                        sessionId = "session-1",
                        scenarioId = "guided-first-exchange",
                        authorityMode = ReferenceAuthorityMode.LIVE,
                        startedAtEpochMillis = 1L,
                        meshStateLabel = "Running",
                        selectedPeerId = "peer-1234",
                        historyStatus = ReferenceHistoryStatus.LIVE,
                    ),
                peers =
                    listOf(
                        PeerSnapshot(
                            peerId = "peer-1234",
                            peerSuffix = "1234",
                            trustState = PeerTrustState.TRUSTED,
                            connectionState = PeerConnectionSnapshotState.CONNECTED,
                            capabilityNotes = listOf("Retained guided proof"),
                        )
                    ),
                timeline =
                    listOf(
                        TimelineEntry(
                            entryId = "entry-1",
                            sessionId = "session-1",
                            occurredAtEpochMillis = 1L,
                            family = ch.trancee.meshlink.reference.model.TimelineFamily.EXPORT,
                            severity = ch.trancee.meshlink.reference.model.TimelineSeverity.INFO,
                            title = "session-exported",
                            detail = "retained",
                        )
                    ),
                activePowerModeLabel = "balanced",
            )

        // Act
        store.applySnapshot(readySnapshot)

        // Assert: the UI transitions into the ready-to-guide branch with retained provenance
        // intact.
        assertFalse(store.uiState.value.readiness.isBlocked)
        assertEquals("Send the first guided message", store.uiState.value.nextActionLabel)
        assertTrue(store.uiState.value.canSendHello)
        assertEquals("1234", store.uiState.value.selectedPeerSuffix)
        assertEquals("peer-1234", store.uiState.value.snapshot.session.selectedPeerId)
        assertEquals(
            "Retained guided proof",
            store.uiState.value.snapshot.peers.single().capabilityNotes.single(),
        )
    }

    @Test
    fun guidedFlowRetainsFailureReasonAfterPeerReadyTransition() {
        // Arrange
        val store =
            GuidedFirstExchangeStateStore(
                platformName = "JVM",
                readinessGuidance =
                    listOf("Connect both devices", "Confirm the first peer is visible"),
                readinessBlockers = emptyList(),
                initialSnapshot =
                    ReferenceControllerSnapshot(
                        session =
                            ReferenceSession(
                                sessionId = "session-2",
                                scenarioId = "guided-first-exchange",
                                authorityMode = ReferenceAuthorityMode.LIVE,
                                startedAtEpochMillis = 1L,
                                meshStateLabel = "Uninitialized",
                                historyStatus = ReferenceHistoryStatus.LIVE,
                            ),
                        peers = emptyList(),
                        timeline = emptyList(),
                        activePowerModeLabel = "balanced",
                    ),
            )

        val readySnapshot =
            ReferenceControllerSnapshot(
                session =
                    ReferenceSession(
                        sessionId = "session-2",
                        scenarioId = "guided-first-exchange",
                        authorityMode = ReferenceAuthorityMode.LIVE,
                        startedAtEpochMillis = 1L,
                        meshStateLabel = "Running",
                        selectedPeerId = "peer-1234",
                        historyStatus = ReferenceHistoryStatus.LIVE,
                    ),
                peers =
                    listOf(
                        PeerSnapshot(
                            peerId = "peer-1234",
                            peerSuffix = "1234",
                            trustState = PeerTrustState.TRUSTED,
                            connectionState = PeerConnectionSnapshotState.CONNECTED,
                            capabilityNotes = listOf("Retained guided proof"),
                        )
                    ),
                timeline =
                    listOf(
                        TimelineEntry(
                            entryId = "entry-1",
                            sessionId = "session-1",
                            occurredAtEpochMillis = 1L,
                            family = ch.trancee.meshlink.reference.model.TimelineFamily.PEER,
                            severity = ch.trancee.meshlink.reference.model.TimelineSeverity.INFO,
                            title = "Peer connected",
                            detail = "ready",
                        )
                    ),
                activePowerModeLabel = "balanced",
            )

        val failedSnapshot =
            ReferenceControllerSnapshot(
                session =
                    ReferenceSession(
                        sessionId = "session-2",
                        scenarioId = "guided-first-exchange",
                        authorityMode = ReferenceAuthorityMode.LIVE,
                        startedAtEpochMillis = 1L,
                        endedAtEpochMillis = 2L,
                        meshStateLabel = "Failed",
                        selectedPeerId = "peer-1234",
                        lastOutcomeSummary = "Link negotiation failed after peer readiness",
                        historyStatus = ReferenceHistoryStatus.RETAINED,
                    ),
                peers =
                    listOf(
                        PeerSnapshot(
                            peerId = "peer-1234",
                            peerSuffix = "1234",
                            trustState = PeerTrustState.TRUSTED,
                            connectionState = PeerConnectionSnapshotState.DISCONNECTED,
                            capabilityNotes = listOf("Retained guided proof"),
                        )
                    ),
                timeline =
                    listOf(
                        TimelineEntry(
                            entryId = "entry-1",
                            sessionId = "session-2",
                            occurredAtEpochMillis = 1L,
                            family = ch.trancee.meshlink.reference.model.TimelineFamily.PEER,
                            severity = ch.trancee.meshlink.reference.model.TimelineSeverity.INFO,
                            title = "Peer connected",
                            detail = "ready",
                        ),
                        TimelineEntry(
                            entryId = "entry-2",
                            sessionId = "session-2",
                            occurredAtEpochMillis = 2L,
                            family = ch.trancee.meshlink.reference.model.TimelineFamily.DIAGNOSTIC,
                            severity = ch.trancee.meshlink.reference.model.TimelineSeverity.ERROR,
                            title = "Negotiation failed",
                            detail = "Link negotiation failed after peer readiness",
                        ),
                    ),
                activePowerModeLabel = "balanced",
            )

        // Act
        store.applySnapshot(readySnapshot)
        store.applySnapshot(failedSnapshot)

        // Assert
        assertTrue(store.uiState.value.isSessionEnded)
        assertEquals(
            "Open the technical timeline to review or start the next supported session",
            store.uiState.value.nextActionLabel,
        )
        assertFalse(store.uiState.value.canSendHello)
        assertEquals("1234", store.uiState.value.selectedPeerSuffix)
        assertEquals("peer-1234", store.uiState.value.snapshot.session.selectedPeerId)
        assertEquals(
            "Link negotiation failed after peer readiness",
            store.uiState.value.snapshot.session.lastOutcomeSummary,
        )
        assertEquals(
            "Retained guided proof",
            store.uiState.value.snapshot.peers.single().capabilityNotes.single(),
        )
        assertEquals(
            "Link negotiation failed after peer readiness",
            store.uiState.value.snapshot.timeline.last().detail,
        )
    }
}
