package ch.trancee.meshlink.reference.timeline

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.platform.PlatformServices
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

    internal fun updateState(
        transform: (TechnicalTimelineUiState) -> TechnicalTimelineUiState
    ): Unit {
        stateFlow.update(transform)
    }
}

private fun visibleEntriesForUpdatedLiveSnapshot(
    current: TechnicalTimelineUiState,
    snapshot: ReferenceControllerSnapshot,
): List<TimelineEntry> {
    return when {
        current.retainedSnapshot != null -> current.visibleEntries
        current.filters.isEmpty() -> snapshot.timeline
        isSingleEntryAppend(
            previous = current.liveSnapshot.timeline,
            current = snapshot.timeline,
        ) -> appendedVisibleEntries(current = current, snapshot = snapshot)
        else -> current.filters.apply(snapshot.timeline)
    }
}

private fun appendedVisibleEntries(
    current: TechnicalTimelineUiState,
    snapshot: ReferenceControllerSnapshot,
): List<TimelineEntry> {
    val appendedEntry = snapshot.timeline.last()
    return if (current.filters.matches(appendedEntry)) {
        current.visibleEntries + appendedEntry
    } else {
        current.visibleEntries
    }
}

private fun isSingleEntryAppend(
    previous: List<TimelineEntry>,
    current: List<TimelineEntry>,
): Boolean {
    val expectedSize = previous.size + 1
    val matchesPreviousTail =
        previous.isEmpty() || current[current.lastIndex - 1].entryId == previous.last().entryId
    return current.size == expectedSize && matchesPreviousTail
}

internal fun mergeRetainedSessions(
    current: List<ReferenceSession>,
    loaded: List<ReferenceSession>,
): List<ReferenceSession> {
    return current +
        loaded.filterNot { loadedSession ->
            current.any { currentSession -> currentSession.sessionId == loadedSession.sessionId }
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

    public val allowFullPayloadExport: Boolean
        get() = isSupportedLiveSession && !viewingRetained

    public val showStartNewSession: Boolean
        get() = isCurrentSessionEnded || isAlternativeSession

    public val currentSnapshot: ReferenceControllerSnapshot
        get() = retainedSnapshot ?: liveSnapshot

    public val viewingRetained: Boolean
        get() = retainedSnapshot != null
}
