package ch.trancee.meshlink.reference.navigation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.session.ReferenceSessionKind
import ch.trancee.meshlink.reference.session.referenceSessionKind

internal sealed interface SurfaceSelectionAction {
    data class Select(val surface: ReferenceSurfaceId) : SurfaceSelectionAction

    data class RequireBoundary(val request: SessionBoundaryRequest) : SurfaceSelectionAction

    data class StartAlternativeSession(val surface: ReferenceSurfaceId) : SurfaceSelectionAction
}

internal fun determineSurfaceSelectionAction(
    currentKind: ReferenceSessionKind,
    activeRoute: ReferenceSurfaceId,
    targetSurface: ReferenceSurfaceId,
): SurfaceSelectionAction {
    return when {
        currentKind == ReferenceSessionKind.SUPPORTED_LIVE &&
            targetSurface == ReferenceSurfaceId.SOLO_EXPLORATION ->
            SurfaceSelectionAction.RequireBoundary(
                SessionBoundaryRequest.LeaveSupportedSession(targetSurface)
            )

        currentKind == ReferenceSessionKind.SUPPORTED_LIVE &&
            targetSurface == ReferenceSurfaceId.LAB ->
            SurfaceSelectionAction.RequireBoundary(
                SessionBoundaryRequest.LeaveSupportedSession(targetSurface)
            )

        currentKind == ReferenceSessionKind.SOLO || currentKind == ReferenceSessionKind.LAB ->
            when (targetSurface) {
                ReferenceSurfaceId.MAIN_GUIDED,
                ReferenceSurfaceId.ADVANCED_CONTROLS,
                ReferenceSurfaceId.SOLO_EXPLORATION,
                ReferenceSurfaceId.LAB -> {
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
            targetSurface == ReferenceSurfaceId.SOLO_EXPLORATION ->
            SurfaceSelectionAction.StartAlternativeSession(targetSurface)

        currentKind == ReferenceSessionKind.SUPPORTED_ENDED &&
            targetSurface == ReferenceSurfaceId.LAB ->
            SurfaceSelectionAction.StartAlternativeSession(targetSurface)

        else -> SurfaceSelectionAction.Select(targetSurface)
    }
}

internal fun determineSurfaceSelectionAction(
    currentSnapshot: ReferenceControllerSnapshot,
    activeRoute: ReferenceSurfaceId,
    targetSurface: ReferenceSurfaceId,
): SurfaceSelectionAction {
    return determineSurfaceSelectionAction(
        currentKind = currentSnapshot.referenceSessionKind(),
        activeRoute = activeRoute,
        targetSurface = targetSurface,
    )
}

internal fun followUpSupportedEntrySurface(
    currentSnapshot: ReferenceControllerSnapshot
): ReferenceSurfaceId {
    return when (currentSnapshot.session.configurationSnapshot["surface"]) {
        ReferenceSurfaceId.ADVANCED_CONTROLS.route -> ReferenceSurfaceId.ADVANCED_CONTROLS
        else -> ReferenceSurfaceId.MAIN_GUIDED
    }
}

internal fun followUpSupportedSessionLabel(currentSnapshot: ReferenceControllerSnapshot): String {
    return when (followUpSupportedEntrySurface(currentSnapshot)) {
        ReferenceSurfaceId.ADVANCED_CONTROLS -> "Start new advanced session"
        else -> "Start new guided session"
    }
}
