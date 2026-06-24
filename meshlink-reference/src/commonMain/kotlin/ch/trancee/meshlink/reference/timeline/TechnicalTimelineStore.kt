package ch.trancee.meshlink.reference.timeline

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.ArtifactPayloadPolicy
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.SessionArtifact
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import ch.trancee.meshlink.reference.model.referenceAuthorityLabel
import ch.trancee.meshlink.reference.model.referenceConnectionLabel
import ch.trancee.meshlink.reference.model.referenceOutcomeLabel
import ch.trancee.meshlink.reference.model.referencePeerTrustLabel
import ch.trancee.meshlink.reference.model.referenceScenarioTitle
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import ch.trancee.meshlink.reference.session.JsonSessionArtifactSerializer
import ch.trancee.meshlink.reference.session.JsonSessionHistoryRepository
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

/**
 * Evidence-state module for the technical timeline and recent-history surfaces.
 *
 * The store owns which evidence snapshot is visible, which retained sessions are listed, how
 * filters affect visible entries, and which export path was created most recently. Session creation
 * and replacement live elsewhere.
 */
internal class TechnicalTimelineStore(
    private val platformName: String,
    private val readinessBlockers: List<String>,
    private val meshLinkController: ReferenceMeshLinkController,
    private val currentTimeMillis: () -> Long,
    private val historyRepository: JsonSessionHistoryRepository,
    private val artifactSerializer: JsonSessionArtifactSerializer,
    internal val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val stateFlow: MutableStateFlow<TechnicalTimelineUiState> =
        MutableStateFlow(TechnicalTimelineUiState(liveSnapshot = meshLinkController.snapshot.value))

    val uiState: StateFlow<TechnicalTimelineUiState> = stateFlow.asStateFlow()

    init {
        loadRetainedSessionList()
        observeLiveSnapshot()
    }

    fun setSearchText(text: String): Unit {
        applyFilters(uiState.value.filters.copy(searchText = text))
    }

    fun setPeerFilter(peerSuffix: String?): Unit {
        applyFilters(uiState.value.filters.copy(peerSuffix = peerSuffix))
    }

    fun setFamilyFilter(family: TimelineFamily?): Unit {
        applyFilters(uiState.value.filters.copy(family = family))
    }

    fun setSeverityFilter(severity: TimelineSeverity?): Unit {
        applyFilters(uiState.value.filters.copy(severity = severity))
    }

    fun clearFilters(): Unit {
        applyFilters(TimelineFilters())
    }

    fun openRetainedSession(sessionId: String): Unit {
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

    fun openLiveSession(): Unit {
        updateState { current ->
            current.copy(
                retainedSnapshot = null,
                visibleEntries = current.filters.apply(current.liveSnapshot.timeline),
            )
        }
    }

    fun deleteRetainedSession(sessionId: String): Unit {
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

    fun clearRetainedSessions(): Unit {
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

    fun exportVisibleSession(policy: ExportPayloadPolicy): Unit {
        scope.launch {
            val currentSnapshot = uiState.value.currentSnapshot
            val storagePath =
                exportSnapshot(currentSnapshot, normalizeExportPolicy(currentSnapshot, policy))
            updateState { current -> current.copy(lastExportPath = storagePath) }
        }
    }

    internal fun publishLiveSnapshot(liveSnapshot: ReferenceControllerSnapshot): Unit {
        updateState { current ->
            current.copy(
                liveSnapshot = liveSnapshot,
                retainedSnapshot = null,
                visibleEntries = current.filters.apply(liveSnapshot.timeline),
            )
        }
    }

    internal suspend fun refreshRetainedSessionList(lastExportPath: String?): Unit {
        val retainedSessions = historyRepository.loadRetainedSessions()
        updateState { current ->
            current.copy(
                retainedSessions = retainedSessions,
                lastExportPath = lastExportPath ?: current.lastExportPath,
                visibleEntries = current.filters.apply(current.currentSnapshot.timeline),
            )
        }
    }

    internal suspend fun retainEndedSnapshotIfEligible(
        endedSnapshot: ReferenceControllerSnapshot
    ): Unit {
        if (!endedSnapshot.isEligibleForAutomaticRetention(readinessBlockers)) {
            return
        }
        historyRepository.retainSnapshot(endedSnapshot.redactedRetainedSnapshot())
    }

    internal suspend fun exportSnapshot(
        snapshot: ReferenceControllerSnapshot,
        policy: ExportPayloadPolicy,
    ): String {
        val createdAtEpochMillis = currentTimeMillis()
        val artifactPolicy =
            if (policy == ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN) {
                ArtifactPayloadPolicy.FULL_OPT_IN
            } else {
                ArtifactPayloadPolicy.REDACTED_PREVIEW
            }
        val artifactSuffix =
            if (policy == ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN) {
                "full"
            } else {
                "redacted"
            }
        val artifact =
            SessionArtifact(
                artifactId =
                    "artifact-${snapshot.session.sessionId}-$createdAtEpochMillis-$artifactSuffix",
                sourceSessionId = snapshot.session.sessionId,
                createdAtEpochMillis = createdAtEpochMillis,
                payloadPolicy = artifactPolicy,
                includesFullPayload =
                    policy == ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN &&
                        snapshot.timeline.any { entry -> entry.fullPayload != null },
                scenarioSummary =
                    mapOf(
                        "scenarioId" to snapshot.session.scenarioId,
                        "title" to referenceScenarioTitle(snapshot.session.scenarioId),
                        "surface" to
                            (snapshot.session.configurationSnapshot["surface"] ?: "main-guided"),
                        "authorityMode" to referenceAuthorityLabel(snapshot.session.authorityMode),
                    ),
                peerSummaries =
                    snapshot.peers.map { peer ->
                        buildMap {
                            put("peerSuffix", peer.peerSuffix)
                            put("trustState", referencePeerTrustLabel(peer.trustState))
                            put("connectionState", referenceConnectionLabel(peer.connectionState))
                            peer.lastDeliveryOutcome?.let { outcome ->
                                put(
                                    "lastDeliveryOutcome",
                                    referenceOutcomeLabel(outcome) ?: outcome,
                                )
                            }
                        }
                    },
                timelineEntries = snapshot.timeline,
                storagePath =
                    "reference/exports/${snapshot.session.sessionId}-$createdAtEpochMillis-$artifactSuffix.json",
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

    internal fun updateState(
        transform: (TechnicalTimelineUiState) -> TechnicalTimelineUiState
    ): Unit {
        stateFlow.update(transform)
    }

    private fun loadRetainedSessionList(): Unit {
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
    }

    private fun observeLiveSnapshot(): Unit {
        scope.launch {
            meshLinkController.snapshot.collectLatest { snapshot ->
                updateState { current ->
                    current.copy(
                        liveSnapshot = snapshot,
                        visibleEntries = visibleEntriesForUpdatedLiveSnapshot(current, snapshot),
                    )
                }
            }
        }
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

internal data class TechnicalTimelineUiState(
    val liveSnapshot: ReferenceControllerSnapshot,
    val retainedSnapshot: ReferenceControllerSnapshot? = null,
    val retainedSessions: List<ReferenceSession> = emptyList(),
    val filters: TimelineFilters = TimelineFilters(),
    val visibleEntries: List<TimelineEntry> = liveSnapshot.timeline,
    val lastExportPath: String? = null,
) {
    val currentSessionKind: ReferenceSessionKind
        get() = currentSnapshot.referenceSessionKind()

    val isCurrentSessionEnded: Boolean
        get() = !viewingRetained && currentSessionKind == ReferenceSessionKind.SUPPORTED_ENDED

    val isSupportedLiveSession: Boolean
        get() = currentSessionKind == ReferenceSessionKind.SUPPORTED_LIVE

    val isAlternativeSession: Boolean
        get() =
            currentSessionKind == ReferenceSessionKind.SOLO ||
                currentSessionKind == ReferenceSessionKind.LAB

    val canExportFullPayload: Boolean
        get() = isSupportedLiveSession && !viewingRetained

    val shouldShowStartNewSession: Boolean
        get() = isCurrentSessionEnded || isAlternativeSession

    val currentSnapshot: ReferenceControllerSnapshot
        get() = retainedSnapshot ?: liveSnapshot

    val viewingRetained: Boolean
        get() = retainedSnapshot != null
}
