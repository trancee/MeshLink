package ch.trancee.meshlink.reference.session

import ch.trancee.meshlink.reference.model.PeerSnapshot
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.referenceConnectionLabel
import ch.trancee.meshlink.reference.model.referenceOutcomeLabel
import ch.trancee.meshlink.reference.model.referencePeerTrustLabel
import ch.trancee.meshlink.reference.model.referenceScenarioTitle

internal fun buildScenarioBlock(session: ReferenceSession): ScenarioBlock {
    return ScenarioBlock(
        scenarioId = session.scenarioId,
        title = referenceScenarioTitle(session.scenarioId),
        surface = normalizeSurface(session.configurationSnapshot["surface"]),
        authorityMode = session.authorityMode.lowercase(),
        startedAt = formatExportTimestampUtc(session.startedAtEpochMillis),
        endedAt = session.endedAtEpochMillis?.let(::formatExportTimestampUtc),
        lastOutcomeSummary = referenceOutcomeLabel(session.lastOutcomeSummary),
    )
}

internal fun buildPeerSummaryBlocks(peers: List<PeerSnapshot>): List<PeerSummaryBlock> {
    return peers.map { peer ->
        PeerSummaryBlock(
            peerSuffix = peer.peerSuffix,
            trustState = referencePeerTrustLabel(peer.trustState),
            connectionState = referenceConnectionLabel(peer.connectionState),
            lastDeliveryOutcome = peer.lastDeliveryOutcome,
        )
    }
}

internal fun buildTimelineEntryBlocks(
    timeline: List<TimelineEntry>,
    fullPayloadAvailable: Boolean,
): List<TimelineEntryBlock> {
    return timeline.map { entry ->
        TimelineEntryBlock(
            entryId = entry.entryId,
            occurredAt = formatExportTimestampUtc(entry.occurredAtEpochMillis),
            family = entry.family.name.lowercase(),
            severity = entry.severity.name.lowercase(),
            title = entry.title,
            detail = entry.detail,
            peerSuffix = entry.peerSuffix,
            payloadMetadata = payloadMetadata(entry),
            payloadPreview = entry.payloadPreview,
            fullPayload = if (fullPayloadAvailable) entry.fullPayload else null,
        )
    }
}

internal fun payloadMetadata(entry: TimelineEntry): Map<String, String>? {
    val metadata = linkedMapOf<String, String>()
    entry.payloadSizeBytes?.let { sizeBytes -> metadata["sizeBytes"] = sizeBytes.toString() }
    if (
        entry.payloadPreview != null || entry.fullPayload != null || entry.payloadSizeBytes != null
    ) {
        metadata["contentType"] = "text/plain"
    }
    return metadata.ifEmpty { null }
}

internal fun normalizeSurface(surface: String?): String {
    return when (surface) {
        "advanced-controls" -> "advanced"
        "solo-exploration" -> "solo"
        "lab" -> "lab"
        else -> "main"
    }
}
