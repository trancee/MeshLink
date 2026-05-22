package ch.trancee.meshlink.reference.advanced

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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

class AdvancedControlsViewModelTest {
    @Test
    fun exposesConfigAndFirstPeerSelection() {
        // Arrange
        val viewModel = AdvancedControlsViewModel(platformServices = advancedPlatformServices())

        // Act
        val uiState = viewModel.uiState.value

        // Assert
        assertEquals("demo.meshlink.reference", uiState.config.appId)
        assertEquals("abc123", uiState.peerRows.first().peerSuffix)
        assertEquals("peer-abc123", uiState.selectedPeerId)
    }

    @Test
    fun priorityChangeUpdatesComposerState() {
        // Arrange
        val viewModel = AdvancedControlsViewModel(platformServices = advancedPlatformServices())

        // Act
        viewModel.updatePriority(DeliveryPriority.HIGH)
        viewModel.updateComposerText("payload")

        // Assert
        assertEquals(DeliveryPriority.HIGH, viewModel.uiState.value.selectedPriority)
        assertTrue(viewModel.uiState.value.canSendMessage)
    }

    @Test
    fun oversizePayloadDisablesMessageSendButKeepsLargeTransferAvailable() {
        // Arrange
        val viewModel = AdvancedControlsViewModel(platformServices = advancedPlatformServices())
        val oversizePayload = "a".repeat(ADVANCED_PAYLOAD_LIMIT_BYTES + 1)

        // Act
        viewModel.updateComposerText(oversizePayload)
        val uiState = viewModel.uiState.value

        // Assert
        assertEquals(ADVANCED_PAYLOAD_LIMIT_BYTES + 1, uiState.payloadSizeBytes)
        assertFalse(uiState.canSendMessage)
        assertTrue(uiState.canSendLargeTransfer)
        assertNotNull(uiState.payloadValidationMessage)
        assertTrue(
            uiState.payloadValidationMessage.contains(ADVANCED_PAYLOAD_LIMIT_BYTES.toString())
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun lifecycleActionsTrackSnapshotState() = runTest {
        // Arrange
        val controller = TestReferenceMeshLinkController()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val viewModel =
            AdvancedControlsViewModel(
                platformServices = advancedPlatformServices(controller = controller),
                scope = scope,
            )
        val expected = LifecycleActionState.from(MeshLinkState.Paused.toString())
        advanceUntilIdle()

        try {
            // Act
            controller.updateMeshState(MeshLinkState.Paused.toString())
            advanceUntilIdle()

            // Assert
            assertEquals(expected, viewModel.lifecycleActions.value)
        } finally {
            scope.cancel()
        }
    }
}

internal fun advancedPlatformServices(
    controller: ReferenceMeshLinkController = TestReferenceMeshLinkController()
): PlatformServices {
    return object : PlatformServices {
        override val platformName: String = "Test"
        override val defaultAuthorityMode: ReferenceAuthorityMode = ReferenceAuthorityMode.LIVE
        override val readinessGuidance: List<String> = listOf("Step 1")
        override val readinessBlockers: List<String> = emptyList()
        override val automationConfig: ReferenceAutomationConfig? = null
        override val documentStore: ReferenceDocumentStore = InMemoryReferenceDocumentStore()
        override val meshLinkController: ReferenceMeshLinkController = controller

        override fun currentTimeMillis(): Long = 1_000L

        override fun emitAutomationLog(message: String): Unit = Unit
    }
}

private class TestReferenceMeshLinkController(
    initialSnapshot: ReferenceControllerSnapshot = advancedSnapshot()
) : ReferenceMeshLinkController {
    private val flow: MutableStateFlow<ReferenceControllerSnapshot> =
        MutableStateFlow(initialSnapshot)

    override val snapshot: StateFlow<ReferenceControllerSnapshot> = flow.asStateFlow()

    override suspend fun start(): Unit = Unit

    override suspend fun pause(): Unit = Unit

    override suspend fun resume(): Unit = Unit

    override suspend fun stop(): Unit = Unit

    override suspend fun sendSamplePayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ): Unit = Unit

    override suspend fun forgetPeer(peerId: String): Unit = Unit

    fun updateMeshState(meshStateLabel: String): Unit {
        flow.value =
            flow.value.copy(session = flow.value.session.copy(meshStateLabel = meshStateLabel))
    }
}

private fun advancedSnapshot(): ReferenceControllerSnapshot {
    return ReferenceControllerSnapshot(
        session =
            ReferenceSession(
                sessionId = "advanced-session",
                scenarioId = "advanced-controls",
                authorityMode = ReferenceAuthorityMode.LIVE,
                startedAtEpochMillis = 1_000L,
                meshStateLabel = MeshLinkState.Running.toString(),
                configurationSnapshot =
                    mapOf(
                        "appId" to "demo.meshlink.reference",
                        "regulatoryRegion" to "DEFAULT",
                        "powerMode" to "Automatic",
                        "deliveryRetryDeadline" to "15s",
                    ),
                historyStatus = ReferenceHistoryStatus.LIVE,
            ),
        peers =
            listOf(
                PeerSnapshot(
                    peerId = "peer-abc123",
                    peerSuffix = "abc123",
                    trustState = PeerTrustState.TRUSTED,
                    connectionState = PeerConnectionSnapshotState.CONNECTED,
                    lastDeliveryOutcome = "Sent",
                )
            ),
        timeline =
            listOf(
                TimelineEntry(
                    entryId = "advanced-session-1",
                    sessionId = "advanced-session",
                    occurredAtEpochMillis = 1_000L,
                    family = TimelineFamily.DIAGNOSTIC,
                    severity = TimelineSeverity.INFO,
                    title = "Mesh started",
                    detail = "mesh.start() -> Started",
                )
            ),
        activePowerModeLabel = "Automatic",
    )
}
