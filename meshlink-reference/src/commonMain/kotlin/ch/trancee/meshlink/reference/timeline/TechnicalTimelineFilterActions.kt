package ch.trancee.meshlink.reference.timeline

import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity

public fun TechnicalTimelineStore.updateSearch(text: String): Unit {
    applyFilters(uiState.value.filters.copy(searchText = text))
}

public fun TechnicalTimelineStore.updatePeer(peerSuffix: String?): Unit {
    applyFilters(uiState.value.filters.copy(peerSuffix = peerSuffix))
}

public fun TechnicalTimelineStore.updateFamily(family: TimelineFamily?): Unit {
    applyFilters(uiState.value.filters.copy(family = family))
}

public fun TechnicalTimelineStore.updateSeverity(severity: TimelineSeverity?): Unit {
    applyFilters(uiState.value.filters.copy(severity = severity))
}

public fun TechnicalTimelineStore.clearFilters(): Unit {
    applyFilters(TimelineFilters())
}

private fun TechnicalTimelineStore.applyFilters(filters: TimelineFilters): Unit {
    updateState { current ->
        current.copy(
            filters = filters,
            visibleEntries = filters.apply(current.currentSnapshot.timeline),
        )
    }
}
