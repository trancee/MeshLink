package ch.trancee.meshlink.reference.timeline

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.automation.ReferenceAutomationConfig
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import ch.trancee.meshlink.reference.navigation.ReferenceSurfaceId
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.session.InMemoryReferenceDocumentStore
import ch.trancee.meshlink.reference.session.JsonSessionArtifactSerializer
import ch.trancee.meshlink.reference.session.JsonSessionHistoryRepository
import ch.trancee.meshlink.reference.session.ReferenceDocumentStore
import ch.trancee.meshlink.reference.session.ReferenceSessionController
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
    fun endCurrentSessionPublishesRetainedSessionState() = runTest {
        // Arrange
        val harness =
            TimelineStoreHarness(initialTimeline = listOf(timelineEntry("live-1", "Live")))
        val store = harness.createStore(scope = this)
        advanceUntilIdle()

        // Act
        store.endCurrentSession()
        advanceUntilIdle()

        // Assert
        assertEquals(true, store.uiState.value.isCurrentSessionEnded)
        assertEquals(1, store.uiState.value.retainedSessions.size)
        assertEquals(
            ReferenceHistoryStatus.RETAINED,
            store.uiState.value.retainedSessions.single().historyStatus,
        )
        coroutineContext.cancelChildren()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun exportCurrentSessionCreatesUniqueArtifactInstancePath() = runTest {
        // Arrange
        val harness = TimelineStoreHarness()
        val store = harness.createStore(scope = this)
        advanceUntilIdle()
        val firstNow = harness.nowMillis

        // Act
        store.exportCurrentSession(
            ch.trancee.meshlink.reference.session.ExportPayloadPolicy.REDACTED_PREVIEW
        )
        advanceUntilIdle()
        harness.nowMillis = firstNow + 100L
        store.exportCurrentSession(
            ch.trancee.meshlink.reference.session.ExportPayloadPolicy.REDACTED_PREVIEW
        )
        advanceUntilIdle()

        // Assert
        assertEquals(
            "reference/exports/timeline-session-2100-redacted.json",
            store.uiState.value.lastExportPath,
        )
        coroutineContext.cancelChildren()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun transitionToSoloSessionStartsSoloSnapshotWithoutRetention() = runTest {
        // Arrange
        val harness = TimelineStoreHarness()
        val store = harness.createStore(scope = this)
        advanceUntilIdle()

        // Act
        store.transitionToSoloSession()
        advanceUntilIdle()

        // Assert
        assertEquals(
            ReferenceAuthorityMode.SOLO,
            store.uiState.value.liveSnapshot.session.authorityMode,
        )
        assertEquals(0, store.uiState.value.retainedSessions.size)
        coroutineContext.cancelChildren()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun transitionAlternativeSessionCanReturnToSupportedLiveSession() = runTest {
        // Arrange
        val harness = TimelineStoreHarness()
        val store = harness.createStore(scope = this)
        advanceUntilIdle()
        store.transitionToSoloSession()
        advanceUntilIdle()

        // Act
        store.transitionAlternativeSession(
            targetSurface = ReferenceSurfaceId.MAIN_GUIDED,
            exportBeforeExit = false,
        )
        advanceUntilIdle()

        // Assert
        assertEquals(
            ReferenceAuthorityMode.LIVE,
            store.uiState.value.liveSnapshot.session.authorityMode,
        )
        assertEquals(null, store.uiState.value.liveSnapshot.session.endedAtEpochMillis)
        coroutineContext.cancelChildren()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun retainedSessionViewSurvivesLaterLiveSnapshotUpdates() = runTest {
        // Arrange
        val retainedEntry = timelineEntry(entryId = "retained-1", title = "Retained")
        val liveEntry = timelineEntry(entryId = "live-1", title = "Live")
        val harness = TimelineStoreHarness(initialTimeline = listOf(retainedEntry))
        val store = harness.createStore(scope = this)
        advanceUntilIdle()
        store.endCurrentSession()
        advanceUntilIdle()
        store.openRetainedSession(sessionId = "timeline-session")
        advanceUntilIdle()

        // Act
        harness.emitLiveSnapshot(timeline = listOf(liveEntry))
        advanceUntilIdle()

        // Assert
        assertEquals(true, store.uiState.value.viewingRetained)
        assertEquals(listOf("retained-1"), store.uiState.value.visibleEntries.map { it.entryId })
        coroutineContext.cancelChildren()
    }
}

private class TimelineStoreHarness(
    initialTimeline: List<TimelineEntry> = emptyList(),
    activeAutomationConfig: ReferenceAutomationConfig? = null,
    initialNowMillis: Long = 2_000L,
) {
    val documentStore: InMemoryReferenceDocumentStore = InMemoryReferenceDocumentStore()
    var nowMillis: Long = initialNowMillis
    private val controllerFlow: MutableStateFlow<ReferenceControllerSnapshot> =
        MutableStateFlow(referenceSnapshot(timeline = initialTimeline))

    private fun rawController(): ReferenceMeshLinkController {
        return object : ReferenceMeshLinkController {
            override val snapshot: StateFlow<ReferenceControllerSnapshot> =
                controllerFlow.asStateFlow()

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
        }
    }

    private val sessionController: ReferenceSessionController =
        ReferenceSessionController(
            platformName = "Test",
            nowProvider = { nowMillis },
            supportedControllerFactory = { rawController() },
        )

    private val platformServices: PlatformServices =
        object : PlatformServices {
            override val platformName: String = "Test"
            override val defaultAuthorityMode: ReferenceAuthorityMode = ReferenceAuthorityMode.LIVE
            override val readinessGuidance: List<String> = emptyList()
            override val readinessBlockers: List<String> = emptyList()
            override val automationConfig: ReferenceAutomationConfig? = activeAutomationConfig
            override val documentStore: ReferenceDocumentStore =
                this@TimelineStoreHarness.documentStore
            override val meshLinkController: ReferenceMeshLinkController = sessionController

            override fun createSupportedMeshLinkController(
                surfaceOfOrigin: String
            ): ReferenceMeshLinkController {
                return rawController()
            }

            override fun currentTimeMillis(): Long = nowMillis

            override fun emitAutomationLog(message: String): Unit = Unit
        }

    fun createStore(scope: kotlinx.coroutines.CoroutineScope): TechnicalTimelineStore {
        return TechnicalTimelineStore(
            platformServices = platformServices,
            historyRepository = JsonSessionHistoryRepository(documentStore = documentStore),
            artifactSerializer = JsonSessionArtifactSerializer(documentStore = documentStore),
            sessionController = sessionController,
            scope = scope,
        )
    }

    fun emitLiveSnapshot(timeline: List<TimelineEntry>): Unit {
        controllerFlow.value = referenceSnapshot(timeline = timeline)
    }
}

private fun referenceSnapshot(timeline: List<TimelineEntry>): ReferenceControllerSnapshot {
    return ReferenceControllerSnapshot(
        session =
            ReferenceSession(
                sessionId = "timeline-session",
                scenarioId = "guided-first-exchange",
                authorityMode = ReferenceAuthorityMode.LIVE,
                startedAtEpochMillis = 1_000L,
                historyStatus = ReferenceHistoryStatus.LIVE,
                configurationSnapshot = mapOf("surface" to "main-guided"),
            ),
        peers = emptyList(),
        timeline = timeline,
        activePowerModeLabel = "Automatic",
    )
}

private fun timelineEntry(entryId: String, title: String): TimelineEntry {
    return TimelineEntry(
        entryId = entryId,
        sessionId = "timeline-session",
        occurredAtEpochMillis = 1L,
        family = TimelineFamily.MESSAGE,
        severity = TimelineSeverity.INFO,
        title = title,
        detail = "$title detail",
        searchText = title,
    )
}
