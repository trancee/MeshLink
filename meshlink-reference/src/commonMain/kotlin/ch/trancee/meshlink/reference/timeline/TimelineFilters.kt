package ch.trancee.meshlink.reference.timeline

import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity

public data class TimelineFilters(
    public val searchText: String = "",
    public val peerSuffix: String? = null,
    public val family: TimelineFamily? = null,
    public val severity: TimelineSeverity? = null,
) {
    public fun isEmpty(): Boolean {
        return searchText.isBlank() && peerSuffix == null && family == null && severity == null
    }

    public fun matches(entry: TimelineEntry): Boolean {
        val matchesSearch =
            searchText.isBlank() || entry.searchText.contains(searchText, ignoreCase = true)
        val matchesPeer = peerSuffix == null || entry.peerSuffix == peerSuffix
        val matchesFamily = family == null || entry.family == family
        val matchesSeverity = severity == null || entry.severity == severity
        return matchesSearch && matchesPeer && matchesFamily && matchesSeverity
    }

    public fun apply(entries: List<TimelineEntry>): List<TimelineEntry> {
        return if (isEmpty()) {
            entries
        } else {
            entries.filter(::matches)
        }
    }
}
