package ch.trancee.meshlink.reference.timeline

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import ch.trancee.meshlink.reference.model.withoutSensitivePayload
import ch.trancee.meshlink.reference.session.ReferenceSessionKind
import ch.trancee.meshlink.reference.session.referenceSessionKind

internal suspend fun TechnicalTimelineStore.refreshRetainedSessions(lastExportPath: String?): Unit {
    val retainedSessions = historyRepository.loadRetainedSessions()
    updateState { current ->
        current.copy(
            retainedSessions = retainedSessions,
            lastExportPath = lastExportPath ?: current.lastExportPath,
            visibleEntries = current.filters.apply(current.currentSnapshot.timeline),
        )
    }
}

internal suspend fun TechnicalTimelineStore.retainIfEligible(
    endedSnapshot: ReferenceControllerSnapshot
): Unit {
    if (!endedSnapshot.isEligibleForAutomaticRetention(platformServices.readinessBlockers)) {
        return
    }
    historyRepository.retainSnapshot(endedSnapshot.redactedRetainedSnapshot())
}

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
    if (session.authorityMode != ReferenceAuthorityMode.LIVE) {
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
