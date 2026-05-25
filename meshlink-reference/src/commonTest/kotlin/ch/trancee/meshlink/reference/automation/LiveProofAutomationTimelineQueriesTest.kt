package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.redactedSuffix
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LiveProofAutomationTimelineQueriesTest {
    @Test
    fun latestAutomationObservationUsesTheNewestMatchingPeerEntry() {
        // Arrange
        val routedPeerId = "peer-routed-target-abcdef"
        val targetPeerSuffix = redactedSuffix(routedPeerId)
        val snapshot =
            ReferenceControllerSnapshot(
                session =
                    ReferenceSession(
                        sessionId = "session-1",
                        scenarioId = "guided-first-exchange",
                        authorityMode = ReferenceAuthorityMode.LIVE,
                        startedAtEpochMillis = 1L,
                    ),
                peers = emptyList(),
                timeline =
                    listOf(
                        TimelineEntry(
                            entryId = "session-1-1",
                            sessionId = "session-1",
                            occurredAtEpochMillis = 2L,
                            family = TimelineFamily.DIAGNOSTIC,
                            severity = TimelineSeverity.INFO,
                            title = "ROUTE_DISCOVERED",
                            detail = "route available",
                            peerSuffix = targetPeerSuffix,
                        ),
                        TimelineEntry(
                            entryId = "session-1-2",
                            sessionId = "session-1",
                            occurredAtEpochMillis = 3L,
                            family = TimelineFamily.MESSAGE,
                            severity = TimelineSeverity.ERROR,
                            title = "Guided message not sent",
                            detail = "send unreachable",
                            peerSuffix = targetPeerSuffix,
                        ),
                    ),
                activePowerModeLabel = "Automatic",
            )

        // Act
        val observation =
            latestAutomationObservation(snapshot = snapshot, peerSuffix = targetPeerSuffix)

        // Assert
        assertNotNull(observation)
        assertEquals("Guided message not sent", observation.title)
        assertEquals("send unreachable", observation.detail)
    }

    @Test
    fun inboundHelpersTrackRecoveryCountsAndLargestPayloadBytes() {
        // Arrange
        val snapshot =
            ReferenceControllerSnapshot(
                session =
                    ReferenceSession(
                        sessionId = "session-1",
                        scenarioId = "guided-first-exchange",
                        authorityMode = ReferenceAuthorityMode.LIVE,
                        startedAtEpochMillis = 1L,
                    ),
                peers = emptyList(),
                timeline =
                    listOf(
                        TimelineEntry(
                            entryId = "session-1-1",
                            sessionId = "session-1",
                            occurredAtEpochMillis = 2L,
                            family = TimelineFamily.MESSAGE,
                            severity = TimelineSeverity.SUCCESS,
                            title = "Inbound message",
                            detail = "Received 32 bytes from abc123.",
                            peerSuffix = "abc123",
                            payloadSizeBytes = 32,
                        ),
                        TimelineEntry(
                            entryId = "session-1-2",
                            sessionId = "session-1",
                            occurredAtEpochMillis = 3L,
                            family = TimelineFamily.MESSAGE,
                            severity = TimelineSeverity.SUCCESS,
                            title = "Inbound message",
                            detail = "Received 8192 bytes from abc123.",
                            peerSuffix = "abc123",
                            payloadSizeBytes = 8_192,
                        ),
                    ),
                activePowerModeLabel = "Automatic",
            )

        // Act
        val inboundCount = timelineEntryCount(snapshot, title = "Inbound message")
        val largestInboundBytes = largestInboundPayloadBytes(snapshot)

        // Assert
        assertEquals(2, inboundCount)
        assertEquals(8_192, largestInboundBytes)
    }
}
