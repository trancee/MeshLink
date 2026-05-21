package ch.trancee.meshlink.reference.timeline

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.automation.ReferenceAutomationConfig
import ch.trancee.meshlink.reference.automation.ReferenceAutomationMode
import ch.trancee.meshlink.reference.automation.ReferenceAutomationRole
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.session.InMemoryReferenceDocumentStore
import ch.trancee.meshlink.reference.session.JsonSessionArtifactSerializer
import ch.trancee.meshlink.reference.session.JsonSessionHistoryRepository
import ch.trancee.meshlink.reference.session.ReferenceDocumentStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
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
        val harness = TimelineStoreHarness()
        val store = harness.createStore(scope = this)
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun retainCurrentSessionAutoExportsRedactedArtifactInScriptedAutomation() = runTest {
        // Arrange
        val harness =
            TimelineStoreHarness(
                activeAutomationConfig =
                    ReferenceAutomationConfig(
                        mode = ReferenceAutomationMode.SCRIPTED_UI,
                        role = ReferenceAutomationRole.PASSIVE,
                        appId = "demo.meshlink.reference.automation",
                        storageSubdirectory = "timeline-test",
                    )
            )
        val store = harness.createStore(scope = this)
        val expectedExportPath = "reference/exports/timeline-session.json"
        advanceUntilIdle()

        // Act
        store.retainCurrentSession()
        advanceUntilIdle()

        // Assert
        assertEquals(expectedExportPath, store.uiState.value.lastExportPath)
        assertEquals(
            true,
            harness.documentStore
                .readText(expectedExportPath)
                ?.contains("\"defaultMode\": \"redacted-preview\""),
        )
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
        store.retainCurrentSession()
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun unfilteredLiveSnapshotReusesTheTimelineList() = runTest {
        // Arrange
        val entry = timelineEntry(entryId = "live-1", title = "Live")
        val harness = TimelineStoreHarness()
        val store = harness.createStore(scope = this)
        advanceUntilIdle()

        // Act
        harness.emitLiveSnapshot(timeline = listOf(entry))
        advanceUntilIdle()

        // Assert
        assertSame(store.uiState.value.liveSnapshot.timeline, store.uiState.value.visibleEntries)
        coroutineContext.cancelChildren()
    }
}

private class TimelineStoreHarness(
    initialTimeline: List<TimelineEntry> = emptyList(),
    activeAutomationConfig: ReferenceAutomationConfig? = null,
) {
    val documentStore: InMemoryReferenceDocumentStore = InMemoryReferenceDocumentStore()
    private val controllerFlow: MutableStateFlow<ReferenceControllerSnapshot> =
        MutableStateFlow(referenceSnapshot(timeline = initialTimeline))

    private val platformServices: PlatformServices =
        object : PlatformServices {
            override val platformName: String = "Test"
            override val defaultAuthorityMode: ReferenceAuthorityMode = ReferenceAuthorityMode.LIVE
            override val readinessGuidance: List<String> = emptyList()
            override val readinessBlockers: List<String> = emptyList()
            override val automationConfig: ReferenceAutomationConfig? = activeAutomationConfig
            override val documentStore: ReferenceDocumentStore =
                this@TimelineStoreHarness.documentStore
            override val meshLinkController: ReferenceMeshLinkController =
                object : ReferenceMeshLinkController {
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

            override fun currentTimeMillis(): Long = 2_000L

            override fun emitAutomationLog(message: String): Unit = Unit
        }

    fun createStore(scope: kotlinx.coroutines.CoroutineScope): TechnicalTimelineStore {
        return TechnicalTimelineStore(
            platformServices = platformServices,
            historyRepository = JsonSessionHistoryRepository(documentStore = documentStore),
            artifactSerializer = JsonSessionArtifactSerializer(documentStore = documentStore),
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
