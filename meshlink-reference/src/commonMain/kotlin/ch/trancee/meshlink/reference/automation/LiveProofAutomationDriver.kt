package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.timeline.TechnicalTimelineUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

internal class LiveProofAutomationDriver(
    private val automationConfig: ReferenceAutomationConfig?,
    private val timelineUiStateFlow: StateFlow<TechnicalTimelineUiState>,
    private val actions: LiveProofAutomationActions,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private var collectionJob: Job? = null
    private val progress: LiveProofAutomationProgress = LiveProofAutomationProgress()

    fun start() {
        val config = automationConfig ?: return
        if (config.mode != ReferenceAutomationMode.LIVE_PROOF || collectionJob != null) {
            return
        }

        handle(timelineUiStateFlow.value)
        collectionJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                timelineUiStateFlow.drop(1).collect { timelineUiState -> handle(timelineUiState) }
            }
    }

    internal fun handle(timelineUiState: TechnicalTimelineUiState) {
        val config = automationConfig ?: return
        if (config.mode != ReferenceAutomationMode.LIVE_PROOF) {
            return
        }

        LiveProofAutomationCoordinator(
                automationConfig = config,
                actions = actions,
                progress = progress,
            )
            .run(snapshot = timelineUiState.liveSnapshot, timelineUiState = timelineUiState)
    }

    fun stop() {
        collectionJob?.cancel()
        collectionJob = null
    }

    fun close() {
        stop()
        scope.cancel()
    }
}
