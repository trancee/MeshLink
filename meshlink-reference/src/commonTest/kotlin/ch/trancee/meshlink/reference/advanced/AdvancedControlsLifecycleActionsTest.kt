package ch.trancee.meshlink.reference.advanced

import ch.trancee.meshlink.api.MeshLinkState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class AdvancedControlsLifecycleActionsTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun lifecycleActionsTrackSnapshotState() = runTest {
        // Arrange
        val controller = TestReferenceMeshLinkController()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val viewModel =
            AdvancedControlsViewModel(
                platformServices = advancedPlatformServices(controller = controller),
                scope = scope,
            )
        val expected = LifecycleActionState.from(MeshLinkState.Paused.toString())
        advanceUntilIdle()

        try {
            // Act
            controller.updateMeshState(MeshLinkState.Paused.toString())
            advanceUntilIdle()

            // Assert
            assertEquals(expected, viewModel.lifecycleActions.value)
        } finally {
            scope.cancel()
        }
    }
}
