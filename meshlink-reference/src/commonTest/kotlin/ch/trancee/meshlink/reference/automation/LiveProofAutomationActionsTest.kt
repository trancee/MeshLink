package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.navigation.SessionTransitionService
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import ch.trancee.meshlink.reference.session.InMemoryReferenceDocumentStore
import ch.trancee.meshlink.reference.session.ReferenceDocumentStore
import ch.trancee.meshlink.reference.timeline.TimelineStoreHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class LiveProofAutomationActionsTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun requestEndCurrentSessionEndsTheSupportedSessionThroughTheTransitionService() = runTest {
        // Arrange
        val harness = TimelineStoreHarness()
        val timelineStore = harness.createStore(scope = this)
        val sessionTransitionService =
            SessionTransitionService(
                timelineStore = timelineStore,
                sessionController = harness.sessionController,
                currentTimeMillis = { 1_000L },
            )
        val platformServices =
            object : PlatformServices {
                override val platformName: String = "Test"
                override val defaultAuthorityMode: ReferenceAuthorityMode =
                    ReferenceAuthorityMode.LIVE
                override val readinessGuidance: List<String> = emptyList()
                override val readinessBlockers: List<String> = emptyList()
                override val automationConfig: ReferenceAutomationConfig? = null
                override val documentStore: ReferenceDocumentStore =
                    InMemoryReferenceDocumentStore()
                override val meshLinkController = harness.sessionController

                override fun currentTimeMillis(): Long = 1_000L

                override fun emitAutomationLog(message: String): Unit = Unit
            }
        val actions =
            TimelineStoreLiveProofAutomationActions(
                platformServices = platformServices,
                timelineStore = timelineStore,
                sessionTransitionService = sessionTransitionService,
            )
        advanceUntilIdle()

        try {
            // Act
            actions.requestEndCurrentSession()
            advanceUntilIdle()

            // Assert
            assertEquals(true, timelineStore.uiState.value.isCurrentSessionEnded)
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun actionsDelegateLifecycleSendForgetAndExportRequestsToTheirOwners() = runTest {
        // Arrange
        val harness = TimelineStoreHarness()
        val timelineStore = harness.createStore(scope = this)
        val sessionTransitionService =
            SessionTransitionService(
                timelineStore = timelineStore,
                sessionController = harness.sessionController,
                currentTimeMillis = { 1_000L },
            )
        val controller = RecordingAutomationController(harness.sessionController.snapshot.value)
        val platformServices =
            object : PlatformServices {
                override val platformName: String = "Test"
                override val defaultAuthorityMode: ReferenceAuthorityMode =
                    ReferenceAuthorityMode.LIVE
                override val readinessGuidance: List<String> = emptyList()
                override val readinessBlockers: List<String> = emptyList()
                override val automationConfig: ReferenceAutomationConfig? = null
                override val documentStore: ReferenceDocumentStore =
                    InMemoryReferenceDocumentStore()
                override val meshLinkController: ReferenceMeshLinkController = controller

                override fun currentTimeMillis(): Long = 1_000L

                override fun emitAutomationLog(message: String): Unit = Unit
            }
        val actions =
            TimelineStoreLiveProofAutomationActions(
                platformServices = platformServices,
                timelineStore = timelineStore,
                sessionTransitionService = sessionTransitionService,
            )

        try {
            // Act
            actions.requestMeshStart()
            actions.requestMeshPause()
            actions.requestMeshResume()
            actions.requestSendPayload(
                peerId = "peer-123456",
                payloadText = "payload",
                priority = DeliveryPriority.HIGH,
            )
            actions.requestForgetPeer("peer-123456")
            actions.requestExportCurrentSession(ExportPayloadPolicy.REDACTED_PREVIEW)
            advanceUntilIdle()

            // Assert
            assertEquals(1, controller.startRequests)
            assertEquals(1, controller.pauseRequests)
            assertEquals(1, controller.resumeRequests)
            assertEquals(
                listOf(RecordedSendRequest("peer-123456", "payload", DeliveryPriority.HIGH)),
                controller.sendRequests,
            )
            assertEquals(listOf("peer-123456"), controller.forgetPeerRequests)
            assertEquals(
                "reference/exports/timeline-session-2000-redacted.json",
                timelineStore.uiState.value.lastExportPath,
            )
        } finally {
            coroutineContext.cancelChildren()
        }
    }
}

private class RecordingAutomationController(initialSnapshot: ReferenceControllerSnapshot) :
    ReferenceMeshLinkController {
    private val flow = MutableStateFlow(initialSnapshot)

    var startRequests: Int = 0
    var pauseRequests: Int = 0
    var resumeRequests: Int = 0
    val sendRequests: MutableList<RecordedSendRequest> = mutableListOf()
    val forgetPeerRequests: MutableList<String> = mutableListOf()

    override val snapshot: StateFlow<ReferenceControllerSnapshot> = flow

    override suspend fun start() {
        startRequests += 1
    }

    override suspend fun pause() {
        pauseRequests += 1
    }

    override suspend fun resume() {
        resumeRequests += 1
    }

    override suspend fun stop(): Unit = Unit

    override suspend fun sendPayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ) {
        sendRequests +=
            RecordedSendRequest(peerId = peerId, payloadText = payloadText, priority = priority)
    }

    override suspend fun forgetPeer(peerId: String) {
        forgetPeerRequests += peerId
    }
}

private data class RecordedSendRequest(
    val peerId: String,
    val payloadText: String,
    val priority: DeliveryPriority,
)
