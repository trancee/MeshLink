package ch.trancee.meshlink.reference.navigation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import ch.trancee.meshlink.reference.session.ReferenceSessionKind
import ch.trancee.meshlink.reference.session.referenceSessionKind
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineStore
import ch.trancee.meshlink.reference.timeline.endedBoundarySnapshot
import ch.trancee.meshlink.reference.timeline.normalizeExportPolicy
import ch.trancee.meshlink.reference.timeline.refreshRetainedSessions
import ch.trancee.meshlink.reference.timeline.retainIfEligible
import ch.trancee.meshlink.reference.timeline.syncLiveSnapshot
import ch.trancee.meshlink.reference.timeline.writeExport

internal sealed interface SessionSurfaceChoice {
    data class Select(val surface: ReferenceSurfaceId) : SessionSurfaceChoice

    data class RequireBoundary(val request: SessionBoundaryRequest) : SessionSurfaceChoice

    data class StartAlternative(val surface: ReferenceSurfaceId) : SessionSurfaceChoice
}

internal class SessionTransitionService(private val timelineStore: TechnicalTimelineStore) {
    fun chooseSurface(
        activeRoute: ReferenceSurfaceId,
        currentSnapshot: ReferenceControllerSnapshot,
        targetSurface: ReferenceSurfaceId,
    ): SessionSurfaceChoice {
        return chooseSessionSurfaceChoice(
            currentKind = currentSnapshot.referenceSessionKind(),
            activeRoute = activeRoute,
            targetSurface = targetSurface,
        )
    }

    fun followUpSupportedSessionLabel(currentSnapshot: ReferenceControllerSnapshot): String {
        return when (followUpSupportedEntrySurface(currentSnapshot)) {
            ReferenceSurfaceId.ADVANCED_CONTROLS -> "Start new advanced session"
            else -> "Start new guided session"
        }
    }

    suspend fun startAlternativeSession(
        surface: ReferenceSurfaceId,
        applySurfaceSelection: (ReferenceSurfaceId) -> Unit,
    ): Unit {
        val snapshot =
            when (surface) {
                ReferenceSurfaceId.SOLO_EXPLORATION ->
                    timelineStore.sessionController.startSoloSession()
                ReferenceSurfaceId.LAB -> timelineStore.sessionController.startLabSession()
                else -> return
            }
        timelineStore.syncLiveSnapshot(snapshot)
        applySurfaceSelection(surface)
    }

    suspend fun startFollowUpSupportedSession(
        currentSnapshot: ReferenceControllerSnapshot,
        applySurfaceSelection: (ReferenceSurfaceId) -> Unit,
    ): Unit {
        val targetSurface = followUpSupportedEntrySurface(currentSnapshot)
        applySurfaceSelection(targetSurface)
        startSupportedSession(targetSurface.route)
    }

    suspend fun startSupportedSession(
        surfaceOfOrigin: String = ReferenceSurfaceId.MAIN_GUIDED.route
    ): Unit {
        val snapshot = timelineStore.sessionController.startNewSupportedSession(surfaceOfOrigin)
        timelineStore.syncLiveSnapshot(snapshot)
    }

    suspend fun endSupportedSession(preEndExportPolicy: ExportPayloadPolicy? = null): Unit {
        val current = timelineStore.uiState.value
        if (!current.isSupportedLiveSession || current.viewingRetained) {
            return
        }

        val snapshotBeforeEnd = current.liveSnapshot
        val exportPath =
            preEndExportPolicy?.let { policy ->
                timelineStore.writeExport(
                    snapshotBeforeEnd,
                    normalizeExportPolicy(snapshotBeforeEnd, policy),
                )
            } ?: current.lastExportPath
        val endedSnapshot = timelineStore.sessionController.endSupportedSession()
        timelineStore.syncLiveSnapshot(endedSnapshot)
        timelineStore.retainIfEligible(endedSnapshot)
        timelineStore.refreshRetainedSessions(lastExportPath = exportPath)
    }

    suspend fun transitionSupportedSession(
        targetSurface: ReferenceSurfaceId,
        preBoundaryExportPolicy: ExportPayloadPolicy? = null,
    ): Unit {
        val current = timelineStore.uiState.value
        if (!current.isSupportedLiveSession || current.viewingRetained) {
            return
        }

        val supportedSnapshot = current.liveSnapshot
        val exportPath =
            preBoundaryExportPolicy?.let { policy ->
                timelineStore.writeExport(
                    supportedSnapshot,
                    normalizeExportPolicy(supportedSnapshot, policy),
                )
            } ?: current.lastExportPath
        timelineStore.retainIfEligible(
            endedBoundarySnapshot(
                supportedSnapshot,
                timelineStore.platformServices.currentTimeMillis(),
            )
        )
        val snapshot =
            when (targetSurface) {
                ReferenceSurfaceId.SOLO_EXPLORATION ->
                    timelineStore.sessionController.startSoloSession()
                ReferenceSurfaceId.LAB -> timelineStore.sessionController.startLabSession()
                else -> return
            }
        timelineStore.syncLiveSnapshot(snapshot)
        timelineStore.refreshRetainedSessions(lastExportPath = exportPath)
    }

    suspend fun transitionAlternativeSession(
        targetSurface: ReferenceSurfaceId,
        exportBeforeExit: Boolean,
    ): Unit {
        val current = timelineStore.uiState.value
        if (!current.isAlternativeSession || current.viewingRetained) {
            return
        }

        val exportPath =
            if (exportBeforeExit) {
                timelineStore.writeExport(
                    current.liveSnapshot,
                    ExportPayloadPolicy.REDACTED_PREVIEW,
                )
            } else {
                current.lastExportPath
            }
        when (targetSurface) {
            ReferenceSurfaceId.SOLO_EXPLORATION ->
                timelineStore.syncLiveSnapshot(timelineStore.sessionController.startSoloSession())
            ReferenceSurfaceId.LAB ->
                timelineStore.syncLiveSnapshot(timelineStore.sessionController.startLabSession())
            ReferenceSurfaceId.ADVANCED_CONTROLS ->
                startSupportedSession(ReferenceSurfaceId.ADVANCED_CONTROLS.route)
            else -> startSupportedSession(ReferenceSurfaceId.MAIN_GUIDED.route)
        }
        timelineStore.refreshRetainedSessions(lastExportPath = exportPath)
    }

    suspend fun confirmBoundary(
        request: SessionBoundaryRequest,
        exportFirst: Boolean,
        applySurfaceSelection: (ReferenceSurfaceId) -> Unit,
    ): Unit {
        when (request) {
            is SessionBoundaryRequest.SupportedTo -> {
                applySurfaceSelection(request.targetSurface)
                transitionSupportedSession(
                    targetSurface = request.targetSurface,
                    preBoundaryExportPolicy = supportedBoundaryExportPolicy(exportFirst),
                )
            }

            is SessionBoundaryRequest.AlternativeTo -> {
                applySurfaceSelection(request.targetSurface)
                transitionAlternativeSession(
                    targetSurface = request.targetSurface,
                    exportBeforeExit = exportFirst,
                )
            }
        }
    }
}

internal fun chooseSessionSurfaceChoice(
    currentKind: ReferenceSessionKind,
    activeRoute: ReferenceSurfaceId,
    targetSurface: ReferenceSurfaceId,
): SessionSurfaceChoice {
    return when {
        currentKind == ReferenceSessionKind.SUPPORTED_LIVE &&
            targetSurface == ReferenceSurfaceId.SOLO_EXPLORATION ->
            SessionSurfaceChoice.RequireBoundary(SessionBoundaryRequest.SupportedTo(targetSurface))

        currentKind == ReferenceSessionKind.SUPPORTED_LIVE &&
            targetSurface == ReferenceSurfaceId.LAB ->
            SessionSurfaceChoice.RequireBoundary(SessionBoundaryRequest.SupportedTo(targetSurface))

        currentKind == ReferenceSessionKind.SOLO || currentKind == ReferenceSessionKind.LAB ->
            when (targetSurface) {
                ReferenceSurfaceId.MAIN_GUIDED,
                ReferenceSurfaceId.ADVANCED_CONTROLS,
                ReferenceSurfaceId.SOLO_EXPLORATION,
                ReferenceSurfaceId.LAB -> {
                    if (targetSurface != activeRoute) {
                        SessionSurfaceChoice.RequireBoundary(
                            SessionBoundaryRequest.AlternativeTo(targetSurface)
                        )
                    } else {
                        SessionSurfaceChoice.Select(targetSurface)
                    }
                }

                else -> SessionSurfaceChoice.Select(targetSurface)
            }

        currentKind == ReferenceSessionKind.SUPPORTED_ENDED &&
            targetSurface == ReferenceSurfaceId.SOLO_EXPLORATION ->
            SessionSurfaceChoice.StartAlternative(targetSurface)

        currentKind == ReferenceSessionKind.SUPPORTED_ENDED &&
            targetSurface == ReferenceSurfaceId.LAB ->
            SessionSurfaceChoice.StartAlternative(targetSurface)

        else -> SessionSurfaceChoice.Select(targetSurface)
    }
}

internal fun followUpSupportedEntrySurface(
    currentSnapshot: ReferenceControllerSnapshot
): ReferenceSurfaceId {
    return when (currentSnapshot.session.configurationSnapshot["surface"]) {
        ReferenceSurfaceId.ADVANCED_CONTROLS.route -> ReferenceSurfaceId.ADVANCED_CONTROLS
        else -> ReferenceSurfaceId.MAIN_GUIDED
    }
}

private fun supportedBoundaryExportPolicy(exportFirst: Boolean): ExportPayloadPolicy? {
    return if (exportFirst) {
        ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN
    } else {
        null
    }
}
