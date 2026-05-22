package ch.trancee.meshlink.reference.timeline

import ch.trancee.meshlink.reference.automation.ReferenceAutomationMode
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.ArtifactPayloadPolicy
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.SessionArtifact
import ch.trancee.meshlink.reference.model.withoutSensitivePayload
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import kotlinx.coroutines.launch

public fun TechnicalTimelineStore.retainCurrentSession(): Unit {
    scope.launch {
        val current = uiState.value
        if (current.viewingRetained) {
            return@launch
        }
        val retainedSnapshot =
            current.currentSnapshot.copy(
                session =
                    current.currentSnapshot.session.copy(
                        endedAtEpochMillis = platformServices.currentTimeMillis(),
                        historyStatus = ReferenceHistoryStatus.RETAINED,
                    ),
                timeline =
                    current.currentSnapshot.timeline.map { entry ->
                        entry.withoutSensitivePayload()
                    },
            )
        updateState { state ->
            state.copy(
                retainedSessions =
                    upsertRetainedSession(
                        existing = state.retainedSessions,
                        session = retainedSnapshot.session,
                    )
            )
        }
        historyRepository.retainSnapshot(retainedSnapshot)
        val retainedSessions =
            mergeRetainedSessions(
                current = uiState.value.retainedSessions,
                loaded = historyRepository.loadRetainedSessions(),
            )
        val lastExportPath =
            if (platformServices.automationConfig?.mode == ReferenceAutomationMode.SCRIPTED_UI) {
                writeExport(
                    snapshot = retainedSnapshot,
                    policy = ExportPayloadPolicy.REDACTED_PREVIEW,
                )
            } else {
                uiState.value.lastExportPath
            }
        updateState { state ->
            state.copy(retainedSessions = retainedSessions, lastExportPath = lastExportPath)
        }
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
        val storagePath = writeExport(snapshot = uiState.value.currentSnapshot, policy = policy)
        updateState { current -> current.copy(lastExportPath = storagePath) }
    }
}

private suspend fun TechnicalTimelineStore.writeExport(
    snapshot: ReferenceControllerSnapshot,
    policy: ExportPayloadPolicy,
): String {
    val artifact =
        SessionArtifact(
            artifactId = "artifact-${snapshot.session.sessionId}",
            sourceSessionId = snapshot.session.sessionId,
            createdAtEpochMillis = platformServices.currentTimeMillis(),
            payloadPolicy =
                if (policy == ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN) {
                    ArtifactPayloadPolicy.FULL_OPT_IN
                } else {
                    ArtifactPayloadPolicy.REDACTED_PREVIEW
                },
            includesFullPayload =
                policy == ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN &&
                    snapshot.timeline.any { entry -> entry.fullPayload != null },
            scenarioSummary = snapshot.session.configurationSnapshot,
            peerSummaries =
                snapshot.peers.map { peer ->
                    mapOf("peerSuffix" to peer.peerSuffix, "trustState" to peer.trustState.name)
                },
            timelineEntries = snapshot.timeline,
            storagePath = "reference/exports/${snapshot.session.sessionId}.json",
        )
    val serialized =
        if (policy == ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN) {
            artifactSerializer.serializeWithFullPayload(
                artifact,
                snapshot.session,
                snapshot.peers,
                snapshot.timeline,
            )
        } else {
            artifactSerializer.serializeRedacted(
                artifact,
                snapshot.session,
                snapshot.peers,
                snapshot.timeline,
            )
        }
    return artifactSerializer.writeArtifact(artifact, serialized)
}

private fun upsertRetainedSession(
    existing: List<ReferenceSession>,
    session: ReferenceSession,
): List<ReferenceSession> {
    return listOf(session) + existing.filterNot { item -> item.sessionId == session.sessionId }
}
