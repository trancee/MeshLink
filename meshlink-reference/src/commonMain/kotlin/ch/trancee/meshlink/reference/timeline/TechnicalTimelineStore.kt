package ch.trancee.meshlink.reference.timeline

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import ch.trancee.meshlink.reference.session.JsonSessionArtifactSerializer
import ch.trancee.meshlink.reference.session.JsonSessionHistoryRepository
import ch.trancee.meshlink.reference.session.ReferenceSessionController
import ch.trancee.meshlink.reference.session.ReferenceSessionKind
import ch.trancee.meshlink.reference.session.referenceSessionKind
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
    internal val platformServices: PlatformServices,
    internal val historyRepository: JsonSessionHistoryRepository,
    internal val artifactSerializer: JsonSessionArtifactSerializer,
    internal val sessionController: ReferenceSessionController,
    internal val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    internal val stateFlow: MutableStateFlow<TechnicalTimelineUiState> =
        MutableStateFlow(
            TechnicalTimelineUiState(
                liveSnapshot = platformServices.meshLinkController.snapshot.value
            )
        )

    public val uiState: StateFlow<TechnicalTimelineUiState> = stateFlow.asStateFlow()

    init {
        scope.launch {
            val retainedSessions = historyRepository.loadRetainedSessions()
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

    public fun setSearchText(text: String): Unit {
        applyFilters(uiState.value.filters.copy(searchText = text))
    }

    public fun setPeerFilter(peerSuffix: String?): Unit {
        applyFilters(uiState.value.filters.copy(peerSuffix = peerSuffix))
    }

    public fun setFamilyFilter(family: TimelineFamily?): Unit {
        applyFilters(uiState.value.filters.copy(family = family))
    }

    public fun setSeverityFilter(severity: TimelineSeverity?): Unit {
        applyFilters(uiState.value.filters.copy(severity = severity))
    }

    public fun clearFilters(): Unit {
        applyFilters(TimelineFilters())
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

    public fun openLiveSession(): Unit {
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

    public fun clearRetainedSessions(): Unit {
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

    public fun exportVisibleSession(policy: ExportPayloadPolicy): Unit {
        scope.launch {
            val currentSnapshot = uiState.value.currentSnapshot
            val storagePath =
                writeExport(currentSnapshot, normalizeExportPolicy(currentSnapshot, policy))
            updateState { current -> current.copy(lastExportPath = storagePath) }
        }
    }

    internal fun updateState(
        transform: (TechnicalTimelineUiState) -> TechnicalTimelineUiState
    ): Unit {
        stateFlow.update(transform)
    }

    private fun applyFilters(filters: TimelineFilters): Unit {
        updateState { current ->
            current.copy(
                filters = filters,
                visibleEntries = filters.apply(current.currentSnapshot.timeline),
            )
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
    public val currentSessionKind: ReferenceSessionKind
        get() = currentSnapshot.referenceSessionKind()

    public val isCurrentSessionEnded: Boolean
        get() = !viewingRetained && currentSessionKind == ReferenceSessionKind.SUPPORTED_ENDED

    public val isSupportedLiveSession: Boolean
        get() = currentSessionKind == ReferenceSessionKind.SUPPORTED_LIVE

    public val isAlternativeSession: Boolean
        get() =
            currentSessionKind == ReferenceSessionKind.SOLO ||
                currentSessionKind == ReferenceSessionKind.LAB

    public val canExportFullPayload: Boolean
        get() = isSupportedLiveSession && !viewingRetained

    public val shouldShowStartNewSession: Boolean
        get() = isCurrentSessionEnded || isAlternativeSession

    public val currentSnapshot: ReferenceControllerSnapshot
        get() = retainedSnapshot ?: liveSnapshot

    public val viewingRetained: Boolean
        get() = retainedSnapshot != null
}
