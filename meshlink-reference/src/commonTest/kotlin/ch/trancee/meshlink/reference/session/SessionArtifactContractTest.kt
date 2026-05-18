package ch.trancee.meshlink.reference.session

import ch.trancee.meshlink.reference.model.PeerConnectionSnapshotState
import ch.trancee.meshlink.reference.model.PeerSnapshot
import ch.trancee.meshlink.reference.model.PeerTrustState
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

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
                    payloadPreview = "hello",
                )
            )

        val redacted = serializer.serializeRedacted(session, peers, timeline)
        val full = serializer.serializeWithFullPayload(session, peers, timeline)

        assertTrue(redacted.contains("payloadPreview"))
        assertFalse(redacted.contains("\"fullPayload\":\"hello\""))
        assertTrue(full.contains("fullPayload"))
    }
}
