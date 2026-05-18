package ch.trancee.meshlink.reference.model

import kotlinx.serialization.Serializable

/** Structured session export or retained artifact metadata. */
@Serializable
public data class SessionArtifact(
    public val artifactId: String,
    public val sourceSessionId: String,
    public val createdAtEpochMillis: Long,
    public val payloadPolicy: ArtifactPayloadPolicy,
    public val includesFullPayload: Boolean,
    public val scenarioSummary: Map<String, String>,
    public val peerSummaries: List<Map<String, String>>,
    public val timelineEntries: List<TimelineEntry>,
    public val storagePath: String,
)

@Serializable
public enum class ArtifactPayloadPolicy {
    METADATA_ONLY,
    REDACTED_PREVIEW,
    FULL_OPT_IN,
}
