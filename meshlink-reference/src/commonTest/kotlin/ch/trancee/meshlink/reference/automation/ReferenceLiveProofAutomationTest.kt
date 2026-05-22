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
    fun autoSendStartsOnceTargetPeerIndexExists() {
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
                        ),
                        PeerSnapshot(
                            peerId = "peer-2",
                            peerSuffix = "def456",
                            trustState = PeerTrustState.UNKNOWN,
                            connectionState = PeerConnectionSnapshotState.CONNECTED,
                        ),
                    ),
                timeline = emptyList(),
                activePowerModeLabel = "Automatic",
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
    fun meshStartWaitsUntilNoLifecycleStateOrStartupBlockerIsPresent() {
        // Arrange
        val snapshot =
            ReferenceControllerSnapshot(
                session =
                    ReferenceSession(
                        sessionId = "session-1",
                        scenarioId = "guided-first-exchange",
                        authorityMode = ReferenceAuthorityMode.LIVE,
                        startedAtEpochMillis = 1L,
                        meshStateLabel = "Uninitialized",
                    ),
                peers = emptyList(),
                timeline = emptyList(),
                activePowerModeLabel = "Automatic",
            )

        // Act
        val allowed =
            shouldRequestLiveProofMeshStart(
                meshStartRequested = false,
                snapshot = snapshot,
                readinessBlockers = emptyList(),
            )
        val blockedByState =
            shouldRequestLiveProofMeshStart(
                meshStartRequested = false,
                snapshot =
                    snapshot.copy(session = snapshot.session.copy(meshStateLabel = "Running")),
                readinessBlockers = emptyList(),
            )
        val blockedByReadiness =
            shouldRequestLiveProofMeshStart(
                meshStartRequested = false,
                snapshot = snapshot,
                readinessBlockers = listOf("Enable Bluetooth"),
            )

        // Assert
        assertTrue(allowed)
        assertFalse(blockedByState)
        assertFalse(blockedByReadiness)
    }

    @Test
    fun passiveProofRetainAndExportOnlyAdvanceWhenPrerequisitesExist() {
        // Arrange
        val retainRequested = false
        val exportRequested = false

        // Act
        val retainReady =
            shouldRetainPassiveLiveProof(
                retainRequested = retainRequested,
                hasTrustEstablished = true,
                hasInboundMessage = true,
            )
        val retainBlocked =
            shouldRetainPassiveLiveProof(
                retainRequested = retainRequested,
                hasTrustEstablished = true,
                hasInboundMessage = false,
            )
        val exportReady =
            shouldExportPassiveLiveProof(
                retainRequested = true,
                exportRequested = exportRequested,
                retainedSessionCount = 1,
            )
        val exportBlocked =
            shouldExportPassiveLiveProof(
                retainRequested = false,
                exportRequested = exportRequested,
                retainedSessionCount = 1,
            )

        // Assert
        assertTrue(retainReady)
        assertFalse(retainBlocked)
        assertTrue(exportReady)
        assertFalse(exportBlocked)
    }
}
