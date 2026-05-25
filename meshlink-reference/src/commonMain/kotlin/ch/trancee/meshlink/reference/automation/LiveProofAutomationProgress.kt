package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.TimelineFamily

internal class LiveProofAutomationProgress {
    var announced: Boolean = false
    var peerAnnounced: Boolean = false
    var meshStartRequested: Boolean = false
    var bootstrapRequested: Boolean = false
    var sendRequested: Boolean = false
    var pauseRequested: Boolean = false
    var pauseObserved: Boolean = false
    var pauseResumeTargetPeerId: String? = null
    var pauseResumeTargetPeerSuffix: String? = null
    var resumeRequested: Boolean = false
    var resumeObserved: Boolean = false
    var trustResetRequested: Boolean = false
    var trustResetObserved: Boolean = false
    var recoverySendRequested: Boolean = false
    var retainRequested: Boolean = false
    var exportRequested: Boolean = false
    var fullExportRequested: Boolean = false
    var fullExportPath: String? = null
    var redactedExportPath: String? = null
    var lastObservedExportPath: String? = null
    var completionLogged: Boolean = false
    var lastPeerSnapshotSummary: String? = null
    var lastBootstrapObservationEntryId: String? = null
    var lastSenderObservationEntryId: String? = null
    var lastPassiveObservationEntryId: String? = null
    var lastRelayObservationEntryId: String? = null
    var lastSenderOutcomeSummary: String? = null
}

internal fun latestSenderDeliveryDetail(
    snapshot: ReferenceControllerSnapshot,
    peerSuffix: String?,
): String? {
    return snapshot.timeline
        .lastOrNull { entry ->
            entry.family == TimelineFamily.DIAGNOSTIC &&
                entry.title == "DELIVERY_SUCCEEDED" &&
                (peerSuffix == null || entry.peerSuffix == peerSuffix)
        }
        ?.detail
}

internal enum class SenderPayloadPlan(val label: String, val priority: DeliveryPriority) {
    GUIDED_HELLO(label = "guided-hello", priority = DeliveryPriority.NORMAL),
    RECOVERY_HELLO(label = "trust-reset-recovery", priority = DeliveryPriority.NORMAL),
    LARGE_TRANSFER(label = "large-transfer", priority = DeliveryPriority.HIGH),
}

internal fun SenderPayloadPlan.payload(platformName: String): String {
    return when (this) {
        SenderPayloadPlan.GUIDED_HELLO -> "hello mesh from $platformName"
        SenderPayloadPlan.RECOVERY_HELLO -> "hello again from $platformName after trust reset"
        SenderPayloadPlan.LARGE_TRANSFER -> buildLargeTransferPayload(platformName)
    }
}

internal fun largeTransferPayloadBytes(platformName: String): Int {
    return buildLargeTransferPayload(platformName).encodeToByteArray().size
}

internal const val REQUIRED_PAUSE_RESUME_DELIVERY_COUNT: Int = 2
internal const val REQUIRED_PAUSE_RESUME_INBOUND_COUNT: Int = 2
internal const val REQUIRED_RECOVERY_DELIVERY_COUNT: Int = 2
internal const val REQUIRED_RECOVERY_INBOUND_COUNT: Int = 2
