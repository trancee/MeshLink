package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.timeline.TechnicalTimelineUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

// Keep sender retries dense enough to fit inside the direct-proof capture budget on slower pairs.
private const val SENDER_PEER_WAIT_RETRY_DELAY_MILLIS: Long = 2_000
private const val SENDER_PEER_WAIT_MAX_RETRIES: Int = 15

internal class LiveProofAutomationDriver(
    private val automationConfig: ReferenceAutomationConfigView?,
    private val timelineUiStateFlow: StateFlow<TechnicalTimelineUiState>,
    private val actions: LiveProofAutomationActions,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private var collectionJob: Job? = null
    private val progress: LiveProofAutomationProgress = LiveProofAutomationProgress()

    fun start() {
        val config = automationConfig ?: return
        if (config.mode != AUTOMATION_MODE_LIVE_PROOF || collectionJob != null) {
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
        if (config.mode != AUTOMATION_MODE_LIVE_PROOF) {
            return
        }

        actions.emitAutomationLog(
            "REFERENCE_AUTOMATION driver.handle role=${config.role} mode=${config.mode} " +
                "snapshotPeers=${timelineUiState.liveSnapshot.peers.size} " +
                "timelineEntries=${timelineUiState.liveSnapshot.timeline.size}"
        )
        if (config.role == AUTOMATION_ROLE_SENDER && !progress.meshStartRequested) {
            actions.emitAutomationLog(
                "REFERENCE_AUTOMATION mesh.start.requested role=${config.role} meshState=${timelineUiState.liveSnapshot.session.meshStateLabel} readinessBlockers=${actions.readinessBlockers.joinToString(separator = "|")}"
            )
            actions.requestMeshStart()
            progress.meshStartRequested = true
        }
        LiveProofAutomationCoordinator(
                automationConfig = config,
                actions = actions,
                progress = progress,
            )
            .run(snapshot = timelineUiState.liveSnapshot, timelineUiState = timelineUiState)
        scheduleSenderPeerWaitRetryIfNeeded(timelineUiState = timelineUiState)
        scheduleSenderRouteWaitRetryIfNeeded(timelineUiState = timelineUiState)
        scheduleSenderUnreachableRetryIfNeeded(timelineUiState = timelineUiState)
    }

    private fun scheduleSenderPeerWaitRetryIfNeeded(
        timelineUiState: TechnicalTimelineUiState
    ): Unit {
        val config = automationConfig ?: return
        if (config.mode != AUTOMATION_MODE_LIVE_PROOF || config.role != AUTOMATION_ROLE_SENDER) {
            return
        }
        if (timelineUiState.liveSnapshot.peers.isNotEmpty()) {
            return
        }
        scheduleSenderRouteRetry("no-peers")
    }

    private fun scheduleSenderRouteWaitRetryIfNeeded(
        timelineUiState: TechnicalTimelineUiState
    ): Unit {
        val config = automationConfig ?: return
        if (config.mode != AUTOMATION_MODE_LIVE_PROOF || config.role != AUTOMATION_ROLE_SENDER) {
            return
        }
        val meshState = timelineUiState.liveSnapshot.session.meshStateLabel
        if (!meshState.contains("Running")) {
            return
        }
        if (timelineUiState.liveSnapshot.peers.isEmpty()) {
            return
        }
        if (
            timelineUiState.liveSnapshot.peers.any { peer ->
                hasAvailableRouteForPeer(timelineUiState.liveSnapshot, peer.peerId)
            }
        ) {
            return
        }
        scheduleSenderRouteRetry("route-unavailable")
    }

    private fun scheduleSenderUnreachableRetryIfNeeded(
        timelineUiState: TechnicalTimelineUiState
    ): Unit {
        val config = automationConfig ?: return
        if (config.mode != AUTOMATION_MODE_LIVE_PROOF || config.role != AUTOMATION_ROLE_SENDER) {
            return
        }
        if (progress.completionLogged || !progress.sendRequested) {
            return
        }
        val outcome = timelineUiState.liveSnapshot.session.lastOutcomeSummary ?: return
        if (outcome != "SendResult.NotSent(UNREACHABLE)") {
            return
        }
        scheduleSenderRouteRetry("unreachable")
    }

    private fun scheduleSenderRouteRetry(reason: String): Unit {
        if (
            progress.senderPeerWaitRetryScheduled ||
                progress.senderPeerWaitRetryCount >= SENDER_PEER_WAIT_MAX_RETRIES
        ) {
            return
        }
        progress.senderPeerWaitRetryScheduled = true
        progress.senderPeerWaitRetryCount += 1
        val retryNumber = progress.senderPeerWaitRetryCount
        actions.emitAutomationLog(
            "REFERENCE_AUTOMATION sender.wait.retry role=sender reason=$reason attempt=$retryNumber delayMs=$SENDER_PEER_WAIT_RETRY_DELAY_MILLIS"
        )
        scope.launch {
            delay(SENDER_PEER_WAIT_RETRY_DELAY_MILLIS)
            progress.senderPeerWaitRetryScheduled = false
            handle(timelineUiStateFlow.value)
        }
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
