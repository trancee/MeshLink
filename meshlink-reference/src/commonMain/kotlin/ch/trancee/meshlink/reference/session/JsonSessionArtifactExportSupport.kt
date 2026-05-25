package ch.trancee.meshlink.reference.session

import ch.trancee.meshlink.reference.model.PeerSnapshot
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.SessionArtifact
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.referenceConnectionLabel
import ch.trancee.meshlink.reference.model.referenceOutcomeLabel
import ch.trancee.meshlink.reference.model.referencePeerTrustLabel
import ch.trancee.meshlink.reference.model.referenceScenarioTitle
import kotlinx.serialization.Serializable

internal fun buildExportDocument(
    artifact: SessionArtifact,
    session: ReferenceSession,
    peers: List<PeerSnapshot>,
    timeline: List<TimelineEntry>,
    includeFullPayload: Boolean,
): ExportDocument {
    val fullPayloadAvailable =
        includeFullPayload && timeline.any { entry -> entry.fullPayload != null }
    return ExportDocument(
        artifactVersion = "1",
        artifactId = artifact.artifactId,
        createdAt = formatExportTimestampUtc(artifact.createdAtEpochMillis),
        sourceSessionId = artifact.sourceSessionId,
        scenario =
            ScenarioBlock(
                scenarioId = session.scenarioId,
                title = referenceScenarioTitle(session.scenarioId),
                surface = normalizeSurface(session.configurationSnapshot["surface"]),
                authorityMode = session.authorityMode.toString().lowercase(),
                startedAt = formatExportTimestampUtc(session.startedAtEpochMillis),
                endedAt = session.endedAtEpochMillis?.let(::formatExportTimestampUtc),
                lastOutcomeSummary = referenceOutcomeLabel(session.lastOutcomeSummary),
            ),
        configuration = session.configurationSnapshot,
        peerSummaries =
            peers.map { peer ->
                PeerSummaryBlock(
                    peerSuffix = peer.peerSuffix,
                    trustState = referencePeerTrustLabel(peer.trustState),
                    connectionState = referenceConnectionLabel(peer.connectionState),
                    lastDeliveryOutcome = peer.lastDeliveryOutcome,
                )
            },
        timelineEntries =
            timeline.map { entry ->
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
            },
        payloadPolicy =
            PayloadPolicyBlock(
                defaultMode = "redacted-preview",
                fullPayloadIncluded = fullPayloadAvailable,
                operatorOptInRecorded = includeFullPayload,
            ),
    )
}

@Serializable
internal data class ExportDocument(
    val artifactVersion: String,
    val artifactId: String,
    val createdAt: String,
    val sourceSessionId: String,
    val scenario: ScenarioBlock,
    val configuration: Map<String, String>,
    val peerSummaries: List<PeerSummaryBlock>,
    val timelineEntries: List<TimelineEntryBlock>,
    val payloadPolicy: PayloadPolicyBlock,
)

@Serializable
internal data class ScenarioBlock(
    val scenarioId: String,
    val title: String,
    val surface: String,
    val authorityMode: String,
    val startedAt: String,
    val endedAt: String? = null,
    val lastOutcomeSummary: String? = null,
)

@Serializable
internal data class PeerSummaryBlock(
    val peerSuffix: String,
    val trustState: String,
    val connectionState: String,
    val lastDeliveryOutcome: String? = null,
)

@Serializable
internal data class TimelineEntryBlock(
    val entryId: String,
    val occurredAt: String,
    val family: String,
    val severity: String,
    val title: String,
    val detail: String,
    val peerSuffix: String? = null,
    val payloadMetadata: Map<String, String>? = null,
    val payloadPreview: String? = null,
    val fullPayload: String? = null,
)

@Serializable
internal data class PayloadPolicyBlock(
    val defaultMode: String,
    val fullPayloadIncluded: Boolean,
    val operatorOptInRecorded: Boolean,
)

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
        "lab" -> "lab"
        else -> "main"
    }
}
