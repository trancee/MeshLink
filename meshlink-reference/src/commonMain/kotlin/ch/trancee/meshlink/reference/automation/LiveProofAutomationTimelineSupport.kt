package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily

internal fun hasTimelineEntry(
    snapshot: ReferenceControllerSnapshot,
    title: String,
    peerSuffix: String? = null,
): Boolean {
    return snapshot.timeline.any { entry ->
        entry.title == title && (peerSuffix == null || entry.peerSuffix == peerSuffix)
    }
}

internal fun timelineEntryCount(
    snapshot: ReferenceControllerSnapshot,
    title: String,
    peerSuffix: String? = null,
): Int {
    return snapshot.timeline.count { entry ->
        entry.title == title && (peerSuffix == null || entry.peerSuffix == peerSuffix)
    }
}

internal fun largestInboundPayloadBytes(snapshot: ReferenceControllerSnapshot): Int? {
    return snapshot.timeline
        .filter { entry -> entry.title == "Inbound message" }
        .maxOfOrNull { entry -> entry.payloadSizeBytes ?: 0 }
        ?.takeIf { bytes -> bytes > 0 }
}

internal fun latestAutomationObservation(
    snapshot: ReferenceControllerSnapshot,
    peerSuffix: String? = null,
    families: Set<TimelineFamily> = DEFAULT_AUTOMATION_OBSERVATION_FAMILIES,
): TimelineEntry? {
    return snapshot.timeline.lastOrNull { entry ->
        entry.family in families && (peerSuffix == null || entry.peerSuffix == peerSuffix)
    }
}

private val DEFAULT_AUTOMATION_OBSERVATION_FAMILIES: Set<TimelineFamily> =
    setOf(TimelineFamily.DIAGNOSTIC, TimelineFamily.MESSAGE, TimelineFamily.TRANSFER)
