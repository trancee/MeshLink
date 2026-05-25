package ch.trancee.meshlink.reference.timeline

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
    targetSurface: ch.trancee.meshlink.reference.navigation.ReferenceSurfaceId,
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
            ch.trancee.meshlink.reference.navigation.ReferenceSurfaceId.SOLO_EXPLORATION ->
                sessionController.startSoloSession()
            ch.trancee.meshlink.reference.navigation.ReferenceSurfaceId.LAB ->
                sessionController.startLabSession()
            ch.trancee.meshlink.reference.navigation.ReferenceSurfaceId.ADVANCED_CONTROLS ->
                sessionController.startNewSupportedSession(surfaceOfOrigin = "advanced-controls")
            else -> sessionController.startNewSupportedSession(surfaceOfOrigin = "main-guided")
        }
        refreshRetainedSessions(lastExportPath = exportPath)
    }
}

public fun TechnicalTimelineStore.openRetainedSession(sessionId: String): Unit {
    scope.launch {
        val retained = historyRepository.loadRetainedSnapshot(sessionId) ?: return@launch
        updateState { current ->
            current.copy(
                retainedSnapshot = retained,
                visibleEntries = current.filters.apply(retained.timeline),
            )
        }
    }
}

public fun TechnicalTimelineStore.returnToLive(): Unit {
    updateState { current ->
        current.copy(
            retainedSnapshot = null,
            visibleEntries = current.filters.apply(current.liveSnapshot.timeline),
        )
    }
}

public fun TechnicalTimelineStore.deleteRetainedSession(sessionId: String): Unit {
    scope.launch {
        historyRepository.deleteSession(sessionId)
        val retainedSessions = historyRepository.loadRetainedSessions()
        updateState { current ->
            val retainedSnapshot =
                current.retainedSnapshot?.takeUnless { snapshot ->
                    snapshot.session.sessionId == sessionId
                }
            val updated =
                current.copy(
                    retainedSessions = retainedSessions,
                    retainedSnapshot = retainedSnapshot,
                )
            updated.copy(visibleEntries = updated.filters.apply(updated.currentSnapshot.timeline))
        }
    }
}

public fun TechnicalTimelineStore.clearHistory(): Unit {
    scope.launch {
        historyRepository.clearAll()
        updateState { current ->
            current.copy(
                retainedSessions = emptyList(),
                retainedSnapshot = null,
                visibleEntries = current.filters.apply(current.liveSnapshot.timeline),
            )
        }
    }
}

public fun TechnicalTimelineStore.exportCurrentSession(policy: ExportPayloadPolicy): Unit {
    scope.launch {
        val currentSnapshot = uiState.value.currentSnapshot
        val storagePath =
            writeExport(currentSnapshot, normalizeExportPolicy(currentSnapshot, policy))
        updateState { current -> current.copy(lastExportPath = storagePath) }
    }
}
