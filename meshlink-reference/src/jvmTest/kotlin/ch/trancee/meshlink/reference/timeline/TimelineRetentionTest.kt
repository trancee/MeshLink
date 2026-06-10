package ch.trancee.meshlink.reference.timeline

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.session.InMemoryReferenceDocumentStore
import ch.trancee.meshlink.reference.session.JsonSessionArtifactSerializer
import ch.trancee.meshlink.reference.session.JsonSessionHistoryRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class TimelineRetentionTest {
    @Test
    fun appendsEntriesInEncounterOrderAndKeepsTheLatestLiveViewAfterRetainedSessionClears() =
        runTest {
            // Arrange
            val liveTimeline = listOf(timelineEntry("live-1"), timelineEntry("live-2"))
            val retainedTimeline = listOf(timelineEntry("retained-1"), timelineEntry("retained-2"))
            val liveSnapshot = snapshot(sessionId = "live-session", timeline = liveTimeline)
            val retainedSnapshot =
                snapshot(sessionId = "retained-session", timeline = retainedTimeline)
            val documentStore = InMemoryReferenceDocumentStore()
            val historyRepository = JsonSessionHistoryRepository(documentStore = documentStore)
            historyRepository.retainSnapshot(retainedSnapshot)
            val controller = FakeReferenceController(liveSnapshot)
            val store =
                newStore(
                    controller = controller,
                    historyRepository = historyRepository,
                    artifactSerializer =
                        JsonSessionArtifactSerializer(documentStore = documentStore),
                    scope = backgroundScope,
                )

            // Act
            controller.publish(retainedSnapshot)
            store.openRetainedSession("retained-session")
            advanceUntilIdle()
            store.openLiveSession()
            advanceUntilIdle()
            store.clearRetainedSessions()
            advanceUntilIdle()

            // Assert
            assertEquals(liveTimeline, store.uiState.value.liveSnapshot.timeline)
            assertEquals(liveTimeline, store.uiState.value.visibleEntries)
            assertNull(store.uiState.value.retainedSnapshot)
            assertTrue(store.uiState.value.retainedSessions.isEmpty())
        }

    @Test
    fun failurePathKeepsRetainedTimelineCoherentWhenASessionCannotBeLoaded() = runTest {
        // Arrange
        val liveTimeline = listOf(timelineEntry("live-1"), timelineEntry("live-2"))
        val retainedTimeline = listOf(timelineEntry("retained-1"))
        val liveSnapshot = snapshot(sessionId = "live-session", timeline = liveTimeline)
        val retainedSnapshot = snapshot(sessionId = "retained-session", timeline = retainedTimeline)
        val documentStore = InMemoryReferenceDocumentStore()
        val historyRepository = JsonSessionHistoryRepository(documentStore = documentStore)
        historyRepository.retainSnapshot(retainedSnapshot)
        val controller = FakeReferenceController(liveSnapshot)
        val store =
            newStore(
                controller = controller,
                historyRepository = historyRepository,
                artifactSerializer = JsonSessionArtifactSerializer(documentStore = documentStore),
                scope = backgroundScope,
            )

        // Act
        controller.publish(retainedSnapshot)
        store.openRetainedSession("missing-session")
        advanceUntilIdle()

        // Assert
        assertEquals(liveTimeline, store.uiState.value.liveSnapshot.timeline)
        assertEquals(liveTimeline, store.uiState.value.visibleEntries)
        assertNull(store.uiState.value.retainedSnapshot)
        assertTrue(store.uiState.value.visibleEntries == liveTimeline)
    }

    private fun newStore(
        controller: FakeReferenceController,
        historyRepository: JsonSessionHistoryRepository,
        artifactSerializer: JsonSessionArtifactSerializer,
        scope: CoroutineScope,
    ): TechnicalTimelineStore {
        return TechnicalTimelineStore(
            platformServices = FakePlatformServices(controller),
            historyRepository = historyRepository,
            artifactSerializer = artifactSerializer,
            scope = scope,
        )
    }

    private fun snapshot(
        sessionId: String,
        timeline: List<TimelineEntry>,
    ): ReferenceControllerSnapshot {
        return ReferenceControllerSnapshot(
            session =
                ReferenceSession(
                    sessionId = sessionId,
                    scenarioId = "guided-first-exchange",
                    authorityMode = ReferenceAuthorityMode.LIVE,
                    startedAtEpochMillis = 1L,
                    configurationSnapshot = mapOf("platform" to "JVM"),
                ),
            peers = emptyList(),
            timeline = timeline,
            activePowerModeLabel = "Automatic",
        )
    }

    private fun timelineEntry(entryId: String): TimelineEntry {
        return TimelineEntry(
            entryId = entryId,
            sessionId = "session-for-$entryId",
            occurredAtEpochMillis = if (entryId.endsWith("1")) 10L else 20L,
            family = TimelineFamily.LIFECYCLE,
            severity = TimelineSeverity.INFO,
            title = entryId,
            detail = "detail for $entryId",
        )
    }
}

private class FakePlatformServices(override val meshLinkController: FakeReferenceController) :
    PlatformServices {
    override val platformName: String = "JVM"
    override val defaultAuthorityMode: ReferenceAuthorityMode = ReferenceAuthorityMode.LIVE
    override val readinessGuidance: List<String> = emptyList()
    override val readinessBlockers: List<String> = emptyList()
    override val automationConfig = null
    override val powerMitigationStatus: String? = null
    override val documentStore = InMemoryReferenceDocumentStore()

    override fun stopPowerMitigation(): Unit = Unit

    override fun currentTimeMillis(): Long = 0L

    override fun emitAutomationLog(message: String): Unit = Unit
}

private class FakeReferenceController(initialSnapshot: ReferenceControllerSnapshot) :
    ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController {
    private val stateFlow = MutableStateFlow(initialSnapshot)

    override val snapshot: StateFlow<ReferenceControllerSnapshot> = stateFlow

    override suspend fun start(): Unit = Unit

    override suspend fun pause(): Unit = Unit

    override suspend fun resume(): Unit = Unit

    override suspend fun stop(): Unit = Unit

    override suspend fun sendPayload(
        peerId: String,
        payloadText: String,
        priority: ch.trancee.meshlink.api.DeliveryPriority,
    ): Unit = Unit

    override suspend fun forgetPeer(peerId: String): Unit = Unit

    override suspend fun close(): Unit = Unit

    fun publish(snapshot: ReferenceControllerSnapshot): Unit {
        stateFlow.value = snapshot
    }
}
