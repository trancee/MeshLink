package ch.trancee.meshlink.reference.navigation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import ch.trancee.meshlink.reference.session.ReferenceSessionKind
import ch.trancee.meshlink.reference.session.referenceSessionKind
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineStore
import ch.trancee.meshlink.reference.timeline.endCurrentSessionNow
import ch.trancee.meshlink.reference.timeline.startNewSupportedSessionNow
import ch.trancee.meshlink.reference.timeline.syncLiveSnapshot
import ch.trancee.meshlink.reference.timeline.transitionAlternativeSessionNow
import ch.trancee.meshlink.reference.timeline.transitionToLabSessionNow
import ch.trancee.meshlink.reference.timeline.transitionToSoloSessionNow

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
        timelineStore.startNewSupportedSessionNow(surfaceOfOrigin = targetSurface.route)
    }

    suspend fun endSupportedSession(preEndExportPolicy: ExportPayloadPolicy? = null): Unit {
        timelineStore.endCurrentSessionNow(preEndExportPolicy)
    }

    suspend fun confirmBoundary(
        request: SessionBoundaryRequest,
        exportFirst: Boolean,
        applySurfaceSelection: (ReferenceSurfaceId) -> Unit,
    ): Unit {
        when (request) {
            is SessionBoundaryRequest.SupportedTo -> {
                applySurfaceSelection(request.targetSurface)
                when (request.targetSurface) {
                    ReferenceSurfaceId.SOLO_EXPLORATION ->
                        timelineStore.transitionToSoloSessionNow(
                            preBoundaryExportPolicy = supportedBoundaryExportPolicy(exportFirst)
                        )
                    ReferenceSurfaceId.LAB ->
                        timelineStore.transitionToLabSessionNow(
                            preBoundaryExportPolicy = supportedBoundaryExportPolicy(exportFirst)
                        )
                    else -> Unit
                }
            }

            is SessionBoundaryRequest.AlternativeTo -> {
                applySurfaceSelection(request.targetSurface)
                timelineStore.transitionAlternativeSessionNow(
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
