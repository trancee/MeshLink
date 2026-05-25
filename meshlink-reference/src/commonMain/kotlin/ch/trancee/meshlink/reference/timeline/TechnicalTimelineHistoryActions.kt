package ch.trancee.meshlink.reference.timeline

import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import kotlinx.coroutines.launch

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
