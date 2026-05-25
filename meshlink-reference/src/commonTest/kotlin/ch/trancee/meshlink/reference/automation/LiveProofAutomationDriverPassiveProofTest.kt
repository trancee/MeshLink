package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow

class LiveProofAutomationDriverPassiveProofTest {
    @Test
    fun driverCompletesPassiveProofFromBackgroundStateUpdates() {
        // Arrange
        val sessionId = "session-passive-proof"
        val automationConfig =
            ReferenceAutomationConfig(
                mode = ReferenceAutomationMode.LIVE_PROOF,
                role = ReferenceAutomationRole.PASSIVE,
                appId = "demo.meshlink.reference.test",
                storageSubdirectory = "driver-passive-proof",
            )
        val timelineUiStateFlow =
            MutableStateFlow(
                TechnicalTimelineUiState(
                    liveSnapshot =
                        automationTestSnapshot(
                            sessionId = sessionId,
                            meshStateLabel = "Running",
                            timeline =
                                listOf(
                                    driverTimelineEntry(
                                        sessionId = sessionId,
                                        entryId = "$sessionId-1",
                                        title = "TRUST_ESTABLISHED",
                                        detail = "TRUST_ESTABLISHED @ trust.pin",
                                        family = TimelineFamily.DIAGNOSTIC,
                                        peerSuffix = "abc123",
                                    ),
                                    driverTimelineEntry(
                                        sessionId = sessionId,
                                        entryId = "$sessionId-2",
                                        title = "Inbound message",
                                        detail = "Received 19 bytes from abc123.",
                                        family = TimelineFamily.MESSAGE,
                                        peerSuffix = "abc123",
                                        payloadSizeBytes = 19,
                                    ),
                                ),
                        )
                )
            )
        val actions = RecordingLiveProofAutomationActions()
        val driver =
            LiveProofAutomationDriver(
                automationConfig = automationConfig,
                timelineUiStateFlow = timelineUiStateFlow,
                actions = actions,
            )

        // Act
        driver.handle(timelineUiStateFlow.value)

        timelineUiStateFlow.value =
            timelineUiStateFlow.value.copy(
                retainedSessions =
                    listOf(retainedDriverSession(sessionId = sessionId, endedAtEpochMillis = 4L))
            )
        driver.handle(timelineUiStateFlow.value)

        timelineUiStateFlow.value =
            timelineUiStateFlow.value.copy(
                lastExportPath = "reference/exports/$sessionId-redacted.json"
            )
        driver.handle(timelineUiStateFlow.value)

        // Assert
        assertEquals(1, actions.endSessionRequests)
        assertEquals(listOf(ExportPayloadPolicy.REDACTED_PREVIEW), actions.exportRequests)
        assertTrue(
            actions.logs.any { log ->
                log.contains("REFERENCE_AUTOMATION session.end.requested role=passive")
            }
        )
        assertTrue(
            actions.logs.any { log ->
                log.contains(
                    "REFERENCE_AUTOMATION export.requested role=passive policy=redacted-preview"
                )
            }
        )
        assertTrue(
            actions.logs.any { log ->
                log.contains("REFERENCE_AUTOMATION proof.complete role=passive") &&
                    log.contains("export=reference/exports/$sessionId-redacted.json")
            }
        )
    }
}
