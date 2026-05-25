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
}
