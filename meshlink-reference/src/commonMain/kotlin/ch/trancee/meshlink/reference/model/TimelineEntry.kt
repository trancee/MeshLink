package ch.trancee.meshlink.reference.model

import kotlinx.serialization.Serializable

/** One chronological record in the technical timeline. */
@Serializable
public data class TimelineEntry(
    public val entryId: String,
    public val sessionId: String,
    public val occurredAtEpochMillis: Long,
    public val family: TimelineFamily,
    public val severity: TimelineSeverity,
    public val title: String,
    public val detail: String,
    public val peerSuffix: String? = null,
    public val searchText: String = listOf(title, detail, peerSuffix.orEmpty()).joinToString(" "),
    public val payloadPreview: String? = null,
    public val fullPayloadIncluded: Boolean = false,
)

@Serializable
public enum class TimelineFamily {
    USER,
    LIFECYCLE,
    PEER,
    DIAGNOSTIC,
    MESSAGE,
    TRANSFER,
    EXPORT,
}

@Serializable
public enum class TimelineSeverity {
    INFO,
    WARNING,
    ERROR,
    SUCCESS,
    DEBUG,
}
