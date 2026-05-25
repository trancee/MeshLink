package ch.trancee.meshlink.reference.timeline

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.navigation.ReferenceSurfaceId
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import kotlinx.coroutines.launch

public fun TechnicalTimelineStore.endCurrentSession(
    preEndExportPolicy: ExportPayloadPolicy? = null
): Unit {
    scope.launch { endCurrentSessionNow(preEndExportPolicy) }
}

internal suspend fun TechnicalTimelineStore.endCurrentSessionNow(
    preEndExportPolicy: ExportPayloadPolicy? = null
): Unit {
    val current = uiState.value
    if (!current.isSupportedLiveSession || current.viewingRetained) {
        return
    }

    val snapshotBeforeEnd = current.liveSnapshot
    val exportPath =
        preEndExportPolicy?.let { policy ->
            writeExport(snapshotBeforeEnd, normalizeExportPolicy(snapshotBeforeEnd, policy))
        } ?: current.lastExportPath
    val endedSnapshot = sessionController.endSupportedSession()
    syncLiveSnapshot(endedSnapshot)
    retainIfEligible(endedSnapshot)
    refreshRetainedSessions(lastExportPath = exportPath)
}

public fun TechnicalTimelineStore.startNewSupportedSession(
    surfaceOfOrigin: String = "main-guided"
): Unit {
    scope.launch { startNewSupportedSessionNow(surfaceOfOrigin) }
}

internal suspend fun TechnicalTimelineStore.startNewSupportedSessionNow(
    surfaceOfOrigin: String = "main-guided"
): Unit {
    val snapshot = sessionController.startNewSupportedSession(surfaceOfOrigin = surfaceOfOrigin)
    syncLiveSnapshot(snapshot)
}

public fun TechnicalTimelineStore.transitionToSoloSession(
    preBoundaryExportPolicy: ExportPayloadPolicy? = null
): Unit {
    scope.launch { transitionToSoloSessionNow(preBoundaryExportPolicy) }
}

internal suspend fun TechnicalTimelineStore.transitionToSoloSessionNow(
    preBoundaryExportPolicy: ExportPayloadPolicy? = null
): Unit {
    val current = uiState.value
    if (!current.isSupportedLiveSession || current.viewingRetained) {
        return
    }

    val supportedSnapshot = current.liveSnapshot
    val exportPath =
        preBoundaryExportPolicy?.let { policy ->
            writeExport(supportedSnapshot, normalizeExportPolicy(supportedSnapshot, policy))
        } ?: current.lastExportPath
    retainIfEligible(endedBoundarySnapshot(supportedSnapshot, platformServices.currentTimeMillis()))
    val soloSnapshot = sessionController.startSoloSession()
    syncLiveSnapshot(soloSnapshot)
    refreshRetainedSessions(lastExportPath = exportPath)
}

public fun TechnicalTimelineStore.transitionToLabSession(
    preBoundaryExportPolicy: ExportPayloadPolicy? = null
): Unit {
    scope.launch { transitionToLabSessionNow(preBoundaryExportPolicy) }
}

internal suspend fun TechnicalTimelineStore.transitionToLabSessionNow(
    preBoundaryExportPolicy: ExportPayloadPolicy? = null
): Unit {
    val current = uiState.value
    if (!current.isSupportedLiveSession || current.viewingRetained) {
        return
    }

    val supportedSnapshot = current.liveSnapshot
    val exportPath =
        preBoundaryExportPolicy?.let { policy ->
            writeExport(supportedSnapshot, normalizeExportPolicy(supportedSnapshot, policy))
        } ?: current.lastExportPath
    retainIfEligible(endedBoundarySnapshot(supportedSnapshot, platformServices.currentTimeMillis()))
    val labSnapshot = sessionController.startLabSession()
    syncLiveSnapshot(labSnapshot)
    refreshRetainedSessions(lastExportPath = exportPath)
}

public fun TechnicalTimelineStore.transitionAlternativeSession(
    targetSurface: ReferenceSurfaceId,
    exportBeforeExit: Boolean,
): Unit {
    scope.launch { transitionAlternativeSessionNow(targetSurface, exportBeforeExit) }
}

internal suspend fun TechnicalTimelineStore.transitionAlternativeSessionNow(
    targetSurface: ReferenceSurfaceId,
    exportBeforeExit: Boolean,
): Unit {
    val current = uiState.value
    if (!current.isAlternativeSession || current.viewingRetained) {
        return
    }

    val exportPath =
        if (exportBeforeExit) {
            writeExport(current.liveSnapshot, ExportPayloadPolicy.REDACTED_PREVIEW)
        } else {
            current.lastExportPath
        }
    when (targetSurface) {
        ReferenceSurfaceId.SOLO_EXPLORATION ->
            syncLiveSnapshot(sessionController.startSoloSession())
        ReferenceSurfaceId.LAB -> syncLiveSnapshot(sessionController.startLabSession())
        ReferenceSurfaceId.ADVANCED_CONTROLS ->
            startNewSupportedSessionNow(
                surfaceOfOrigin = ReferenceSurfaceId.ADVANCED_CONTROLS.route
            )
        else -> startNewSupportedSessionNow(surfaceOfOrigin = ReferenceSurfaceId.MAIN_GUIDED.route)
    }
    refreshRetainedSessions(lastExportPath = exportPath)
}

private fun TechnicalTimelineStore.syncLiveSnapshot(
    liveSnapshot: ReferenceControllerSnapshot
): Unit {
    updateState { current ->
        current.copy(
            liveSnapshot = liveSnapshot,
            retainedSnapshot = null,
            visibleEntries = current.filters.apply(liveSnapshot.timeline),
        )
    }
}
