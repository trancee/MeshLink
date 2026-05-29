package ch.trancee.meshlink.reference.navigation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.session.ReferenceSessionKind
import ch.trancee.meshlink.reference.session.referenceSessionKind

/**
 * Pure route-choice helper for the shared shell.
 *
 * Navigation owns the visible route. This helper only decides whether a surface change stays inside
 * the current session, needs boundary confirmation, or must start a new alternative session
 * immediately.
 */
internal sealed interface SessionSurfaceChoice {
    data class SelectSurface(val surface: ReferenceSurface) : SessionSurfaceChoice

    data class RequireBoundaryConfirmation(val request: SessionBoundaryRequest) :
        SessionSurfaceChoice

    data class StartAlternativeSession(val surface: ReferenceSurface) : SessionSurfaceChoice
}

internal fun chooseSessionSurfaceChoice(
    currentKind: ReferenceSessionKind,
    activeRoute: ReferenceSurface,
    targetSurface: ReferenceSurface,
): SessionSurfaceChoice {
    return when {
        currentKind == ReferenceSessionKind.SUPPORTED_LIVE &&
            targetSurface == ReferenceSurface.SOLO_EXPLORATION ->
            SessionSurfaceChoice.RequireBoundaryConfirmation(
                SessionBoundaryRequest.LeaveSupportedSession(targetSurface)
            )

        currentKind == ReferenceSessionKind.SUPPORTED_LIVE &&
            targetSurface == ReferenceSurface.LAB ->
            SessionSurfaceChoice.RequireBoundaryConfirmation(
                SessionBoundaryRequest.LeaveSupportedSession(targetSurface)
            )

        currentKind == ReferenceSessionKind.SOLO || currentKind == ReferenceSessionKind.LAB ->
            when (targetSurface) {
                ReferenceSurface.MAIN_GUIDED,
                ReferenceSurface.ADVANCED_CONTROLS,
                ReferenceSurface.SOLO_EXPLORATION,
                ReferenceSurface.LAB -> {
                    if (targetSurface != activeRoute) {
                        SessionSurfaceChoice.RequireBoundaryConfirmation(
                            SessionBoundaryRequest.LeaveAlternativeSession(targetSurface)
                        )
                    } else {
                        SessionSurfaceChoice.SelectSurface(targetSurface)
                    }
                }

                else -> SessionSurfaceChoice.SelectSurface(targetSurface)
            }

        currentKind == ReferenceSessionKind.SUPPORTED_ENDED &&
            targetSurface == ReferenceSurface.SOLO_EXPLORATION ->
            SessionSurfaceChoice.StartAlternativeSession(targetSurface)

        currentKind == ReferenceSessionKind.SUPPORTED_ENDED &&
            targetSurface == ReferenceSurface.LAB ->
            SessionSurfaceChoice.StartAlternativeSession(targetSurface)

        else -> SessionSurfaceChoice.SelectSurface(targetSurface)
    }
}

internal fun chooseSessionSurfaceChoice(
    currentSnapshot: ReferenceControllerSnapshot,
    activeRoute: ReferenceSurface,
    targetSurface: ReferenceSurface,
): SessionSurfaceChoice {
    return chooseSessionSurfaceChoice(
        currentKind = currentSnapshot.referenceSessionKind(),
        activeRoute = activeRoute,
        targetSurface = targetSurface,
    )
}

internal fun followUpSupportedEntrySurface(
    currentSnapshot: ReferenceControllerSnapshot
): ReferenceSurface {
    return when (currentSnapshot.session.configurationSnapshot["surface"]) {
        ReferenceSurface.ADVANCED_CONTROLS.route -> ReferenceSurface.ADVANCED_CONTROLS
        else -> ReferenceSurface.MAIN_GUIDED
    }
}

internal fun followUpSupportedSessionLabel(currentSnapshot: ReferenceControllerSnapshot): String {
    return when (followUpSupportedEntrySurface(currentSnapshot)) {
        ReferenceSurface.ADVANCED_CONTROLS -> "Start new advanced session"
        else -> "Start new guided session"
    }
}
