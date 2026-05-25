package ch.trancee.meshlink.reference.guided

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.reference.automation.ReferenceAutomationConfig
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.PeerConnectionSnapshotState
import ch.trancee.meshlink.reference.model.PeerSnapshot
import ch.trancee.meshlink.reference.model.PeerTrustState
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.session.InMemoryReferenceDocumentStore
import ch.trancee.meshlink.reference.session.ReferenceDocumentStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class GuidedFirstExchangeViewModelTest {
    @Test
    fun nextActionStartsWithMeshStartWhenUninitialized() {
        // Arrange
        val viewModel = GuidedFirstExchangeViewModel(platformServices = fakePlatformServices())

        // Act
        val uiState = viewModel.uiState.value

        // Assert
        assertEquals("Start MeshLink", uiState.nextActionLabel)
        assertTrue(uiState.readiness.isReadyToGuide)
    }

    @Test
    fun sendBecomesAvailableWhenPeerExists() {
        // Arrange
        val controller =
            FakeReferenceMeshLinkController(
                snapshot =
                    baseSnapshot()
                        .copy(
                            session =
                                baseSnapshot()
                                    .session
                                    .copy(meshStateLabel = MeshLinkState.Running.toString()),
                            peers =
                                listOf(
                                    PeerSnapshot(
                                        peerId = "peer-123456",
                                        peerSuffix = "123456",
                                        trustState = PeerTrustState.TRUSTED,
                                        connectionState = PeerConnectionSnapshotState.CONNECTED,
                                    )
                                ),
                        )
            )
        val viewModel =
            GuidedFirstExchangeViewModel(
                platformServices = fakePlatformServices(controller = controller)
            )

        // Act
        val uiState = viewModel.uiState.value

        // Assert
        assertTrue(uiState.canSendHello)
        assertEquals("123456", uiState.selectedPeerSuffix)
        assertEquals("Send the first guided message", uiState.nextActionLabel)
    }

    @Test
    fun nextActionShowsRecoveryWhenStartupIsBlocked() {
        // Arrange
        val viewModel =
            GuidedFirstExchangeViewModel(
                platformServices =
                    fakePlatformServices(
                        startupBlockers = listOf("Enable Bluetooth before starting MeshLink")
                    )
            )

        // Act
        val uiState = viewModel.uiState.value

        // Assert
        assertEquals("Resolve startup blockers", uiState.nextActionLabel)
        assertTrue(uiState.readiness.isBlocked)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun uiStateTracksSnapshotUpdatesFromTheController() = runTest {
        // Arrange
        val controller = FakeReferenceMeshLinkController(snapshot = baseSnapshot())
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val viewModel =
            GuidedFirstExchangeViewModel(
                platformServices = fakePlatformServices(controller = controller),
                scope = scope,
            )
        val updatedSnapshot =
            baseSnapshot()
                .copy(
                    session =
                        baseSnapshot()
                            .session
                            .copy(meshStateLabel = MeshLinkState.Running.toString()),
                    peers =
                        listOf(
                            PeerSnapshot(
                                peerId = "peer-123456",
                                peerSuffix = "123456",
                                trustState = PeerTrustState.TRUSTED,
                                connectionState = PeerConnectionSnapshotState.CONNECTED,
                            )
                        ),
                )
        advanceUntilIdle()

        try {
            // Act
            controller.emitSnapshot(updatedSnapshot)
            advanceUntilIdle()

            // Assert
            assertTrue(viewModel.uiState.value.canSendHello)
            assertEquals("123456", viewModel.uiState.value.selectedPeerSuffix)
            assertEquals("Send the first guided message", viewModel.uiState.value.nextActionLabel)
        } finally {
            scope.cancel()
        }
    }
}

private fun fakePlatformServices(
    controller: ReferenceMeshLinkController =
        FakeReferenceMeshLinkController(snapshot = baseSnapshot()),
    startupBlockers: List<String> = emptyList(),
): PlatformServices {
    return object : PlatformServices {
        override val platformName: String = "Test"
        override val defaultAuthorityMode: ReferenceAuthorityMode = ReferenceAuthorityMode.LIVE
        override val readinessGuidance: List<String> =
            listOf("Keep two devices nearby", "Stay offline")
        override val readinessBlockers: List<String> = startupBlockers
        override val automationConfig: ReferenceAutomationConfig? = null
        override val documentStore: ReferenceDocumentStore = InMemoryReferenceDocumentStore()
        override val meshLinkController: ReferenceMeshLinkController = controller

        override fun currentTimeMillis(): Long = 1_000L

        override fun emitAutomationLog(message: String): Unit = Unit
    }
}

private fun baseSnapshot(): ReferenceControllerSnapshot {
    return ReferenceControllerSnapshot(
        session =
            ReferenceSession(
                sessionId = "session-1",
                scenarioId = "guided-first-exchange",
                authorityMode = ReferenceAuthorityMode.LIVE,
                startedAtEpochMillis = 1_000L,
                meshStateLabel = MeshLinkState.Uninitialized.toString(),
                historyStatus = ReferenceHistoryStatus.LIVE,
            ),
        peers = emptyList(),
        timeline =
            listOf(
                TimelineEntry(
                    entryId = "session-1-1",
                    sessionId = "session-1",
                    occurredAtEpochMillis = 1_000L,
                    family = TimelineFamily.USER,
                    severity = TimelineSeverity.INFO,
                    title = "Initialized",
                    detail = "Test snapshot",
                )
            ),
        activePowerModeLabel = "Automatic",
    )
}

private class FakeReferenceMeshLinkController(snapshot: ReferenceControllerSnapshot) :
    ReferenceMeshLinkController {
    private val flow: MutableStateFlow<ReferenceControllerSnapshot> = MutableStateFlow(snapshot)

    override val snapshot: StateFlow<ReferenceControllerSnapshot> = flow.asStateFlow()

    fun emitSnapshot(snapshot: ReferenceControllerSnapshot): Unit {
        flow.value = snapshot
    }

    override suspend fun start() = Unit

    override suspend fun pause() = Unit

    override suspend fun resume() = Unit

    override suspend fun stop() = Unit

    override suspend fun sendPayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ) = Unit

    override suspend fun forgetPeer(peerId: String) = Unit
}
