package ch.trancee.meshlink.reference.advanced

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.PeerConnectionSnapshotState
import ch.trancee.meshlink.reference.model.PeerSnapshot
import ch.trancee.meshlink.reference.model.PeerTrustState
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.session.InMemoryReferenceDocumentStore
import ch.trancee.meshlink.reference.session.ReferenceDocumentStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class AdvancedControlsViewModelStateTest {
    @Test
    fun advancedControlsRetainInteractiveTogglesAcrossSnapshotRefreshes() = runTest {
        // Arrange
        val controller = RecordingReferenceMeshLinkController(referenceSnapshot())
        val viewModel = newViewModel(controller, testScheduler)
        advanceUntilIdle()

        // Act
        viewModel.selectPeer("peer-b")
        viewModel.updateComposerText("relay this carefully")
        viewModel.updatePriority(DeliveryPriority.HIGH)
        controller.snapshot.value =
            referenceSnapshot(
                peers =
                    listOf(
                        peerSnapshot(peerId = "peer-a", peerSuffix = "aaa111"),
                        peerSnapshot(peerId = "peer-b", peerSuffix = "bbb222"),
                    ),
                meshStateLabel = "Running",
                activePowerModeLabel = "Eco",
            )
        advanceUntilIdle()

        // Assert
        assertEquals("peer-b", viewModel.uiState.value.selectedPeerId)
        assertEquals("relay this carefully", viewModel.uiState.value.composerText)
        assertEquals(DeliveryPriority.HIGH, viewModel.uiState.value.selectedPriority)
        assertEquals("Running", viewModel.uiState.value.meshStateLabel)
        assertEquals("Eco", viewModel.uiState.value.activePowerModeLabel)
        assertEquals(
            "bbb222",
            viewModel.uiState.value.peerRows.single { it.peerId == "peer-b" }.peerSuffix,
        )
    }

    @Test
    fun advancedControlsDispatchLifecycleAndSendBranchesThroughSelectedPeerOnly() = runTest {
        // Arrange
        val controller = RecordingReferenceMeshLinkController(referenceSnapshot())
        val viewModel = newViewModel(controller, testScheduler)
        advanceUntilIdle()

        // Act
        viewModel.startMesh()
        viewModel.pauseMesh()
        viewModel.resumeMesh()
        viewModel.stopMesh()
        viewModel.sendCurrentMessage()
        advanceUntilIdle()

        viewModel.selectPeer("peer-1")
        viewModel.updateComposerText("payload from advanced controls")
        viewModel.updatePriority(DeliveryPriority.HIGH)
        viewModel.sendCurrentMessage()
        viewModel.sendLargeTransferPreview()
        viewModel.forgetSelectedPeer()
        advanceUntilIdle()

        // Assert
        assertEquals(listOf("start", "pause", "resume", "stop"), controller.lifecycleCalls)
        assertEquals(
            listOf(
                RecordedPayload(
                    peerId = "peer-1",
                    payloadText = "hello mesh from Test advanced",
                    priority = DeliveryPriority.NORMAL,
                ),
                RecordedPayload(
                    peerId = "peer-1",
                    payloadText = "payload from advanced controls",
                    priority = DeliveryPriority.HIGH,
                ),
                RecordedPayload(
                    peerId = "peer-1",
                    payloadText = buildAdvancedLargeTransferPreviewPayload(),
                    priority = DeliveryPriority.HIGH,
                ),
            ),
            controller.sentPayloads,
        )
        assertEquals(listOf("peer-1"), controller.forgottenPeers)
        assertFalse(viewModel.uiState.value.isSessionEnded)
    }

    private fun newViewModel(
        controller: RecordingReferenceMeshLinkController,
        scheduler: TestCoroutineScheduler,
    ): AdvancedControlsViewModel {
        return AdvancedControlsViewModel(
            platformServices = recordingPlatformServices(controller),
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler)),
        )
    }

    private fun recordingPlatformServices(
        controller: RecordingReferenceMeshLinkController
    ): PlatformServices {
        return object : PlatformServices {
            override val platformName: String = "Test"
            override val defaultAuthorityMode: ReferenceAuthorityMode = ReferenceAuthorityMode.LIVE
            override val readinessGuidance: List<String> = emptyList()
            override val readinessBlockers: List<String> = emptyList()
            override val automationConfig = null
            override val powerMitigationStatus = null
            override val documentStore: ReferenceDocumentStore = InMemoryReferenceDocumentStore()
            override val meshLinkController: ReferenceMeshLinkController = controller

            override fun stopPowerMitigation(): Unit = Unit

            override fun currentTimeMillis(): Long = 1L

            override fun emitAutomationLog(message: String): Unit = Unit
        }
    }

    private fun referenceSnapshot(
        peers: List<PeerSnapshot> = listOf(peerSnapshot()),
        meshStateLabel: String = "Idle",
        activePowerModeLabel: String = "Automatic",
    ): ReferenceControllerSnapshot {
        return ReferenceControllerSnapshot(
            session =
                ReferenceSession(
                    sessionId = "session-1",
                    scenarioId = "guided-first-exchange",
                    authorityMode = ReferenceAuthorityMode.LIVE,
                    startedAtEpochMillis = 1L,
                    meshStateLabel = meshStateLabel,
                    configurationSnapshot = mapOf("platform" to "Test"),
                ),
            peers = peers,
            timeline =
                listOf(
                    TimelineEntry(
                        entryId = "entry-1",
                        sessionId = "session-1",
                        title = "initial",
                        detail = "initial snapshot",
                        occurredAtEpochMillis = 1L,
                        family = TimelineFamily.MESSAGE,
                        severity = TimelineSeverity.INFO,
                    )
                ),
            activePowerModeLabel = activePowerModeLabel,
        )
    }

    private fun peerSnapshot(
        peerId: String = "peer-1",
        peerSuffix: String = "abc123",
    ): PeerSnapshot {
        return PeerSnapshot(
            peerId = peerId,
            peerSuffix = peerSuffix,
            trustState = PeerTrustState.TRUSTED,
            connectionState = PeerConnectionSnapshotState.CONNECTED,
        )
    }
}

private data class RecordedPayload(
    val peerId: String,
    val payloadText: String,
    val priority: DeliveryPriority,
)

private class RecordingReferenceMeshLinkController(initialSnapshot: ReferenceControllerSnapshot) :
    ReferenceMeshLinkController {
    override val snapshot: MutableStateFlow<ReferenceControllerSnapshot> =
        MutableStateFlow(initialSnapshot)
    val lifecycleCalls: MutableList<String> = mutableListOf()
    val sentPayloads: MutableList<RecordedPayload> = mutableListOf()
    val forgottenPeers: MutableList<String> = mutableListOf()

    override suspend fun start(): Unit {
        lifecycleCalls += "start"
    }

    override suspend fun pause(): Unit {
        lifecycleCalls += "pause"
    }

    override suspend fun resume(): Unit {
        lifecycleCalls += "resume"
    }

    override suspend fun stop(): Unit {
        lifecycleCalls += "stop"
    }

    override suspend fun sendPayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ): Unit {
        sentPayloads += RecordedPayload(peerId, payloadText, priority)
    }

    override suspend fun forgetPeer(peerId: String): Unit {
        forgottenPeers += peerId
    }
}
