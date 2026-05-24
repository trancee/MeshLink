package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineStore
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineUiState
import ch.trancee.meshlink.reference.timeline.endCurrentSession
import ch.trancee.meshlink.reference.timeline.exportCurrentSession
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

internal interface LiveProofAutomationActions {
    val platformName: String
    val readinessBlockers: List<String>

    fun emitAutomationLog(message: String)

    fun requestMeshStart()

    fun requestMeshPause()

    fun requestMeshResume()

    fun requestSendSamplePayload(peerId: String, payloadText: String, priority: DeliveryPriority)

    fun requestForgetPeer(peerId: String)

    fun requestEndCurrentSession()

    fun requestExportCurrentSession(policy: ExportPayloadPolicy)
}

internal class TimelineStoreLiveProofAutomationActions(
    private val platformServices: PlatformServices,
    private val timelineStore: TechnicalTimelineStore,
) : LiveProofAutomationActions {
    override val platformName: String
        get() = platformServices.platformName

    override val readinessBlockers: List<String>
        get() = platformServices.readinessBlockers

    override fun emitAutomationLog(message: String) {
        platformServices.emitAutomationLog(message)
    }

    override fun requestMeshStart() {
        timelineStore.scope.launch { platformServices.meshLinkController.start() }
    }

    override fun requestMeshPause() {
        timelineStore.scope.launch { platformServices.meshLinkController.pause() }
    }

    override fun requestMeshResume() {
        timelineStore.scope.launch { platformServices.meshLinkController.resume() }
    }

    override fun requestSendSamplePayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ) {
        timelineStore.scope.launch {
            platformServices.meshLinkController.sendSamplePayload(
                peerId = peerId,
                payloadText = payloadText,
                priority = priority,
            )
        }
    }

    override fun requestForgetPeer(peerId: String) {
        timelineStore.scope.launch { platformServices.meshLinkController.forgetPeer(peerId) }
    }

    override fun requestEndCurrentSession() {
        timelineStore.endCurrentSession()
    }

    override fun requestExportCurrentSession(policy: ExportPayloadPolicy) {
        timelineStore.exportCurrentSession(policy)
    }
}

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
