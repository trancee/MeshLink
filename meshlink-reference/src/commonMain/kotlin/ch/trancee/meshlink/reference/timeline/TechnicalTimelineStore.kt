package ch.trancee.meshlink.reference.timeline

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
                stateFlow.value =
                    stateFlow.value.copy(
                        retainedSessions =
                            mergeRetainedSessions(
                                current = stateFlow.value.retainedSessions,
                                loaded = retainedSessions,
                            )
                    )
            }
        }
        scope.launch {
            platformServices.meshLinkController.snapshot.collectLatest { snapshot ->
                stateFlow.value =
                    stateFlow.value.copy(
                        liveSnapshot = snapshot,
                        visibleEntries = stateFlow.value.filters.apply(snapshot.timeline),
                    )
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
            val snapshot = stateFlow.value.currentSnapshot
            val retainedSnapshot =
                snapshot.copy(
                    session =
                        snapshot.session.copy(
                            endedAtEpochMillis = platformServices.currentTimeMillis(),
                            historyStatus = ReferenceHistoryStatus.RETAINED,
                        )
                )
            stateFlow.value =
                stateFlow.value.copy(
                    retainedSessions =
                        upsertRetainedSession(
                            existing = stateFlow.value.retainedSessions,
                            session = retainedSnapshot.session,
                        )
                )
            historyRepository.retainSnapshot(retainedSnapshot)
            stateFlow.value =
                stateFlow.value.copy(
                    retainedSessions =
                        mergeRetainedSessions(
                            current = stateFlow.value.retainedSessions,
                            loaded = historyRepository.loadRetainedSessions(),
                        )
                )
        }
    }

    public fun openRetainedSession(sessionId: String): Unit {
        scope.launch {
            val retained = historyRepository.loadRetainedSnapshot(sessionId) ?: return@launch
            stateFlow.value =
                stateFlow.value.copy(
                    retainedSnapshot = retained,
                    visibleEntries = stateFlow.value.filters.apply(retained.timeline),
                )
        }
    }

    public fun returnToLive(): Unit {
        stateFlow.value =
            stateFlow.value.copy(
                retainedSnapshot = null,
                visibleEntries =
                    stateFlow.value.filters.apply(stateFlow.value.liveSnapshot.timeline),
            )
    }

    public fun deleteRetainedSession(sessionId: String): Unit {
        scope.launch {
            historyRepository.deleteSession(sessionId)
            val retainedSessions = historyRepository.loadRetainedSessions()
            stateFlow.value =
                stateFlow.value.copy(
                    retainedSessions = retainedSessions,
                    retainedSnapshot =
                        stateFlow.value.retainedSnapshot?.takeUnless { snapshot ->
                            snapshot.session.sessionId == sessionId
                        },
                    visibleEntries =
                        stateFlow.value.filters.apply(stateFlow.value.currentSnapshot.timeline),
                )
        }
    }

    public fun clearHistory(): Unit {
        scope.launch {
            historyRepository.clearAll()
            stateFlow.value =
                stateFlow.value.copy(
                    retainedSessions = emptyList(),
                    retainedSnapshot = null,
                    visibleEntries =
                        stateFlow.value.filters.apply(stateFlow.value.liveSnapshot.timeline),
                )
        }
    }

    public fun exportCurrentSession(policy: ExportPayloadPolicy): Unit {
        scope.launch {
            val snapshot = stateFlow.value.currentSnapshot
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
                            ch.trancee.meshlink.reference.model.ArtifactPayloadPolicy
                                .REDACTED_PREVIEW
                        },
                    includesFullPayload = policy == ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN,
                    scenarioSummary = snapshot.session.configurationSnapshot,
                    peerSummaries =
                        snapshot.peers.map { peer ->
                            mapOf(
                                "peerSuffix" to peer.peerSuffix,
                                "trustState" to peer.trustState.name,
                            )
                        },
                    timelineEntries = snapshot.timeline,
                    storagePath = "reference/exports/${snapshot.session.sessionId}.json",
                )
            val storagePath = artifactSerializer.writeArtifact(artifact, serialized)
            stateFlow.value = stateFlow.value.copy(lastExportPath = storagePath)
        }
    }

    private fun updateFilters(filters: TimelineFilters): Unit {
        val currentSnapshot = stateFlow.value.currentSnapshot
        stateFlow.value =
            stateFlow.value.copy(
                filters = filters,
                visibleEntries = filters.apply(currentSnapshot.timeline),
            )
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
