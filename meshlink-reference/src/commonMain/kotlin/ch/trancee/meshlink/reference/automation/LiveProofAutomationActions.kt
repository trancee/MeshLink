package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineStore
import ch.trancee.meshlink.reference.timeline.endCurrentSession
import ch.trancee.meshlink.reference.timeline.exportCurrentSession
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
