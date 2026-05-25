package ch.trancee.meshlink.reference.timeline

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry

internal fun visibleEntriesForUpdatedLiveSnapshot(
    current: TechnicalTimelineUiState,
    snapshot: ReferenceControllerSnapshot,
): List<TimelineEntry> {
    return when {
        current.retainedSnapshot != null -> current.visibleEntries
        current.filters.isEmpty() -> snapshot.timeline
        isSingleEntryAppend(
            previous = current.liveSnapshot.timeline,
            current = snapshot.timeline,
        ) -> appendedVisibleEntries(current = current, snapshot = snapshot)
        else -> current.filters.apply(snapshot.timeline)
    }
}

internal fun mergeRetainedSessions(
    current: List<ReferenceSession>,
    loaded: List<ReferenceSession>,
): List<ReferenceSession> {
    return current +
        loaded.filterNot { loadedSession ->
            current.any { currentSession -> currentSession.sessionId == loadedSession.sessionId }
        }
}

internal fun TechnicalTimelineStore.syncLiveSnapshot(
    liveSnapshot: ReferenceControllerSnapshot
): Unit {
    updateState { current ->
        current.copy(
            liveSnapshot = liveSnapshot,
            retainedSnapshot = null,
            visibleEntries = current.filters.apply(liveSnapshot.timeline),
        )
    }
}

private fun appendedVisibleEntries(
    current: TechnicalTimelineUiState,
    snapshot: ReferenceControllerSnapshot,
): List<TimelineEntry> {
    val appendedEntry = snapshot.timeline.last()
    return if (current.filters.matches(appendedEntry)) {
        current.visibleEntries + appendedEntry
    } else {
        current.visibleEntries
    }
}

private fun isSingleEntryAppend(
    previous: List<TimelineEntry>,
    current: List<TimelineEntry>,
): Boolean {
    val expectedSize = previous.size + 1
    val matchesPreviousTail =
        previous.isEmpty() || current[current.lastIndex - 1].entryId == previous.last().entryId
    return current.size == expectedSize && matchesPreviousTail
}
