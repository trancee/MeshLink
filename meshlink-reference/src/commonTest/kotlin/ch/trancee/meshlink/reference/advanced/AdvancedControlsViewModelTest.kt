package ch.trancee.meshlink.reference.advanced

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.MeshLinkState
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AdvancedControlsViewModelTest {
    @Test
    fun exposesConfigAndFirstPeerSelection() {
        val viewModel = AdvancedControlsViewModel(platformServices = advancedPlatformServices())

        assertEquals("demo.meshlink.reference", viewModel.uiState.value.config.appId)
        assertEquals("abc123", viewModel.uiState.value.peerRows.first().peerSuffix)
        assertEquals("peer-abc123", viewModel.uiState.value.selectedPeerId)
    }

    @Test
    fun priorityChangeUpdatesComposerState() {
        val viewModel = AdvancedControlsViewModel(platformServices = advancedPlatformServices())

        viewModel.updatePriority(DeliveryPriority.HIGH)
        viewModel.updateComposerText("payload")

        assertEquals(DeliveryPriority.HIGH, viewModel.uiState.value.selectedPriority)
        assertTrue(viewModel.uiState.value.canSend)
    }
}

internal fun advancedPlatformServices(): PlatformServices {
    return object : PlatformServices {
        override val platformName: String = "Test"
        override val defaultAuthorityMode: ReferenceAuthorityMode = ReferenceAuthorityMode.LIVE
        override val readinessGuidance: List<String> = listOf("Step 1")
        override val meshLinkController: ReferenceMeshLinkController =
            object : ReferenceMeshLinkController {
                private val flow =
                    MutableStateFlow(
                        ReferenceControllerSnapshot(
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
                    )

                override val snapshot: StateFlow<ReferenceControllerSnapshot> = flow.asStateFlow()

                override suspend fun start() = Unit
                override suspend fun pause() = Unit
                override suspend fun resume() = Unit
                override suspend fun stop() = Unit
                override suspend fun sendSamplePayload(peerId: String, payloadText: String, priority: DeliveryPriority) = Unit
                override suspend fun forgetPeer(peerId: String) = Unit
            }

        override fun currentTimeMillis(): Long = 1_000L
    }
}
