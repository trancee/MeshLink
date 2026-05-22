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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SessionArtifactContractTest {
    @Test
    fun redactedExportOmitsFullPayloadAndUsesUtcIsoTimestamps() = runTest {
        // Arrange
        val serializer =
            JsonSessionArtifactSerializer(documentStore = InMemoryReferenceDocumentStore())
        val session =
            ReferenceSession(
                sessionId = "session-1",
                scenarioId = "guided-first-exchange",
                authorityMode = ReferenceAuthorityMode.LIVE,
                startedAtEpochMillis = 1_000L,
                lastOutcomeSummary = "ForgetPeerResult.Forgotten",
                historyStatus = ReferenceHistoryStatus.LIVE,
            )
        val peers =
            listOf(
                PeerSnapshot(
                    peerId = "peer-1",
                    peerSuffix = "abc123",
                    trustState = PeerTrustState.TRUSTED,
                    connectionState = PeerConnectionSnapshotState.CONNECTED,
                )
            )
        val timeline =
            listOf(
                TimelineEntry(
                    entryId = "entry-1",
                    sessionId = "session-1",
                    occurredAtEpochMillis = 1_500L,
                    family = TimelineFamily.MESSAGE,
                    severity = TimelineSeverity.SUCCESS,
                    title = "Delivered",
                    detail = "Delivered",
                    payloadPreview = "he… [redacted]",
                    payloadSizeBytes = 5,
                    fullPayload = "hello",
                    fullPayloadIncluded = true,
                )
            )
        val artifact =
            SessionArtifact(
                artifactId = "artifact-session-1",
                sourceSessionId = session.sessionId,
                createdAtEpochMillis = 2_000L,
                payloadPolicy = ArtifactPayloadPolicy.REDACTED_PREVIEW,
                includesFullPayload = false,
                scenarioSummary = mapOf("surface" to "main-guided"),
                peerSummaries = emptyList(),
                timelineEntries = timeline,
                storagePath = "reference/exports/session-1.json",
            )

        // Act
        val redacted = serializer.serializeRedacted(artifact, session, peers, timeline)
        val full = serializer.serializeWithFullPayload(artifact, session, peers, timeline)
        val redactedDocument = Json.parseToJsonElement(redacted).jsonObject
        val fullDocument = Json.parseToJsonElement(full).jsonObject
        val redactedTimelineEntry =
            redactedDocument.getValue("timelineEntries").jsonArray.first().jsonObject
        val fullTimelineEntry =
            fullDocument.getValue("timelineEntries").jsonArray.first().jsonObject
        val redactedScenario = redactedDocument.getValue("scenario").jsonObject
        val redactedPeerSummary =
            redactedDocument.getValue("peerSummaries").jsonArray.first().jsonObject

        // Assert
        assertTrue(redacted.contains("payloadPreview"))
        assertFalse(redactedTimelineEntry.containsKey("fullPayload"))
        assertEquals("hello", fullTimelineEntry.getValue("fullPayload").jsonPrimitive.content)
        assertEquals(
            "Guided first exchange",
            redactedScenario.getValue("title").jsonPrimitive.content,
        )
        assertEquals("main", redactedScenario.getValue("surface").jsonPrimitive.content)
        assertEquals(
            "Trust reset",
            redactedScenario.getValue("lastOutcomeSummary").jsonPrimitive.content,
        )
        assertFalse(redactedScenario.containsKey("endedAt"))
        assertEquals("Trusted", redactedPeerSummary.getValue("trustState").jsonPrimitive.content)
        assertEquals(
            "Connected",
            redactedPeerSummary.getValue("connectionState").jsonPrimitive.content,
        )
        assertEquals(
            "5",
            redactedTimelineEntry
                .getValue("payloadMetadata")
                .jsonObject
                .getValue("sizeBytes")
                .jsonPrimitive
                .content,
        )
        assertExactUtcTimestamp(
            "1970-01-01T00:00:02.000Z",
            redactedDocument.getValue("createdAt").jsonPrimitive.content,
        )
        assertExactUtcTimestamp(
            "1970-01-01T00:00:01.000Z",
            redactedScenario.getValue("startedAt").jsonPrimitive.content,
        )
        assertExactUtcTimestamp(
            "1970-01-01T00:00:01.500Z",
            redactedTimelineEntry.getValue("occurredAt").jsonPrimitive.content,
        )
    }

    @Test
    fun liveSessionExportOmitsEndedAt() = runTest {
        // Arrange
        val serializer =
            JsonSessionArtifactSerializer(documentStore = InMemoryReferenceDocumentStore())
        val session =
            ReferenceSession(
                sessionId = "session-live",
                scenarioId = "technical-timeline",
                authorityMode = ReferenceAuthorityMode.LIVE,
                startedAtEpochMillis = 1_000L,
                historyStatus = ReferenceHistoryStatus.LIVE,
            )
        val artifact =
            SessionArtifact(
                artifactId = "artifact-live",
                sourceSessionId = session.sessionId,
                createdAtEpochMillis = 2_000L,
                payloadPolicy = ArtifactPayloadPolicy.REDACTED_PREVIEW,
                includesFullPayload = false,
                scenarioSummary = mapOf("surface" to "main-guided"),
                peerSummaries = emptyList(),
                timelineEntries = emptyList(),
                storagePath = "reference/exports/live.json",
            )

        // Act
        val redacted = serializer.serializeRedacted(artifact, session, emptyList(), emptyList())
        val redactedDocument = Json.parseToJsonElement(redacted).jsonObject
        val redactedScenario = redactedDocument.getValue("scenario").jsonObject

        // Assert
        assertFalse(redactedScenario.containsKey("endedAt"))
    }

    @Test
    fun endedSessionExportIncludesUtcIsoEndedAt() = runTest {
        // Arrange
        val serializer =
            JsonSessionArtifactSerializer(documentStore = InMemoryReferenceDocumentStore())
        val session =
            ReferenceSession(
                sessionId = "session-ended",
                scenarioId = "recent-history",
                authorityMode = ReferenceAuthorityMode.LIVE,
                startedAtEpochMillis = 1_000L,
                endedAtEpochMillis = 3_000L,
                historyStatus = ReferenceHistoryStatus.RETAINED,
            )
        val artifact =
            SessionArtifact(
                artifactId = "artifact-ended",
                sourceSessionId = session.sessionId,
                createdAtEpochMillis = 4_000L,
                payloadPolicy = ArtifactPayloadPolicy.REDACTED_PREVIEW,
                includesFullPayload = false,
                scenarioSummary = mapOf("surface" to "main-guided"),
                peerSummaries = emptyList(),
                timelineEntries = emptyList(),
                storagePath = "reference/exports/ended.json",
            )

        // Act
        val redacted = serializer.serializeRedacted(artifact, session, emptyList(), emptyList())
        val redactedDocument = Json.parseToJsonElement(redacted).jsonObject
        val redactedScenario = redactedDocument.getValue("scenario").jsonObject

        // Assert
        assertExactUtcTimestamp(
            "1970-01-01T00:00:03.000Z",
            redactedScenario.getValue("endedAt").jsonPrimitive.content,
        )
    }

    @Test
    fun exportOmitsUnknownPayloadMetadata() = runTest {
        // Arrange
        val serializer =
            JsonSessionArtifactSerializer(documentStore = InMemoryReferenceDocumentStore())
        val session =
            ReferenceSession(
                sessionId = "session-2",
                scenarioId = "technical-timeline",
                authorityMode = ReferenceAuthorityMode.LIVE,
                startedAtEpochMillis = 1_000L,
                historyStatus = ReferenceHistoryStatus.LIVE,
            )
        val timeline =
            listOf(
                TimelineEntry(
                    entryId = "entry-2",
                    sessionId = session.sessionId,
                    occurredAtEpochMillis = 1_500L,
                    family = TimelineFamily.DIAGNOSTIC,
                    severity = TimelineSeverity.INFO,
                    title = "Mesh started",
                    detail = "mesh.start() -> Started",
                )
            )
        val artifact =
            SessionArtifact(
                artifactId = "artifact-session-2",
                sourceSessionId = session.sessionId,
                createdAtEpochMillis = 2_000L,
                payloadPolicy = ArtifactPayloadPolicy.REDACTED_PREVIEW,
                includesFullPayload = false,
                scenarioSummary = mapOf("surface" to "main-guided"),
                peerSummaries = emptyList(),
                timelineEntries = timeline,
                storagePath = "reference/exports/session-2.json",
            )

        // Act
        val redacted =
            serializer.serializeRedacted(
                artifact = artifact,
                session = session,
                peers = emptyList(),
                timeline = timeline,
            )
        val redactedDocument = Json.parseToJsonElement(redacted).jsonObject
        val redactedTimelineEntry =
            redactedDocument.getValue("timelineEntries").jsonArray.first().jsonObject

        // Assert
        assertFalse(redactedTimelineEntry.containsKey("payloadMetadata"))
    }
}

private fun assertExactUtcTimestamp(expected: String, actual: String) {
    assertTrue(
        UTC_ISO_8601_UTC_REGEX.matches(actual),
        "Expected '$actual' to match the UTC ISO 8601 export format",
    )
    assertEquals(expected, actual)
}

private val UTC_ISO_8601_UTC_REGEX = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")
