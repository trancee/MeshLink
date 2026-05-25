package ch.trancee.meshlink.reference.session

import ch.trancee.meshlink.reference.model.ArtifactPayloadPolicy
import ch.trancee.meshlink.reference.model.PeerConnectionSnapshotState
import ch.trancee.meshlink.reference.model.PeerSnapshot
import ch.trancee.meshlink.reference.model.PeerTrustState
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.SessionArtifact
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal fun sessionArtifactSerializer(): JsonSessionArtifactSerializer {
    return JsonSessionArtifactSerializer(documentStore = InMemoryReferenceDocumentStore())
}

internal fun sessionArtifactSession(
    sessionId: String,
    scenarioId: String = "guided-first-exchange",
    authorityMode: ReferenceAuthorityMode = ReferenceAuthorityMode.LIVE,
    surfaceOfOrigin: String = "main-guided",
    startedAtEpochMillis: Long = 1_000L,
    endedAtEpochMillis: Long? = null,
    lastOutcomeSummary: String? = null,
    historyStatus: ReferenceHistoryStatus = ReferenceHistoryStatus.LIVE,
): ReferenceSession {
    return ReferenceSession(
        sessionId = sessionId,
        scenarioId = scenarioId,
        authorityMode = authorityMode,
        startedAtEpochMillis = startedAtEpochMillis,
        endedAtEpochMillis = endedAtEpochMillis,
        configurationSnapshot = mapOf("surface" to surfaceOfOrigin),
        lastOutcomeSummary = lastOutcomeSummary,
        historyStatus = historyStatus,
    )
}

internal fun sessionArtifactPeerSummary(
    peerId: String = "peer-1",
    peerSuffix: String = "abc123",
    trustState: PeerTrustState = PeerTrustState.TRUSTED,
    connectionState: PeerConnectionSnapshotState = PeerConnectionSnapshotState.CONNECTED,
): PeerSnapshot {
    return PeerSnapshot(
        peerId = peerId,
        peerSuffix = peerSuffix,
        trustState = trustState,
        connectionState = connectionState,
    )
}

internal fun sessionArtifactTimelineEntry(
    entryId: String,
    sessionId: String,
    title: String,
    detail: String,
    occurredAtEpochMillis: Long = 1_500L,
    family: TimelineFamily = TimelineFamily.MESSAGE,
    severity: TimelineSeverity = TimelineSeverity.SUCCESS,
    payloadPreview: String? = null,
    payloadSizeBytes: Int? = null,
    fullPayload: String? = null,
    fullPayloadIncluded: Boolean = false,
): TimelineEntry {
    return TimelineEntry(
        entryId = entryId,
        sessionId = sessionId,
        occurredAtEpochMillis = occurredAtEpochMillis,
        family = family,
        severity = severity,
        title = title,
        detail = detail,
        payloadPreview = payloadPreview,
        payloadSizeBytes = payloadSizeBytes,
        fullPayload = fullPayload,
        fullPayloadIncluded = fullPayloadIncluded,
    )
}

internal fun sessionArtifact(
    artifactId: String,
    sourceSessionId: String,
    createdAtEpochMillis: Long,
    storagePath: String,
    payloadPolicy: ArtifactPayloadPolicy = ArtifactPayloadPolicy.REDACTED_PREVIEW,
    includesFullPayload: Boolean = false,
    timelineEntries: List<TimelineEntry> = emptyList(),
): SessionArtifact {
    return SessionArtifact(
        artifactId = artifactId,
        sourceSessionId = sourceSessionId,
        createdAtEpochMillis = createdAtEpochMillis,
        payloadPolicy = payloadPolicy,
        includesFullPayload = includesFullPayload,
        scenarioSummary = mapOf("surface" to "main-guided"),
        peerSummaries = emptyList(),
        timelineEntries = timelineEntries,
        storagePath = storagePath,
    )
}

internal fun assertExactUtcTimestamp(expected: String, actual: String) {
    assertTrue(
        UTC_ISO_8601_UTC_REGEX.matches(actual),
        "Expected '$actual' to match the UTC ISO 8601 export format",
    )
    assertEquals(expected, actual)
}

private val UTC_ISO_8601_UTC_REGEX = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")
