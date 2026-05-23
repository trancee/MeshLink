package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.ForgetPeerResult
import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.StartResult
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticEvent
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
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
import kotlin.test.assertTrue

class ReferenceSessionProjectorTest {
    @Test
    fun `recordMeshCall appends a lifecycle event and tracks lifecycle outcomes`() {
        // Arrange
        val stateStore = referenceStateStore()
        val runtimeLogs = mutableListOf<String>()
        val projector =
            LiveReferenceSessionProjector(stateStore = stateStore, runtimeLogger = runtimeLogs::add)

        // Act
        projector.recordMeshCall(
            result = Result.success(StartResult.Started),
            successTitle = "Mesh started",
            successDetail = { result -> "mesh.start() -> $result" },
            errorTitle = "Mesh start failed",
        )

        // Assert
        val snapshot = stateStore.currentSnapshot
        val timelineEntry = snapshot.timeline.last()
        assertEquals("Mesh started", timelineEntry.title)
        assertEquals("mesh.start() -> Started", timelineEntry.detail)
        assertEquals("Started", snapshot.session.lastOutcomeSummary)
        assertTrue(runtimeLogs.isEmpty(), "Lifecycle projection should not emit runtime logs")
    }

    @Test
    fun `recordMeshCall leaves the last outcome unchanged for non lifecycle results`() {
        // Arrange
        val stateStore = referenceStateStore(lastOutcomeSummary = "Existing outcome")
        val projector = LiveReferenceSessionProjector(stateStore = stateStore)

        // Act
        projector.recordMeshCall(
            result = Result.success(ForgetPeerResult.Forgotten),
            successTitle = "Trust reset",
            successDetail = { result -> "forgetPeer() -> $result" },
            errorTitle = "Trust reset failed",
        )

        // Assert
        val snapshot = stateStore.currentSnapshot
        assertEquals("Existing outcome", snapshot.session.lastOutcomeSummary)
        assertEquals("Trust reset", snapshot.timeline.last().title)
    }

    @Test
    fun `recordMeshCall appends an error lifecycle event when the mesh call fails`() {
        // Arrange
        val stateStore = referenceStateStore()
        val projector = LiveReferenceSessionProjector(stateStore = stateStore)

        // Act
        projector.recordMeshCall(
            result = Result.failure(IllegalStateException("boom")),
            successTitle = "Mesh started",
            successDetail = { result: StartResult -> "mesh.start() -> $result" },
            errorTitle = "Mesh start failed",
        )

        // Assert
        val snapshot = stateStore.currentSnapshot
        val timelineEntry = snapshot.timeline.last()
        assertEquals("Mesh start failed", timelineEntry.title)
        assertEquals("boom", timelineEntry.detail)
        assertEquals("Mesh start failed", snapshot.session.lastOutcomeSummary)
    }

    @Test
    fun `recordPeerTrustReset marks the peer forgotten when trust is cleared`() {
        // Arrange
        val stateStore = referenceStateStore()
        val projector = LiveReferenceSessionProjector(stateStore = stateStore)

        // Act
        projector.recordPeerTrustReset(peerId = TEST_PEER_ID, result = ForgetPeerResult.Forgotten)

        // Assert
        val snapshot = stateStore.currentSnapshot
        assertEquals(PeerTrustState.FORGOTTEN, snapshot.peers.single().trustState)
        assertEquals("Peer trust reset", snapshot.timeline.last().title)
        assertEquals(
            "forgetPeer(${TEST_PEER_SUFFIX}) -> Forgotten",
            snapshot.timeline.last().detail,
        )
    }

    @Test
    fun `recordPeerTrustReset keeps the peer unknown when no trust state exists`() {
        // Arrange
        val stateStore = referenceStateStore(trustState = PeerTrustState.TRUSTED)
        val projector = LiveReferenceSessionProjector(stateStore = stateStore)

        // Act
        projector.recordPeerTrustReset(peerId = TEST_PEER_ID, result = ForgetPeerResult.NotFound)

        // Assert
        val snapshot = stateStore.currentSnapshot
        assertEquals(PeerTrustState.UNKNOWN, snapshot.peers.single().trustState)
        assertEquals("forgetPeer(${TEST_PEER_SUFFIX}) -> NotFound", snapshot.timeline.last().detail)
    }

    @Test
    fun `recordDiagnostic marks trusted peers as trusted and emits a runtime log`() {
        // Arrange
        val stateStore = referenceStateStore()
        val runtimeLogs = mutableListOf<String>()
        val projector =
            LiveReferenceSessionProjector(stateStore = stateStore, runtimeLogger = runtimeLogs::add)
        val event =
            DiagnosticEvent(
                code = DiagnosticCode.TRUST_ESTABLISHED,
                severity = DiagnosticSeverity.INFO,
                stage = "handshake.complete",
                peerSuffix = TEST_PEER_SUFFIX,
                metadata = mapOf("cipher" to "Noise_XX_25519_ChaChaPoly_SHA256"),
            )

        // Act
        projector.recordDiagnostic(event)

        // Assert
        val snapshot = stateStore.currentSnapshot
        assertEquals(PeerTrustState.TRUSTED, snapshot.peers.single().trustState)
        assertEquals(DiagnosticCode.TRUST_ESTABLISHED.name, snapshot.timeline.last().title)
        assertEquals(1, runtimeLogs.size)
        assertTrue(runtimeLogs.single().contains("code=TRUST_ESTABLISHED"))
    }

    @Test
    fun `recordDiagnostic marks trust failures as changed`() {
        // Arrange
        val stateStore = referenceStateStore()
        val projector = LiveReferenceSessionProjector(stateStore = stateStore)
        val event =
            DiagnosticEvent(
                code = DiagnosticCode.TRUST_FAILURE,
                severity = DiagnosticSeverity.ERROR,
                stage = "handshake.failed",
                peerSuffix = TEST_PEER_SUFFIX,
            )

        // Act
        projector.recordDiagnostic(event)

        // Assert
        assertEquals(PeerTrustState.CHANGED, stateStore.currentSnapshot.peers.single().trustState)
        assertEquals(
            DiagnosticCode.TRUST_FAILURE.name,
            stateStore.currentSnapshot.timeline.last().title,
        )
    }

    @Test
    fun `recordDiagnostic updates the active power mode label when the tier is present`() {
        // Arrange
        val stateStore = referenceStateStore()
        val projector = LiveReferenceSessionProjector(stateStore = stateStore)
        val event =
            DiagnosticEvent(
                code = DiagnosticCode.POWER_MODE_CHANGED,
                severity = DiagnosticSeverity.INFO,
                stage = "power.mode",
                metadata = mapOf("tier" to "Performance"),
            )

        // Act
        projector.recordDiagnostic(event)

        // Assert
        assertEquals("Performance", stateStore.currentSnapshot.activePowerModeLabel)
        assertEquals(
            DiagnosticCode.POWER_MODE_CHANGED.name,
            stateStore.currentSnapshot.timeline.last().title,
        )
    }

    @Test
    fun `recordDiagnostic leaves peer and power state unchanged for unrelated codes`() {
        // Arrange
        val stateStore = referenceStateStore(trustState = PeerTrustState.UNKNOWN)
        val projector = LiveReferenceSessionProjector(stateStore = stateStore)
        val event =
            DiagnosticEvent(
                code = DiagnosticCode.DELIVERY_QUEUED,
                severity = DiagnosticSeverity.INFO,
                stage = "delivery.queue",
                peerSuffix = TEST_PEER_SUFFIX,
            )

        // Act
        projector.recordDiagnostic(event)

        // Assert
        val snapshot = stateStore.currentSnapshot
        assertEquals(PeerTrustState.UNKNOWN, snapshot.peers.single().trustState)
        assertEquals("Automatic", snapshot.activePowerModeLabel)
        assertEquals(DiagnosticCode.DELIVERY_QUEUED.name, snapshot.timeline.last().title)
    }

    @Test
    fun `recordInboundMessage appends payload evidence and updates the peer outcome`() {
        // Arrange
        val stateStore = referenceStateStore()
        val runtimeLogs = mutableListOf<String>()
        val projector =
            LiveReferenceSessionProjector(stateStore = stateStore, runtimeLogger = runtimeLogs::add)
        val message =
            InboundMessage(
                originPeerId = PeerId(TEST_PEER_ID),
                payload = "hello from relay".encodeToByteArray(),
                receivedAtEpochMillis = 2_000L,
                priority = ch.trancee.meshlink.api.DeliveryPriority.NORMAL,
            )

        // Act
        projector.recordInboundMessage(message)

        // Assert
        val snapshot = stateStore.currentSnapshot
        val timelineEntry = snapshot.timeline.last()
        assertEquals("Inbound message", timelineEntry.title)
        assertEquals(TEST_PEER_SUFFIX, timelineEntry.peerSuffix)
        assertEquals("hello from relay", timelineEntry.fullPayload)
        assertEquals("Inbound message received", snapshot.session.lastOutcomeSummary)
        assertEquals(TEST_PEER_ID, snapshot.session.selectedPeerId)
        assertEquals("Inbound 16 bytes", snapshot.peers.single().lastDeliveryOutcome)
        assertEquals(1, runtimeLogs.size)
        assertTrue(runtimeLogs.single().contains("origin=$TEST_PEER_ID"))
    }
}

private fun referenceStateStore(
    lastOutcomeSummary: String? = null,
    trustState: PeerTrustState = PeerTrustState.UNKNOWN,
): ReferenceControllerStateStore {
    return ReferenceControllerStateStore(
        initialSnapshot =
            referenceSnapshot(lastOutcomeSummary = lastOutcomeSummary, trustState = trustState),
        sessionId = "reference-session",
        nowProvider = { 2_000L },
    )
}

private fun referenceSnapshot(
    lastOutcomeSummary: String? = null,
    trustState: PeerTrustState,
): ReferenceControllerSnapshot {
    return ReferenceControllerSnapshot(
        session =
            ReferenceSession(
                sessionId = "reference-session",
                scenarioId = "guided-first-exchange",
                authorityMode = ReferenceAuthorityMode.LIVE,
                startedAtEpochMillis = 1_000L,
                meshStateLabel = "Running",
                lastOutcomeSummary = lastOutcomeSummary,
                historyStatus = ReferenceHistoryStatus.LIVE,
            ),
        peers =
            listOf(
                PeerSnapshot(
                    peerId = TEST_PEER_ID,
                    peerSuffix = TEST_PEER_SUFFIX,
                    trustState = trustState,
                    connectionState = PeerConnectionSnapshotState.CONNECTED,
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

private const val TEST_PEER_ID: String = "peer-abcdef"
private const val TEST_PEER_SUFFIX: String = "abcdef"
