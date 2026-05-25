package ch.trancee.meshlink.reference.timeline

import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.navigation.ReferenceSurfaceId
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
        val harness = TimelineStoreHarness().createBoundaryCoordinatorHarness(scope = this)
        advanceUntilIdle()

        try {
            // Act
            harness.boundaryCoordinator.transitionSupportedSession(
                targetSurface = ReferenceSurfaceId.SOLO_EXPLORATION
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
        val harness = TimelineStoreHarness().createBoundaryCoordinatorHarness(scope = this)
        advanceUntilIdle()
        harness.boundaryCoordinator.transitionSupportedSession(
            targetSurface = ReferenceSurfaceId.SOLO_EXPLORATION
        )
        advanceUntilIdle()

        try {
            // Act
            harness.boundaryCoordinator.transitionAlternativeSession(
                targetSurface = ReferenceSurfaceId.MAIN_GUIDED,
                continuation =
                    ch.trancee.meshlink.reference.navigation.BoundaryContinuation
                        .CONTINUE_WITHOUT_EXPORT,
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
