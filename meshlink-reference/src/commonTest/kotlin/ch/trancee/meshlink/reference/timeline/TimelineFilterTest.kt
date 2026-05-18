package ch.trancee.meshlink.reference.timeline

import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineFilterTest {
    @Test
    fun filtersByPeerAndSearchText() {
        val entries =
            listOf(
                TimelineEntry(
                    entryId = "1",
                    sessionId = "session",
                    occurredAtEpochMillis = 1,
                    family = TimelineFamily.MESSAGE,
                    severity = TimelineSeverity.SUCCESS,
                    title = "Delivered",
                    detail = "Delivered to abc123",
                    peerSuffix = "abc123",
                    searchText = "Delivered abc123",
                ),
                TimelineEntry(
                    entryId = "2",
                    sessionId = "session",
                    occurredAtEpochMillis = 2,
                    family = TimelineFamily.DIAGNOSTIC,
                    severity = TimelineSeverity.INFO,
                    title = "Power",
                    detail = "Automatic",
                    peerSuffix = "def456",
                    searchText = "Power def456",
                ),
            )

        val filtered =
            TimelineFilters(searchText = "Delivered", peerSuffix = "abc123").apply(entries)

        assertEquals(listOf("1"), filtered.map { it.entryId })
    }
}
