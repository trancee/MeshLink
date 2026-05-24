package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow

class LiveProofAutomationDriverTest {
    @Test
    fun driverRequestsMeshStartFromTheTimelineStateWithoutCompose() {
        // Arrange
        val automationConfig =
            ReferenceAutomationConfig(
                mode = ReferenceAutomationMode.LIVE_PROOF,
                role = ReferenceAutomationRole.PASSIVE,
                appId = "demo.meshlink.reference.test",
                storageSubdirectory = "driver-mesh-start",
            )
        val timelineUiStateFlow =
            MutableStateFlow(
                TechnicalTimelineUiState(
                    liveSnapshot =
                        automationSnapshot(meshStateLabel = "Uninitialized", timeline = emptyList())
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

        // Assert
        assertEquals(1, actions.meshStartRequests)
        assertTrue(actions.logs.any { log -> log.contains("REFERENCE_AUTOMATION started") })
        assertTrue(
            actions.logs.any { log ->
                log.contains("REFERENCE_AUTOMATION mesh.start.requested role=PASSIVE")
            }
        )
    }

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
                        automationSnapshot(
                            sessionId = sessionId,
                            meshStateLabel = "Running",
                            timeline =
                                listOf(
                                    timelineEntry(
                                        sessionId = sessionId,
                                        entryId = "$sessionId-1",
                                        title = "TRUST_ESTABLISHED",
                                        detail = "TRUST_ESTABLISHED @ trust.pin",
                                        family = TimelineFamily.DIAGNOSTIC,
                                        peerSuffix = "abc123",
                                    ),
                                    timelineEntry(
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
                    listOf(retainedSession(sessionId = sessionId, endedAtEpochMillis = 4L))
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

private fun automationSnapshot(
    sessionId: String = "session-1",
    meshStateLabel: String,
    timeline: List<TimelineEntry>,
): ReferenceControllerSnapshot {
    return ReferenceControllerSnapshot(
        session =
            ReferenceSession(
                sessionId = sessionId,
                scenarioId = "guided-first-exchange",
                authorityMode = ReferenceAuthorityMode.LIVE,
                startedAtEpochMillis = 1L,
                meshStateLabel = meshStateLabel,
            ),
        peers = emptyList(),
        timeline = timeline,
        activePowerModeLabel = "Automatic",
    )
}

private fun timelineEntry(
    sessionId: String,
    entryId: String,
    title: String,
    detail: String,
    family: TimelineFamily,
    peerSuffix: String? = null,
    payloadSizeBytes: Int? = null,
): TimelineEntry {
    return TimelineEntry(
        entryId = entryId,
        sessionId = sessionId,
        occurredAtEpochMillis = 2L,
        family = family,
        severity = TimelineSeverity.INFO,
        title = title,
        detail = detail,
        peerSuffix = peerSuffix,
        payloadSizeBytes = payloadSizeBytes,
    )
}

private fun retainedSession(sessionId: String, endedAtEpochMillis: Long): ReferenceSession {
    return ReferenceSession(
        sessionId = sessionId,
        scenarioId = "guided-first-exchange",
        authorityMode = ReferenceAuthorityMode.LIVE,
        startedAtEpochMillis = 1L,
        endedAtEpochMillis = endedAtEpochMillis,
    )
}

private class RecordingLiveProofAutomationActions(
    override val platformName: String = "Android",
    override val readinessBlockers: List<String> = emptyList(),
) : LiveProofAutomationActions {
    val logs: MutableList<String> = mutableListOf()
    var meshStartRequests: Int = 0
    var endSessionRequests: Int = 0
    val exportRequests: MutableList<ExportPayloadPolicy> = mutableListOf()

    override fun emitAutomationLog(message: String) {
        logs += message
    }

    override fun requestMeshStart() {
        meshStartRequests += 1
    }

    override fun requestMeshPause() = Unit

    override fun requestMeshResume() = Unit

    override fun requestSendSamplePayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ) = Unit

    override fun requestForgetPeer(peerId: String) = Unit

    override fun requestEndCurrentSession() {
        endSessionRequests += 1
    }

    override fun requestExportCurrentSession(policy: ExportPayloadPolicy) {
        exportRequests += policy
    }
}
