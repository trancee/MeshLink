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
    fun redactedExportOmitsFullPayload() = runTest {
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
                    occurredAtEpochMillis = 1_000L,
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
        assertEquals("2000", redactedDocument.getValue("createdAt").jsonPrimitive.content)
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
