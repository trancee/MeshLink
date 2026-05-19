package ch.trancee.meshlink.reference.timeline

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.automation.ReferenceAutomationConfig
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.session.InMemoryReferenceDocumentStore
import ch.trancee.meshlink.reference.session.JsonSessionArtifactSerializer
import ch.trancee.meshlink.reference.session.JsonSessionHistoryRepository
import ch.trancee.meshlink.reference.session.ReferenceDocumentStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class TechnicalTimelineStoreTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun retainCurrentSessionPublishesRetainedSessionState() = runTest {
        // Arrange
        val documentStore = InMemoryReferenceDocumentStore()
        val store =
            TechnicalTimelineStore(
                platformServices = timelinePlatformServices(),
                historyRepository = JsonSessionHistoryRepository(documentStore = documentStore),
                artifactSerializer = JsonSessionArtifactSerializer(documentStore = documentStore),
                scope = this,
            )
        advanceUntilIdle()

        // Act
        store.retainCurrentSession()
        advanceUntilIdle()

        // Assert
        assertEquals(1, store.uiState.value.retainedSessions.size)
        assertEquals(
            ReferenceHistoryStatus.RETAINED,
            store.uiState.value.retainedSessions.single().historyStatus,
        )
        coroutineContext.cancelChildren()
    }
}

private fun timelinePlatformServices(): PlatformServices {
    return object : PlatformServices {
        override val platformName: String = "Test"
        override val defaultAuthorityMode: ReferenceAuthorityMode = ReferenceAuthorityMode.LIVE
        override val readinessGuidance: List<String> = emptyList()
        override val readinessBlockers: List<String> = emptyList()
        override val automationConfig: ReferenceAutomationConfig? = null
        override val documentStore: ReferenceDocumentStore = InMemoryReferenceDocumentStore()
        override val meshLinkController: ReferenceMeshLinkController =
            object : ReferenceMeshLinkController {
                private val flow =
                    MutableStateFlow(
                        ReferenceControllerSnapshot(
                            session =
                                ReferenceSession(
                                    sessionId = "timeline-session",
                                    scenarioId = "guided-first-exchange",
                                    authorityMode = ReferenceAuthorityMode.LIVE,
                                    startedAtEpochMillis = 1_000L,
                                    historyStatus = ReferenceHistoryStatus.LIVE,
                                ),
                            peers = emptyList(),
                            timeline = emptyList(),
                            activePowerModeLabel = "Automatic",
                        )
                    )

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

        override fun currentTimeMillis(): Long = 2_000L

        override fun emitAutomationLog(message: String): Unit = Unit
    }
}
