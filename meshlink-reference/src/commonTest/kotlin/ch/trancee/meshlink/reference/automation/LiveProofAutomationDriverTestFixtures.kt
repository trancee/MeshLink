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
    var endSessionRequests: Int = 0
    val exportRequests: MutableList<ExportPayloadPolicy> = mutableListOf()

    override fun emitAutomationLog(message: String) {
        logs += message
    }

    override fun requestMeshStart() {
        meshStartRequests += 1
    }

    override fun requestMeshPause() = Unit

    override fun requestMeshResume() = Unit

    override fun requestSendPayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ) = Unit

    override fun requestForgetPeer(peerId: String) = Unit

    override fun requestEndCurrentSession() {
        endSessionRequests += 1
    }

    override fun requestExportCurrentSession(policy: ExportPayloadPolicy) {
        exportRequests += policy
    }
}
