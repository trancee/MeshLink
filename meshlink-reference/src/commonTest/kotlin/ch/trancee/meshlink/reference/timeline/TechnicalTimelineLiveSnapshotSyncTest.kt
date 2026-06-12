package ch.trancee.meshlink.reference.timeline

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import kotlin.test.Test
import kotlin.test.assertEquals

class TechnicalTimelineLiveSnapshotSyncTest {
    @Test
    fun retainedSnapshotPreservesTheCurrentlyVisibleEntries() {
        // Arrange
        val retainedVisibleEntries = listOf(timelineEntry("retained-1", "Retained match"))
        val current =
            timelineState(
                liveTimeline = listOf(timelineEntry("live-1", "Live match")),
                retainedTimeline = listOf(timelineEntry("retained-1", "Retained match")),
                filters = TimelineFilters(searchText = "match"),
                visibleEntries = retainedVisibleEntries,
            )
        val updatedSnapshot =
            controllerSnapshot(
                timeline =
                    listOf(
                        timelineEntry("retained-1", "Retained match"),
                        timelineEntry("retained-2", "Retained match extra"),
                    )
            )

        // Act
        val actual =
            visibleEntriesForUpdatedLiveSnapshot(current = current, snapshot = updatedSnapshot)

        // Assert
        assertEquals(retainedVisibleEntries, actual)
    }

    @Test
    fun emptyFiltersAdoptTheIncomingTimelineWhenThereIsNoRetainedSnapshot() {
        // Arrange
        val current =
            timelineState(
                liveTimeline = listOf(timelineEntry("live-1", "Live one")),
                filters = TimelineFilters(),
                visibleEntries = listOf(timelineEntry("live-1", "Live one")),
            )
        val updatedSnapshot =
            controllerSnapshot(
                timeline =
                    listOf(timelineEntry("live-1", "Live one"), timelineEntry("live-2", "Live two"))
            )

        // Act
        val actual =
            visibleEntriesForUpdatedLiveSnapshot(current = current, snapshot = updatedSnapshot)

        // Assert
        assertEquals(updatedSnapshot.timeline, actual)
    }

    @Test
    fun singleEntryAppendPreservesVisibilityWhenAppendedEntryMatchesFilters() {
        // Arrange
        val firstEntry = timelineEntry("live-1", "Keep me")
        val appendedEntry = timelineEntry("live-2", "Keep me too")
        val current =
            timelineState(
                liveTimeline = listOf(firstEntry),
                filters = TimelineFilters(searchText = "Keep"),
                visibleEntries = listOf(firstEntry),
            )
        val updatedSnapshot = controllerSnapshot(timeline = listOf(firstEntry, appendedEntry))

        // Act
        val actual =
            visibleEntriesForUpdatedLiveSnapshot(current = current, snapshot = updatedSnapshot)

        // Assert
        assertEquals(listOf(firstEntry, appendedEntry), actual)
    }

    @Test
    fun singleEntryAppendKeepsTheExistingVisibilityWhenAppendedEntryDoesNotMatchFilters() {
        // Arrange
        val firstEntry = timelineEntry("live-1", "Keep me")
        val appendedEntry = timelineEntry("live-2", "Hide me")
        val current =
            timelineState(
                liveTimeline = listOf(firstEntry),
                filters = TimelineFilters(searchText = "Keep"),
                visibleEntries = listOf(firstEntry),
            )
        val updatedSnapshot = controllerSnapshot(timeline = listOf(firstEntry, appendedEntry))

        // Act
        val actual =
            visibleEntriesForUpdatedLiveSnapshot(current = current, snapshot = updatedSnapshot)

        // Assert
        assertEquals(listOf(firstEntry), actual)
    }

    @Test
    fun filterFallbackReappliesTheCurrentFiltersToAReplacedTimeline() {
        // Arrange
        val visibleEntry = timelineEntry("live-1", "Keep this")
        val current =
            timelineState(
                liveTimeline = listOf(visibleEntry),
                filters = TimelineFilters(searchText = "Keep"),
                visibleEntries = listOf(visibleEntry),
            )
        val updatedSnapshot =
            controllerSnapshot(
                timeline =
                    listOf(
                        timelineEntry("new-1", "Hide this"),
                        timelineEntry("new-2", "Keep this too"),
                    )
            )

        // Act
        val actual =
            visibleEntriesForUpdatedLiveSnapshot(current = current, snapshot = updatedSnapshot)

        // Assert
        assertEquals(listOf(updatedSnapshot.timeline[1]), actual)
    }
}

private fun controllerSnapshot(timeline: List<TimelineEntry>): ReferenceControllerSnapshot {
    return ReferenceControllerSnapshot(
        session = referenceSession(sessionId = "session-1", startedAt = 1L),
        peers = emptyList(),
        timeline = timeline,
        activePowerModeLabel = "Automatic",
    )
}

private fun timelineState(
    liveTimeline: List<TimelineEntry>,
    retainedTimeline: List<TimelineEntry> = emptyList(),
    filters: TimelineFilters = TimelineFilters(),
    visibleEntries: List<TimelineEntry> = liveTimeline,
): TechnicalTimelineUiState {
    return TechnicalTimelineUiState(
        liveSnapshot = controllerSnapshot(liveTimeline),
        retainedSnapshot =
            retainedTimeline.takeIf { it.isNotEmpty() }?.let { controllerSnapshot(it) },
        filters = filters,
        visibleEntries = visibleEntries,
    )
}

private fun timelineEntry(entryId: String, title: String): TimelineEntry {
    return TimelineEntry(
        entryId = entryId,
        sessionId = "session-1",
        occurredAtEpochMillis = 1L,
        family = TimelineFamily.MESSAGE,
        severity = TimelineSeverity.INFO,
        title = title,
        detail = "$title detail",
        searchText = title,
    )
}

private fun referenceSession(sessionId: String, startedAt: Long): ReferenceSession {
    return ReferenceSession(
        sessionId = sessionId,
        scenarioId = "guided-first-exchange",
        authorityMode = ReferenceAuthorityMode.LIVE,
        startedAtEpochMillis = startedAt,
        historyStatus = ReferenceHistoryStatus.LIVE,
    )
}
