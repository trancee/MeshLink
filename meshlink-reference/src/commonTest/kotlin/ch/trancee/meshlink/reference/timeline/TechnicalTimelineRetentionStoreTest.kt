package ch.trancee.meshlink.reference.timeline

import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class TechnicalTimelineRetentionStoreTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun endCurrentSessionPublishesRetainedSessionState() = runTest {
        // Arrange
        val harness =
            TimelineStoreHarness(initialTimeline = listOf(timelineStoreEntry("live-1", "Live")))
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
    fun retainedSessionViewSurvivesLaterLiveSnapshotUpdates() = runTest {
        // Arrange
        val retainedEntry = timelineStoreEntry(entryId = "retained-1", title = "Retained")
        val liveEntry = timelineStoreEntry(entryId = "live-1", title = "Live")
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
