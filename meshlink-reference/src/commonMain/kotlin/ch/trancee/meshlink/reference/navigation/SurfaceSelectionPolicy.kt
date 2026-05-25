package ch.trancee.meshlink.reference.navigation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.session.ReferenceSessionKind
import ch.trancee.meshlink.reference.session.referenceSessionKind

internal sealed interface SurfaceSelectionAction {
    data class Select(val surface: ReferenceSurface) : SurfaceSelectionAction

    data class RequireBoundary(val request: SessionBoundaryRequest) : SurfaceSelectionAction

    data class StartAlternativeSession(val surface: ReferenceSurface) : SurfaceSelectionAction
}

internal fun determineSurfaceSelectionAction(
    currentKind: ReferenceSessionKind,
    activeRoute: ReferenceSurface,
    targetSurface: ReferenceSurface,
): SurfaceSelectionAction {
    return when {
        currentKind == ReferenceSessionKind.SUPPORTED_LIVE &&
            targetSurface == ReferenceSurface.SOLO_EXPLORATION ->
            SurfaceSelectionAction.RequireBoundary(
                SessionBoundaryRequest.LeaveSupportedSession(targetSurface)
            )

        currentKind == ReferenceSessionKind.SUPPORTED_LIVE &&
            targetSurface == ReferenceSurface.LAB ->
            SurfaceSelectionAction.RequireBoundary(
                SessionBoundaryRequest.LeaveSupportedSession(targetSurface)
            )

        currentKind == ReferenceSessionKind.SOLO || currentKind == ReferenceSessionKind.LAB ->
            when (targetSurface) {
                ReferenceSurface.MAIN_GUIDED,
                ReferenceSurface.ADVANCED_CONTROLS,
                ReferenceSurface.SOLO_EXPLORATION,
                ReferenceSurface.LAB -> {
                    if (targetSurface != activeRoute) {
                        SurfaceSelectionAction.RequireBoundary(
                            SessionBoundaryRequest.LeaveAlternativeSession(targetSurface)
                        )
                    } else {
                        SurfaceSelectionAction.Select(targetSurface)
                    }
                }

                else -> SurfaceSelectionAction.Select(targetSurface)
            }

        currentKind == ReferenceSessionKind.SUPPORTED_ENDED &&
            targetSurface == ReferenceSurface.SOLO_EXPLORATION ->
            SurfaceSelectionAction.StartAlternativeSession(targetSurface)

        currentKind == ReferenceSessionKind.SUPPORTED_ENDED &&
            targetSurface == ReferenceSurface.LAB ->
            SurfaceSelectionAction.StartAlternativeSession(targetSurface)

        else -> SurfaceSelectionAction.Select(targetSurface)
    }
}

internal fun determineSurfaceSelectionAction(
    currentSnapshot: ReferenceControllerSnapshot,
    activeRoute: ReferenceSurface,
    targetSurface: ReferenceSurface,
): SurfaceSelectionAction {
    return determineSurfaceSelectionAction(
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
