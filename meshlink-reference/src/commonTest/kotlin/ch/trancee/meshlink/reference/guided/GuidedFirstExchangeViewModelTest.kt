package ch.trancee.meshlink.reference.guided

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

class GuidedFirstExchangeViewModelTest {
    @Test
    fun nextActionStartsWithMeshStartWhenUninitialized() {
        val viewModel = GuidedFirstExchangeViewModel(platformServices = fakePlatformServices())

        assertEquals("Start MeshLink", viewModel.uiState.value.nextActionLabel)
        assertTrue(viewModel.uiState.value.readiness.isReadyToGuide)
    }

    @Test
    fun sendBecomesAvailableWhenPeerExists() {
        val controller = FakeReferenceMeshLinkController(
            snapshot =
                baseSnapshot().copy(
                    session = baseSnapshot().session.copy(meshStateLabel = MeshLinkState.Running.toString()),
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
        val viewModel = GuidedFirstExchangeViewModel(platformServices = fakePlatformServices(controller = controller))

        assertTrue(viewModel.uiState.value.canSendHello)
        assertEquals("123456", viewModel.uiState.value.selectedPeerSuffix)
        assertEquals("Send the first guided message", viewModel.uiState.value.nextActionLabel)
    }
}

private fun fakePlatformServices(
    controller: ReferenceMeshLinkController = FakeReferenceMeshLinkController(snapshot = baseSnapshot()),
): PlatformServices {
    return object : PlatformServices {
        override val platformName: String = "Test"
        override val defaultAuthorityMode: ReferenceAuthorityMode = ReferenceAuthorityMode.LIVE
        override val readinessGuidance: List<String> = listOf("Keep two devices nearby", "Stay offline")
        override val meshLinkController: ReferenceMeshLinkController = controller

        override fun currentTimeMillis(): Long = 1_000L
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

private class FakeReferenceMeshLinkController(
    snapshot: ReferenceControllerSnapshot,
) : ReferenceMeshLinkController {
    private val flow: MutableStateFlow<ReferenceControllerSnapshot> = MutableStateFlow(snapshot)

    override val snapshot: StateFlow<ReferenceControllerSnapshot> = flow.asStateFlow()

    override suspend fun start() = Unit

    override suspend fun pause() = Unit

    override suspend fun resume() = Unit

    override suspend fun stop() = Unit

    override suspend fun sendSamplePayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ) = Unit

    override suspend fun forgetPeer(peerId: String) = Unit
}
