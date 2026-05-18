package ch.trancee.meshlink.reference.session

import ch.trancee.meshlink.reference.model.ArtifactPayloadPolicy
import ch.trancee.meshlink.reference.model.PeerSnapshot
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.SessionArtifact
import ch.trancee.meshlink.reference.model.TimelineEntry
import kotlinx.serialization.Serializable

/**
 * JSON serializer for redacted and explicit full-payload session exports.
 */
public class JsonSessionArtifactSerializer(
    private val documentStore: ReferenceDocumentStore,
) : SessionArtifactSerializer {
    override suspend fun serializeRedacted(
        session: ReferenceSession,
        peers: List<PeerSnapshot>,
        timeline: List<TimelineEntry>,
    ): String {
        return ReferenceJson.codec.encodeToString(
            ExportDocument.serializer(),
            exportDocument(
                session = session,
                peers = peers,
                timeline = timeline,
                includeFullPayload = false,
            ),
        )
    }

    override suspend fun serializeWithFullPayload(
        session: ReferenceSession,
        peers: List<PeerSnapshot>,
        timeline: List<TimelineEntry>,
    ): String {
        return ReferenceJson.codec.encodeToString(
            ExportDocument.serializer(),
            exportDocument(
                session = session,
                peers = peers,
                timeline = timeline,
                includeFullPayload = true,
            ),
        )
    }

    override suspend fun writeArtifact(
        artifact: SessionArtifact,
        serialized: String,
    ): String {
        documentStore.writeText(artifact.storagePath, serialized)
        return artifact.storagePath
    }

    private fun exportDocument(
        session: ReferenceSession,
        peers: List<PeerSnapshot>,
        timeline: List<TimelineEntry>,
        includeFullPayload: Boolean,
    ): ExportDocument {
        return ExportDocument(
            artifactVersion = "1",
            artifactId = "artifact-${session.sessionId}",
            createdAt = session.startedAtEpochMillis.toString(),
            sourceSessionId = session.sessionId,
            scenario =
                ScenarioBlock(
                    scenarioId = session.scenarioId,
                    title = session.scenarioId,
                    surface = session.configurationSnapshot["surface"] ?: "main",
                    authorityMode = session.authorityMode.toString().lowercase(),
                    startedAt = session.startedAtEpochMillis.toString(),
                    endedAt = session.endedAtEpochMillis?.toString(),
                    lastOutcomeSummary = session.lastOutcomeSummary,
                ),
            configuration = session.configurationSnapshot,
            peerSummaries =
                peers.map { peer ->
                    PeerSummaryBlock(
                        peerSuffix = peer.peerSuffix,
                        trustState = peer.trustState.name,
                        connectionState = peer.connectionState.name,
                        lastDeliveryOutcome = peer.lastDeliveryOutcome,
                    )
                },
            timelineEntries =
                timeline.map { entry ->
                    TimelineEntryBlock(
                        entryId = entry.entryId,
                        occurredAt = entry.occurredAtEpochMillis.toString(),
                        family = entry.family.name.lowercase(),
                        severity = entry.severity.name.lowercase(),
                        title = entry.title,
                        detail = entry.detail,
                        peerSuffix = entry.peerSuffix,
                        payloadMetadata = mapOf("previewLength" to (entry.payloadPreview?.length?.toString() ?: "0")),
                        payloadPreview = entry.payloadPreview,
                        fullPayload = if (includeFullPayload) entry.payloadPreview else null,
                    )
                },
            payloadPolicy =
                PayloadPolicyBlock(
                    defaultMode = "redacted-preview",
                    fullPayloadIncluded = includeFullPayload,
                    operatorOptInRecorded = includeFullPayload,
                ),
        )
    }

    @Serializable
    private data class ExportDocument(
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
    private data class ScenarioBlock(
        val scenarioId: String,
        val title: String,
        val surface: String,
        val authorityMode: String,
        val startedAt: String,
        val endedAt: String? = null,
        val lastOutcomeSummary: String? = null,
    )

    @Serializable
    private data class PeerSummaryBlock(
        val peerSuffix: String,
        val trustState: String,
        val connectionState: String,
        val lastDeliveryOutcome: String? = null,
    )

    @Serializable
    private data class TimelineEntryBlock(
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
    private data class PayloadPolicyBlock(
        val defaultMode: String,
        val fullPayloadIncluded: Boolean,
        val operatorOptInRecorded: Boolean,
    )
}
