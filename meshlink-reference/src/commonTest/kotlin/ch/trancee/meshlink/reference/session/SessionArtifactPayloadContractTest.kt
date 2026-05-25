package ch.trancee.meshlink.reference.session

import ch.trancee.meshlink.reference.model.ArtifactPayloadPolicy
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SessionArtifactPayloadContractTest {
    @Test
    fun redactedExportOmitsFullPayloadAndUsesUtcIsoTimestamps() = runTest {
        // Arrange
        val serializer = sessionArtifactSerializer()
        val session =
            sessionArtifactSession(
                sessionId = "session-1",
                lastOutcomeSummary = "ForgetPeerResult.Forgotten",
                historyStatus = ReferenceHistoryStatus.LIVE,
            )
        val peers = listOf(sessionArtifactPeerSummary())
        val timeline =
            listOf(
                sessionArtifactTimelineEntry(
                    entryId = "entry-1",
                    sessionId = "session-1",
                    title = "Delivered",
                    detail = "Delivered",
                    payloadPreview = "he… [redacted]",
                    payloadSizeBytes = 5,
                    fullPayload = "hello",
                    fullPayloadIncluded = true,
                )
            )
        val artifact =
            sessionArtifact(
                artifactId = "artifact-session-1",
                sourceSessionId = session.sessionId,
                createdAtEpochMillis = 2_000L,
                storagePath = "reference/exports/session-1.json",
                payloadPolicy = ArtifactPayloadPolicy.REDACTED_PREVIEW,
                includesFullPayload = false,
                timelineEntries = timeline,
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
    fun soloExportPreservesTheSoloSurfaceIdentity() = runTest {
        // Arrange
        val serializer = sessionArtifactSerializer()
        val session =
            sessionArtifactSession(
                sessionId = "session-solo",
                scenarioId = "solo-exploration",
                authorityMode = ReferenceAuthorityMode.SOLO,
                surfaceOfOrigin = "solo-exploration",
            )
        val artifact =
            sessionArtifact(
                artifactId = "artifact-session-solo",
                sourceSessionId = session.sessionId,
                createdAtEpochMillis = 2_000L,
                storagePath = "reference/exports/session-solo.json",
            )

        // Act
        val redacted =
            serializer.serializeRedacted(
                artifact = artifact,
                session = session,
                peers = emptyList(),
                timeline = emptyList(),
            )
        val redactedDocument = Json.parseToJsonElement(redacted).jsonObject
        val redactedScenario = redactedDocument.getValue("scenario").jsonObject

        // Assert
        assertEquals("solo", redactedScenario.getValue("surface").jsonPrimitive.content)
        assertEquals("solo", redactedScenario.getValue("authorityMode").jsonPrimitive.content)
    }

    @Test
    fun exportOmitsUnknownPayloadMetadata() = runTest {
        // Arrange
        val serializer = sessionArtifactSerializer()
        val session =
            sessionArtifactSession(sessionId = "session-2", scenarioId = "technical-timeline")
        val timeline =
            listOf(
                sessionArtifactTimelineEntry(
                    entryId = "entry-2",
                    sessionId = session.sessionId,
                    title = "Mesh started",
                    detail = "mesh.start() -> Started",
                    family = ch.trancee.meshlink.reference.model.TimelineFamily.DIAGNOSTIC,
                    severity = ch.trancee.meshlink.reference.model.TimelineSeverity.INFO,
                )
            )
        val artifact =
            sessionArtifact(
                artifactId = "artifact-session-2",
                sourceSessionId = session.sessionId,
                createdAtEpochMillis = 2_000L,
                storagePath = "reference/exports/session-2.json",
                timelineEntries = timeline,
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
