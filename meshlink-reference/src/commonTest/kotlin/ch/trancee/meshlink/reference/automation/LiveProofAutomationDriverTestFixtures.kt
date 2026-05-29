package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy

internal fun driverTimelineEntry(
    sessionId: String,
    entryId: String,
    title: String,
    detail: String,
    family: TimelineFamily,
    peerSuffix: String? = null,
    payloadSizeBytes: Int? = null,
): TimelineEntry {
    return TimelineEntry(
        entryId = entryId,
        sessionId = sessionId,
        occurredAtEpochMillis = 2L,
        family = family,
        severity = TimelineSeverity.INFO,
        title = title,
        detail = detail,
        peerSuffix = peerSuffix,
        payloadSizeBytes = payloadSizeBytes,
    )
}

internal fun retainedDriverSession(sessionId: String, endedAtEpochMillis: Long): ReferenceSession {
    return ReferenceSession(
        sessionId = sessionId,
        scenarioId = "guided-first-exchange",
        authorityMode = ReferenceAuthorityMode.LIVE,
        startedAtEpochMillis = 1L,
        endedAtEpochMillis = endedAtEpochMillis,
    )
}

internal class RecordingLiveProofAutomationActions(
    override val platformName: String = "Android",
    override val readinessBlockers: List<String> = emptyList(),
) : LiveProofAutomationActions {
    val logs: MutableList<String> = mutableListOf()
    var meshStartRequests: Int = 0
    var meshPauseRequests: Int = 0
    var meshResumeRequests: Int = 0
    var endSessionRequests: Int = 0
    val sendRequests: MutableList<SendPayloadRequest> = mutableListOf()
    val forgetPeerRequests: MutableList<String> = mutableListOf()
    val exportRequests: MutableList<ExportPayloadPolicy> = mutableListOf()

    override fun emitAutomationLog(message: String) {
        logs += message
    }

    override fun requestMeshStart() {
        meshStartRequests += 1
    }

    override fun requestMeshPause() {
        meshPauseRequests += 1
    }

    override fun requestMeshResume() {
        meshResumeRequests += 1
    }

    override fun requestSendPayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ) {
        sendRequests +=
            SendPayloadRequest(peerId = peerId, payloadText = payloadText, priority = priority)
    }

    override fun requestForgetPeer(peerId: String) {
        forgetPeerRequests += peerId
    }

    override fun requestEndCurrentSession() {
        endSessionRequests += 1
    }

    override fun requestExportCurrentSession(policy: ExportPayloadPolicy) {
        exportRequests += policy
    }
}

internal data class SendPayloadRequest(
    val peerId: String,
    val payloadText: String,
    val priority: DeliveryPriority,
)
