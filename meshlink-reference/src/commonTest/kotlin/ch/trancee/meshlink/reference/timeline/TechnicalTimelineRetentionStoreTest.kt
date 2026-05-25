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
                .createTransitionServiceHarness(scope = this)
        advanceUntilIdle()

        try {
            // Act
            harness.transitionService.endSupportedSession()
            advanceUntilIdle()

            // Assert
            assertEquals(true, harness.timelineStore.uiState.value.isCurrentSessionEnded)
            assertEquals(1, harness.timelineStore.uiState.value.retainedSessions.size)
            assertEquals(
                ReferenceHistoryStatus.RETAINED,
                harness.timelineStore.uiState.value.retainedSessions.single().historyStatus,
            )
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun retainedSessionViewSurvivesLaterLiveSnapshotUpdates() = runTest {
        // Arrange
        val retainedEntry = timelineStoreEntry(entryId = "retained-1", title = "Retained")
        val liveEntry = timelineStoreEntry(entryId = "live-1", title = "Live")
        val timelineHarness = TimelineStoreHarness(initialTimeline = listOf(retainedEntry))
        val harness = timelineHarness.createTransitionServiceHarness(scope = this)
        advanceUntilIdle()
        harness.transitionService.endSupportedSession()
        advanceUntilIdle()
        harness.timelineStore.openRetainedSession(sessionId = "timeline-session")
        advanceUntilIdle()

        try {
            // Act
            timelineHarness.emitLiveSnapshot(timeline = listOf(liveEntry))
            advanceUntilIdle()

            // Assert
            assertEquals(true, harness.timelineStore.uiState.value.viewingRetained)
            assertEquals(
                listOf("retained-1"),
                harness.timelineStore.uiState.value.visibleEntries.map { it.entryId },
            )
        } finally {
            coroutineContext.cancelChildren()
        }
    }
}
