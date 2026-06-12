package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.timeline.TechnicalTimelineUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class LiveProofAutomationDriverLifecycleTest {
    @Test
    fun stopCancelsCollectionAndSuppressesFutureTimelineUpdates() = runTest {
        // Arrange
        val initialState =
            TechnicalTimelineUiState(
                liveSnapshot =
                    automationTestSnapshot(meshStateLabel = "Uninitialized", timeline = emptyList())
            )
        val updatedState =
            TechnicalTimelineUiState(
                liveSnapshot =
                    automationTestSnapshot(
                        meshStateLabel = "Running",
                        peers =
                            listOf(
                                automationTestPeer(peerId = "peer-stop-1", peerSuffix = "abc123")
                            ),
                        timeline = emptyList(),
                    )
            )

        val stoppedActions = RecordingLiveProofAutomationActions()
        val stoppedTimelineUiStateFlow = MutableStateFlow(initialState)
        val stoppedDriver =
            newLifecycleDriver(
                timelineUiStateFlow = stoppedTimelineUiStateFlow,
                actions = stoppedActions,
                storageSubdirectory = "driver-stop",
                testSchedulerDispatcher = UnconfinedTestDispatcher(testScheduler),
            )

        val activeActions = RecordingLiveProofAutomationActions()
        val activeTimelineUiStateFlow = MutableStateFlow(initialState)
        val activeDriver =
            newLifecycleDriver(
                timelineUiStateFlow = activeTimelineUiStateFlow,
                actions = activeActions,
                storageSubdirectory = "driver-stop-control",
                testSchedulerDispatcher = UnconfinedTestDispatcher(testScheduler),
            )

        // Act
        stoppedDriver.start()
        activeDriver.start()
        val stoppedBaseline = stoppedActions.counts()
        val activeBaseline = activeActions.counts()
        stoppedDriver.stop()
        stoppedTimelineUiStateFlow.value = updatedState
        activeTimelineUiStateFlow.value = updatedState
        runCurrent()

        // Assert
        assertEquals(stoppedBaseline, stoppedActions.counts())
        assertTrue(activeActions.counts().hasChangedFrom(activeBaseline))
    }

    @Test
    fun closeCancelsScopeAndSuppressesFutureTimelineUpdates() = runTest {
        // Arrange
        val initialState =
            TechnicalTimelineUiState(
                liveSnapshot =
                    automationTestSnapshot(meshStateLabel = "Uninitialized", timeline = emptyList())
            )
        val updatedState =
            TechnicalTimelineUiState(
                liveSnapshot =
                    automationTestSnapshot(
                        meshStateLabel = "Running",
                        peers =
                            listOf(
                                automationTestPeer(peerId = "peer-close-1", peerSuffix = "def456")
                            ),
                        timeline = emptyList(),
                    )
            )

        val closedActions = RecordingLiveProofAutomationActions()
        val closedTimelineUiStateFlow = MutableStateFlow(initialState)
        val closedDriver =
            newLifecycleDriver(
                timelineUiStateFlow = closedTimelineUiStateFlow,
                actions = closedActions,
                storageSubdirectory = "driver-close",
                testSchedulerDispatcher = UnconfinedTestDispatcher(testScheduler),
            )

        val activeActions = RecordingLiveProofAutomationActions()
        val activeTimelineUiStateFlow = MutableStateFlow(initialState)
        val activeDriver =
            newLifecycleDriver(
                timelineUiStateFlow = activeTimelineUiStateFlow,
                actions = activeActions,
                storageSubdirectory = "driver-close-control",
                testSchedulerDispatcher = UnconfinedTestDispatcher(testScheduler),
            )

        // Act
        closedDriver.start()
        activeDriver.start()
        val closedBaseline = closedActions.counts()
        val activeBaseline = activeActions.counts()
        closedDriver.close()
        closedTimelineUiStateFlow.value = updatedState
        activeTimelineUiStateFlow.value = updatedState
        runCurrent()

        // Assert
        assertEquals(closedBaseline, closedActions.counts())
        assertTrue(activeActions.counts().hasChangedFrom(activeBaseline))
    }

    private fun newLifecycleDriver(
        timelineUiStateFlow: MutableStateFlow<TechnicalTimelineUiState>,
        actions: RecordingLiveProofAutomationActions,
        storageSubdirectory: String,
        testSchedulerDispatcher: CoroutineDispatcher,
    ): LiveProofAutomationDriver {
        return LiveProofAutomationDriver(
            automationConfig =
                ReferenceAutomationConfig(
                    mode = ReferenceAutomationMode.LIVE_PROOF,
                    role = ReferenceAutomationRole.SENDER,
                    appId = "demo.meshlink.reference.lifecycle",
                    storageSubdirectory = storageSubdirectory,
                ),
            timelineUiStateFlow = timelineUiStateFlow,
            actions = actions,
            scope = CoroutineScope(SupervisorJob() + testSchedulerDispatcher),
        )
    }

    private data class ActionCounts(
        val logs: Int,
        val meshStartRequests: Int,
        val sendRequests: Int,
    ) {
        fun hasChangedFrom(other: ActionCounts): Boolean {
            return logs != other.logs ||
                meshStartRequests != other.meshStartRequests ||
                sendRequests != other.sendRequests
        }
    }

    private fun RecordingLiveProofAutomationActions.counts(): ActionCounts {
        return ActionCounts(
            logs = logs.size,
            meshStartRequests = meshStartRequests,
            sendRequests = sendRequests.size,
        )
    }
}
