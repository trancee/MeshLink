package ch.trancee.meshlink.reference.navigation

internal data class SessionBoundaryDialogContent(
    val title: String,
    val body: String,
    val exportLabel: String,
    val continueLabel: String,
)

internal sealed interface SessionBoundaryRequest {
    val targetSurface: ReferenceSurfaceId

    data class SupportedTo(override val targetSurface: ReferenceSurfaceId) : SessionBoundaryRequest

    data class AlternativeTo(override val targetSurface: ReferenceSurfaceId) :
        SessionBoundaryRequest
}

internal fun SessionBoundaryRequest.toDialogContent(): SessionBoundaryDialogContent {
    return SessionBoundaryDialogContent(
        title = dialogTitle(),
        body = dialogBody(),
        exportLabel = exportLabel(),
        continueLabel = continueLabel(),
    )
}

private fun SessionBoundaryRequest.dialogTitle(): String {
    return when (this) {
        is SessionBoundaryRequest.SupportedTo -> "Start a new session"
        is SessionBoundaryRequest.AlternativeTo -> "Leave current session"
    }
}

private fun SessionBoundaryRequest.dialogBody(): String {
    return when (this) {
        is SessionBoundaryRequest.SupportedTo ->
            "This closes the current supported session and starts a new ${targetSurface.titleForBoundary()} session."
        is SessionBoundaryRequest.AlternativeTo ->
            "This closes the current solo or lab session and starts a new ${targetSurface.titleForBoundary()} session."
    }
}

private fun SessionBoundaryRequest.exportLabel(): String {
    return when (this) {
        is SessionBoundaryRequest.SupportedTo -> "Export full and continue"
        is SessionBoundaryRequest.AlternativeTo -> "Export redacted and continue"
    }
}

private fun SessionBoundaryRequest.continueLabel(): String {
    return when (this) {
        is SessionBoundaryRequest.SupportedTo -> "Continue without export"
        is SessionBoundaryRequest.AlternativeTo -> "Continue without export"
    }
}

private fun ReferenceSurfaceId.titleForBoundary(): String {
    return when (this) {
        ReferenceSurfaceId.SOLO_EXPLORATION -> "solo"
        ReferenceSurfaceId.LAB -> "lab"
        ReferenceSurfaceId.ADVANCED_CONTROLS -> "supported"
        else -> "supported"
    }
}
