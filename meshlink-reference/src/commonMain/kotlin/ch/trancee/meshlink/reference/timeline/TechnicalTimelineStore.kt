package ch.trancee.meshlink.reference.timeline

import ch.trancee.meshlink.reference.automation.ReferenceAutomationMode
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.SessionArtifact
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import ch.trancee.meshlink.reference.session.JsonSessionArtifactSerializer
import ch.trancee.meshlink.reference.session.JsonSessionHistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

public class TechnicalTimelineStore(
    private val platformServices: PlatformServices,
    private val historyRepository: JsonSessionHistoryRepository,
    private val artifactSerializer: JsonSessionArtifactSerializer,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val stateFlow: MutableStateFlow<TechnicalTimelineUiState> =
        MutableStateFlow(
            TechnicalTimelineUiState(
                liveSnapshot = platformServices.meshLinkController.snapshot.value
            )
        )

    public val uiState: StateFlow<TechnicalTimelineUiState> = stateFlow.asStateFlow()

    init {
        scope.launch {
            historyRepository.loadRetainedSessions().let { retainedSessions ->
                updateState { current ->
                    current.copy(
                        retainedSessions =
                            mergeRetainedSessions(
                                current = current.retainedSessions,
                                loaded = retainedSessions,
                            )
                    )
                }
            }
        }
        scope.launch {
            platformServices.meshLinkController.snapshot.collectLatest { snapshot ->
                updateState { current ->
                    current.copy(
                        liveSnapshot = snapshot,
                        visibleEntries = visibleEntriesForUpdatedLiveSnapshot(current, snapshot),
                    )
                }
            }
        }
    }

    public fun updateSearch(text: String): Unit {
        updateFilters(stateFlow.value.filters.copy(searchText = text))
    }

    public fun updatePeer(peerSuffix: String?): Unit {
        updateFilters(stateFlow.value.filters.copy(peerSuffix = peerSuffix))
    }

    public fun updateFamily(family: ch.trancee.meshlink.reference.model.TimelineFamily?): Unit {
        updateFilters(stateFlow.value.filters.copy(family = family))
    }

    public fun updateSeverity(
        severity: ch.trancee.meshlink.reference.model.TimelineSeverity?
    ): Unit {
        updateFilters(stateFlow.value.filters.copy(severity = severity))
    }

    public fun clearFilters(): Unit {
        updateFilters(TimelineFilters())
    }

    public fun retainCurrentSession(): Unit {
        scope.launch {
            val current = stateFlow.value
            val retainedSnapshot =
                current.currentSnapshot.copy(
                    session =
                        current.currentSnapshot.session.copy(
                            endedAtEpochMillis = platformServices.currentTimeMillis(),
                            historyStatus = ReferenceHistoryStatus.RETAINED,
                        )
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
                    current = stateFlow.value.retainedSessions,
                    loaded = historyRepository.loadRetainedSessions(),
                )
            val lastExportPath =
                if (
                    platformServices.automationConfig?.mode == ReferenceAutomationMode.SCRIPTED_UI
                ) {
                    writeExport(
                        snapshot = retainedSnapshot,
                        policy = ExportPayloadPolicy.REDACTED_PREVIEW,
                    )
                } else {
                    stateFlow.value.lastExportPath
                }
            updateState { state ->
                state.copy(retainedSessions = retainedSessions, lastExportPath = lastExportPath)
            }
        }
    }

    public fun openRetainedSession(sessionId: String): Unit {
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

    public fun returnToLive(): Unit {
        updateState { current ->
            current.copy(
                retainedSnapshot = null,
                visibleEntries = current.filters.apply(current.liveSnapshot.timeline),
            )
        }
    }

    public fun deleteRetainedSession(sessionId: String): Unit {
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
                updated.copy(
                    visibleEntries = updated.filters.apply(updated.currentSnapshot.timeline)
                )
            }
        }
    }

    public fun clearHistory(): Unit {
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

    public fun exportCurrentSession(policy: ExportPayloadPolicy): Unit {
        scope.launch {
            val storagePath =
                writeExport(snapshot = stateFlow.value.currentSnapshot, policy = policy)
            updateState { current -> current.copy(lastExportPath = storagePath) }
        }
    }

    private suspend fun writeExport(
        snapshot: ReferenceControllerSnapshot,
        policy: ExportPayloadPolicy,
    ): String {
        val serialized =
            if (policy == ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN) {
                artifactSerializer.serializeWithFullPayload(
                    snapshot.session,
                    snapshot.peers,
                    snapshot.timeline,
                )
            } else {
                artifactSerializer.serializeRedacted(
                    snapshot.session,
                    snapshot.peers,
                    snapshot.timeline,
                )
            }
        val artifact =
            SessionArtifact(
                artifactId = "artifact-${snapshot.session.sessionId}",
                sourceSessionId = snapshot.session.sessionId,
                createdAtEpochMillis = platformServices.currentTimeMillis(),
                payloadPolicy =
                    if (policy == ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN) {
                        ch.trancee.meshlink.reference.model.ArtifactPayloadPolicy.FULL_OPT_IN
                    } else {
                        ch.trancee.meshlink.reference.model.ArtifactPayloadPolicy.REDACTED_PREVIEW
                    },
                includesFullPayload = policy == ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN,
                scenarioSummary = snapshot.session.configurationSnapshot,
                peerSummaries =
                    snapshot.peers.map { peer ->
                        mapOf("peerSuffix" to peer.peerSuffix, "trustState" to peer.trustState.name)
                    },
                timelineEntries = snapshot.timeline,
                storagePath = "reference/exports/${snapshot.session.sessionId}.json",
            )
        return artifactSerializer.writeArtifact(artifact, serialized)
    }

    private fun updateFilters(filters: TimelineFilters): Unit {
        updateState { current ->
            current.copy(
                filters = filters,
                visibleEntries = filters.apply(current.currentSnapshot.timeline),
            )
        }
    }

    private fun visibleEntriesForUpdatedLiveSnapshot(
        current: TechnicalTimelineUiState,
        snapshot: ReferenceControllerSnapshot,
    ): List<TimelineEntry> {
        if (current.retainedSnapshot != null) {
            return current.visibleEntries
        }
        if (current.filters.isEmpty()) {
            return snapshot.timeline
        }
        return if (
            isSingleEntryAppend(
                previous = current.liveSnapshot.timeline,
                current = snapshot.timeline,
            )
        ) {
            val appendedEntry = snapshot.timeline.last()
            if (current.filters.matches(appendedEntry)) {
                current.visibleEntries + appendedEntry
            } else {
                current.visibleEntries
            }
        } else {
            current.filters.apply(snapshot.timeline)
        }
    }

    private fun isSingleEntryAppend(
        previous: List<TimelineEntry>,
        current: List<TimelineEntry>,
    ): Boolean {
        if (current.size != previous.size + 1) {
            return false
        }
        if (previous.isEmpty()) {
            return true
        }
        return current[current.lastIndex - 1].entryId == previous.last().entryId
    }

    private fun updateState(
        transform: (TechnicalTimelineUiState) -> TechnicalTimelineUiState
    ): Unit {
        stateFlow.update(transform)
    }

    private fun upsertRetainedSession(
        existing: List<ReferenceSession>,
        session: ReferenceSession,
    ): List<ReferenceSession> {
        return listOf(session) + existing.filterNot { item -> item.sessionId == session.sessionId }
    }

    private fun mergeRetainedSessions(
        current: List<ReferenceSession>,
        loaded: List<ReferenceSession>,
    ): List<ReferenceSession> {
        return current +
            loaded.filterNot { loadedSession ->
                current.any { currentSession ->
                    currentSession.sessionId == loadedSession.sessionId
                }
            }
    }
}

public data class TechnicalTimelineUiState(
    public val liveSnapshot: ReferenceControllerSnapshot,
    public val retainedSnapshot: ReferenceControllerSnapshot? = null,
    public val retainedSessions: List<ReferenceSession> = emptyList(),
    public val filters: TimelineFilters = TimelineFilters(),
    public val visibleEntries: List<TimelineEntry> = liveSnapshot.timeline,
    public val lastExportPath: String? = null,
) {
    public val currentSnapshot: ReferenceControllerSnapshot
        get() = retainedSnapshot ?: liveSnapshot

    public val viewingRetained: Boolean
        get() = retainedSnapshot != null
}
