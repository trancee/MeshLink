package ch.trancee.meshlink.reference.timeline

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.ArtifactPayloadPolicy
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.SessionArtifact
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import ch.trancee.meshlink.reference.model.referenceAuthorityLabel
import ch.trancee.meshlink.reference.model.referenceConnectionLabel
import ch.trancee.meshlink.reference.model.referenceOutcomeLabel
import ch.trancee.meshlink.reference.model.referencePeerTrustLabel
import ch.trancee.meshlink.reference.model.referenceScenarioTitle
import ch.trancee.meshlink.reference.model.withoutSensitivePayload
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import ch.trancee.meshlink.reference.session.ReferenceSessionKind
import ch.trancee.meshlink.reference.session.allowsFullPayloadExport
import ch.trancee.meshlink.reference.session.referenceSessionKind
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

private suspend fun TechnicalTimelineStore.writeExport(
    snapshot: ReferenceControllerSnapshot,
    policy: ExportPayloadPolicy,
): String {
    val createdAtEpochMillis = platformServices.currentTimeMillis()
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
                            put("lastDeliveryOutcome", referenceOutcomeLabel(outcome) ?: outcome)
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

private suspend fun TechnicalTimelineStore.refreshRetainedSessions(lastExportPath: String?): Unit {
    val retainedSessions = historyRepository.loadRetainedSessions()
    updateState { current ->
        current.copy(
            retainedSessions = retainedSessions,
            lastExportPath = lastExportPath ?: current.lastExportPath,
            visibleEntries = current.filters.apply(current.currentSnapshot.timeline),
        )
    }
}

private suspend fun TechnicalTimelineStore.retainIfEligible(
    endedSnapshot: ReferenceControllerSnapshot
): Unit {
    if (!endedSnapshot.isEligibleForAutomaticRetention(platformServices.readinessBlockers)) {
        return
    }
    historyRepository.retainSnapshot(endedSnapshot.redactedRetainedSnapshot())
}

private fun normalizeExportPolicy(
    snapshot: ReferenceControllerSnapshot,
    requestedPolicy: ExportPayloadPolicy,
): ExportPayloadPolicy {
    return if (snapshot.allowsFullPayloadExport()) {
        requestedPolicy
    } else {
        ExportPayloadPolicy.REDACTED_PREVIEW
    }
}

private fun endedBoundarySnapshot(
    snapshot: ReferenceControllerSnapshot,
    endedAtEpochMillis: Long,
): ReferenceControllerSnapshot {
    return snapshot.copy(session = snapshot.session.copy(endedAtEpochMillis = endedAtEpochMillis))
}

private fun ReferenceControllerSnapshot.redactedRetainedSnapshot(): ReferenceControllerSnapshot {
    return copy(
        session = session.copy(historyStatus = ReferenceHistoryStatus.RETAINED),
        timeline = timeline.map { entry -> entry.withoutSensitivePayload() },
    )
}

private fun ReferenceControllerSnapshot.isEligibleForAutomaticRetention(
    readinessBlockers: List<String>
): Boolean {
    if (session.authorityMode != ReferenceAuthorityMode.LIVE) {
        return false
    }
    if (referenceSessionKind() == ReferenceSessionKind.LAB) {
        return false
    }
    if (readinessBlockers.isNotEmpty()) {
        return true
    }
    return timeline.any { entry -> entry.isReviewableEvidenceEntry() }
}

private fun TimelineEntry.isReviewableEvidenceEntry(): Boolean {
    if (title == "Reference session created" || title == "Automation session created") {
        return false
    }
    return family != TimelineFamily.USER || severity != TimelineSeverity.INFO
}
