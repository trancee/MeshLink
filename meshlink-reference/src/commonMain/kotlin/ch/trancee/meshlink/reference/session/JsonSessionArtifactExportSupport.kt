package ch.trancee.meshlink.reference.session

import ch.trancee.meshlink.reference.model.PeerSnapshot
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.SessionArtifact
import ch.trancee.meshlink.reference.model.TimelineEntry
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
        scenario = buildScenarioBlock(session),
        configuration = session.configurationSnapshot,
        peerSummaries = buildPeerSummaryBlocks(peers),
        timelineEntries = buildTimelineEntryBlocks(timeline, fullPayloadAvailable),
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
