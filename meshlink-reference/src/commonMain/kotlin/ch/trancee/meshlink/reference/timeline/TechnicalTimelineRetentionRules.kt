package ch.trancee.meshlink.reference.timeline

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.REFERENCE_AUTHORITY_MODE_LIVE
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import ch.trancee.meshlink.reference.model.withoutSensitivePayload
import ch.trancee.meshlink.reference.session.ReferenceSessionKind
import ch.trancee.meshlink.reference.session.referenceSessionKind

internal fun endedBoundarySnapshot(
    snapshot: ReferenceControllerSnapshot,
    endedAtEpochMillis: Long,
): ReferenceControllerSnapshot {
    return snapshot.copy(session = snapshot.session.copy(endedAtEpochMillis = endedAtEpochMillis))
}

internal fun ReferenceControllerSnapshot.redactedRetainedSnapshot(): ReferenceControllerSnapshot {
    return copy(
        session = session.copy(historyStatus = ReferenceHistoryStatus.RETAINED),
        timeline = timeline.map { entry -> entry.withoutSensitivePayload() },
    )
}

internal fun ReferenceControllerSnapshot.isEligibleForAutomaticRetention(
    readinessBlockers: List<String>
): Boolean {
    if (session.authorityMode != REFERENCE_AUTHORITY_MODE_LIVE) {
        return false
    }
    if (referenceSessionKind() == ReferenceSessionKind.LAB) {
        return false
    }
    if (readinessBlockers.isNotEmpty()) {
        return true
    }
    return timeline.any { entry -> entry.isReviewableEvidenceEntry() }
}

private fun TimelineEntry.isReviewableEvidenceEntry(): Boolean {
    if (title == "Reference session created" || title == "Automation session created") {
        return false
    }
    return family != TimelineFamily.USER || severity != TimelineSeverity.INFO
}
