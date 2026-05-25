package ch.trancee.meshlink.reference.session

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity

internal fun createStaticReferenceSessionSnapshot(
    platformName: String,
    nowProvider: () -> Long,
    currentSnapshot: ReferenceControllerSnapshot,
    scenarioId: String,
    authorityMode: ReferenceAuthorityMode,
    surfaceOfOrigin: String,
    title: String,
    detail: String,
): ReferenceControllerSnapshot {
    val now = nowProvider()
    val sessionId = "${scenarioId}-${platformName.lowercase()}-$now"
    val baseConfiguration = currentSnapshot.session.configurationSnapshot
    val configurationSnapshot =
        baseConfiguration + mapOf("platform" to platformName, "surface" to surfaceOfOrigin)
    return ReferenceControllerSnapshot(
        session =
            ReferenceSession(
                sessionId = sessionId,
                scenarioId = scenarioId,
                authorityMode = authorityMode,
                startedAtEpochMillis = now,
                meshStateLabel = currentSnapshot.session.meshStateLabel,
                configurationSnapshot = configurationSnapshot,
                historyStatus = ReferenceHistoryStatus.LIVE,
            ),
        peers = emptyList(),
        timeline =
            listOf(
                TimelineEntry(
                    entryId = "$sessionId-1",
                    sessionId = sessionId,
                    occurredAtEpochMillis = now,
                    family = TimelineFamily.USER,
                    severity = TimelineSeverity.INFO,
                    title = title,
                    detail = detail,
                )
            ),
        activePowerModeLabel = currentSnapshot.activePowerModeLabel,
    )
}
