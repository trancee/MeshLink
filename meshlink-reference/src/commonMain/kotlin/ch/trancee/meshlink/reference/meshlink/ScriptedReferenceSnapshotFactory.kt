package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity

internal fun createScriptedReferenceInitialSnapshot(
    platformName: String,
    authorityMode: String,
    nowProvider: () -> Long,
    appId: String,
    surfaceOfOrigin: String,
    sessionId: String,
): ReferenceControllerSnapshot {
    val startedAtEpochMillis = nowProvider()
    return ReferenceControllerSnapshot(
        session =
            ReferenceSession(
                sessionId = sessionId,
                scenarioId = "guided-first-exchange",
                authorityMode = authorityMode,
                startedAtEpochMillis = startedAtEpochMillis,
                meshStateLabel = MeshLinkState.Uninitialized.toString(),
                configurationSnapshot =
                    mapOf(
                        "platform" to platformName,
                        "surface" to surfaceOfOrigin,
                        "appId" to appId,
                        "regulatoryRegion" to "DEFAULT",
                        "powerMode" to "Automatic",
                        "deliveryRetryDeadline" to "15s",
                    ),
                historyStatus = ReferenceHistoryStatus.LIVE,
            ),
        peers = emptyList(),
        timeline =
            listOf(
                ReferenceTimelineEvent(
                        family = TimelineFamily.USER,
                        severity = TimelineSeverity.INFO,
                        title = "Automation session created",
                        detail =
                            "A deterministic scripted controller is active for $platformName UI automation.",
                    )
                    .toTimelineEntry(
                        sessionId = sessionId,
                        entryIndex = 1,
                        occurredAtEpochMillis = startedAtEpochMillis,
                    )
            ),
        activePowerModeLabel = "Automatic",
    )
}
