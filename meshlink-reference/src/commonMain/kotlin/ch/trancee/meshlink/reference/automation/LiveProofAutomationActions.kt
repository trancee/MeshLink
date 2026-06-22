package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.navigation.SessionTransitionService
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineStore
import kotlinx.coroutines.launch

internal interface LiveProofAutomationActions {
    val platformName: String
    val readinessBlockers: List<String>

    fun emitAutomationLog(message: String)

    fun requestMeshStart()

    fun requestMeshPause()

    fun requestMeshResume()

    fun requestSendPayload(peerId: String, payloadText: String, priority: DeliveryPriority)

    fun requestForgetPeer(peerId: String)

    fun requestTopologyDisruption(peerId: String) {
        requestForgetPeer(peerId)
    }

    fun requestEndCurrentSession()

    fun requestExportCurrentSession(policy: ExportPayloadPolicy)
}

internal class TimelineStoreLiveProofAutomationActions(
    private val platformNameValue: String,
    private val readinessBlockersValue: List<String>,
    private val emitAutomationLogAction: (String) -> Unit,
    private val meshLinkController: ReferenceMeshLinkController,
    private val timelineStore: TechnicalTimelineStore,
    private val sessionTransitionService: SessionTransitionService,
) : LiveProofAutomationActions {
    override val platformName: String
        get() = platformNameValue

    override val readinessBlockers: List<String>
        get() = readinessBlockersValue

    override fun emitAutomationLog(message: String) {
        emitAutomationLogAction(message)
    }

    override fun requestMeshStart() {
        timelineStore.scope.launch { meshLinkController.start() }
    }

    override fun requestMeshPause() {
        timelineStore.scope.launch { meshLinkController.pause() }
    }

    override fun requestMeshResume() {
        timelineStore.scope.launch { meshLinkController.resume() }
    }

    override fun requestSendPayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ) {
        timelineStore.scope.launch {
            meshLinkController.sendPayload(
                peerId = peerId,
                payloadText = payloadText,
                priority = priority,
            )
        }
    }

    override fun requestForgetPeer(peerId: String) {
        timelineStore.scope.launch { meshLinkController.forgetPeer(peerId) }
    }

    override fun requestEndCurrentSession() {
        timelineStore.scope.launch { sessionTransitionService.endSupportedSession() }
    }

    override fun requestExportCurrentSession(policy: ExportPayloadPolicy) {
        timelineStore.exportVisibleSession(policy)
    }
}
