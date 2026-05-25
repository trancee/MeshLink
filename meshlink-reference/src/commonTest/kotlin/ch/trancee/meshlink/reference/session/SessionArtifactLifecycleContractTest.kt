package ch.trancee.meshlink.reference.session

import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SessionArtifactLifecycleContractTest {
    @Test
    fun liveSessionExportOmitsEndedAt() = runTest {
        // Arrange
        val serializer = sessionArtifactSerializer()
        val session =
            sessionArtifactSession(
                sessionId = "session-live",
                scenarioId = "technical-timeline",
                historyStatus = ReferenceHistoryStatus.LIVE,
            )
        val artifact =
            sessionArtifact(
                artifactId = "artifact-live",
                sourceSessionId = session.sessionId,
                createdAtEpochMillis = 2_000L,
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
        val serializer = sessionArtifactSerializer()
        val session =
            sessionArtifactSession(
                sessionId = "session-ended",
                scenarioId = "recent-history",
                endedAtEpochMillis = 3_000L,
                historyStatus = ReferenceHistoryStatus.RETAINED,
            )
        val artifact =
            sessionArtifact(
                artifactId = "artifact-ended",
                sourceSessionId = session.sessionId,
                createdAtEpochMillis = 4_000L,
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
}
