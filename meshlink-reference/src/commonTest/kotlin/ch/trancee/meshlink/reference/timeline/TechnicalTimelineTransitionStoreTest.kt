package ch.trancee.meshlink.reference.timeline

import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.navigation.BoundaryContinuation
import ch.trancee.meshlink.reference.navigation.ReferenceSurface
import ch.trancee.meshlink.reference.navigation.SessionBoundaryRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class TechnicalTimelineTransitionStoreTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun transitionToSoloSessionStartsSoloSnapshotWithoutRetention() = runTest {
        // Arrange
        val harness = TimelineStoreHarness().createSessionTransitionHarness(scope = this)
        advanceUntilIdle()

        try {
            // Act
            harness.sessionTransitionService.completeBoundary(
                request =
                    SessionBoundaryRequest.LeaveSupportedSession(ReferenceSurface.SOLO_EXPLORATION),
                continuation = BoundaryContinuation.CONTINUE_WITHOUT_EXPORT,
                applySurfaceSelection = {},
            )
            advanceUntilIdle()

            // Assert
            assertEquals(
                ReferenceAuthorityMode.SOLO,
                harness.timelineStore.uiState.value.liveSnapshot.session.authorityMode,
            )
            assertEquals(0, harness.timelineStore.uiState.value.retainedSessions.size)
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun transitionAlternativeSessionCanReturnToSupportedLiveSession() = runTest {
        // Arrange
        val harness = TimelineStoreHarness().createSessionTransitionHarness(scope = this)
        advanceUntilIdle()
        harness.sessionTransitionService.completeBoundary(
            request =
                SessionBoundaryRequest.LeaveSupportedSession(ReferenceSurface.SOLO_EXPLORATION),
            continuation = BoundaryContinuation.CONTINUE_WITHOUT_EXPORT,
            applySurfaceSelection = {},
        )
        advanceUntilIdle()

        try {
            // Act
            harness.sessionTransitionService.completeBoundary(
                request =
                    SessionBoundaryRequest.LeaveAlternativeSession(ReferenceSurface.MAIN_GUIDED),
                continuation = BoundaryContinuation.CONTINUE_WITHOUT_EXPORT,
                applySurfaceSelection = {},
            )
            advanceUntilIdle()

            // Assert
            assertEquals(
                ReferenceAuthorityMode.LIVE,
                harness.timelineStore.uiState.value.liveSnapshot.session.authorityMode,
            )
            assertEquals(
                null,
                harness.timelineStore.uiState.value.liveSnapshot.session.endedAtEpochMillis,
            )
        } finally {
            coroutineContext.cancelChildren()
        }
    }
}
