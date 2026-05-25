package ch.trancee.meshlink.reference.timeline

import ch.trancee.meshlink.reference.navigation.ReferenceSurfaceId
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import kotlinx.coroutines.launch

public fun TechnicalTimelineStore.endCurrentSession(
    preEndExportPolicy: ExportPayloadPolicy? = null
): Unit {
    scope.launch {
        val current = uiState.value
        if (!current.isSupportedLiveSession || current.viewingRetained) {
            return@launch
        }

        val snapshotBeforeEnd = current.liveSnapshot
        val exportPath =
            preEndExportPolicy?.let { policy ->
                writeExport(snapshotBeforeEnd, normalizeExportPolicy(snapshotBeforeEnd, policy))
            } ?: current.lastExportPath
        val endedSnapshot = sessionController.endSupportedSession()
        retainIfEligible(endedSnapshot)
        refreshRetainedSessions(lastExportPath = exportPath)
    }
}

public fun TechnicalTimelineStore.startNewSupportedSession(
    surfaceOfOrigin: String = "main-guided"
): Unit {
    scope.launch {
        sessionController.startNewSupportedSession(surfaceOfOrigin = surfaceOfOrigin)
        updateState { current ->
            current.copy(
                retainedSnapshot = null,
                visibleEntries = current.filters.apply(sessionController.snapshot.value.timeline),
            )
        }
    }
}

public fun TechnicalTimelineStore.transitionToSoloSession(
    preBoundaryExportPolicy: ExportPayloadPolicy? = null
): Unit {
    scope.launch {
        val current = uiState.value
        if (!current.isSupportedLiveSession || current.viewingRetained) {
            return@launch
        }

        val supportedSnapshot = current.liveSnapshot
        val exportPath =
            preBoundaryExportPolicy?.let { policy ->
                writeExport(supportedSnapshot, normalizeExportPolicy(supportedSnapshot, policy))
            } ?: current.lastExportPath
        retainIfEligible(
            endedBoundarySnapshot(supportedSnapshot, platformServices.currentTimeMillis())
        )
        sessionController.startSoloSession()
        refreshRetainedSessions(lastExportPath = exportPath)
    }
}

public fun TechnicalTimelineStore.transitionToLabSession(
    preBoundaryExportPolicy: ExportPayloadPolicy? = null
): Unit {
    scope.launch {
        val current = uiState.value
        if (!current.isSupportedLiveSession || current.viewingRetained) {
            return@launch
        }

        val supportedSnapshot = current.liveSnapshot
        val exportPath =
            preBoundaryExportPolicy?.let { policy ->
                writeExport(supportedSnapshot, normalizeExportPolicy(supportedSnapshot, policy))
            } ?: current.lastExportPath
        retainIfEligible(
            endedBoundarySnapshot(supportedSnapshot, platformServices.currentTimeMillis())
        )
        sessionController.startLabSession()
        refreshRetainedSessions(lastExportPath = exportPath)
    }
}

public fun TechnicalTimelineStore.transitionAlternativeSession(
    targetSurface: ReferenceSurfaceId,
    exportBeforeExit: Boolean,
): Unit {
    scope.launch {
        val current = uiState.value
        if (!current.isAlternativeSession || current.viewingRetained) {
            return@launch
        }

        val exportPath =
            if (exportBeforeExit) {
                writeExport(current.liveSnapshot, ExportPayloadPolicy.REDACTED_PREVIEW)
            } else {
                current.lastExportPath
            }
        when (targetSurface) {
            ReferenceSurfaceId.SOLO_EXPLORATION -> sessionController.startSoloSession()
            ReferenceSurfaceId.LAB -> sessionController.startLabSession()
            ReferenceSurfaceId.ADVANCED_CONTROLS ->
                sessionController.startNewSupportedSession(surfaceOfOrigin = "advanced-controls")
            else -> sessionController.startNewSupportedSession(surfaceOfOrigin = "main-guided")
        }
        refreshRetainedSessions(lastExportPath = exportPath)
    }
}
