package ch.trancee.meshlink.reference.navigation

internal data class SessionBoundaryDialogContent(
    val title: String,
    val body: String,
    val exportLabel: String,
    val continueLabel: String,
)

internal enum class BoundaryContinuation {
    EXPORT_AND_CONTINUE,
    CONTINUE_WITHOUT_EXPORT,
}

internal sealed interface SessionBoundaryRequest {
    val targetSurface: ReferenceSurface

    data class LeaveSupportedSession(override val targetSurface: ReferenceSurface) :
        SessionBoundaryRequest

    data class LeaveAlternativeSession(override val targetSurface: ReferenceSurface) :
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
        is SessionBoundaryRequest.LeaveSupportedSession -> "Start a new session"
        is SessionBoundaryRequest.LeaveAlternativeSession -> "Leave current session"
    }
}

private fun SessionBoundaryRequest.dialogBody(): String {
    return when (this) {
        is SessionBoundaryRequest.LeaveSupportedSession ->
            "This closes the current supported session and starts a new ${targetSurface.titleForBoundary()} session."

        is SessionBoundaryRequest.LeaveAlternativeSession ->
            "This closes the current solo or lab session and starts a new ${targetSurface.titleForBoundary()} session."
    }
}

private fun SessionBoundaryRequest.exportLabel(): String {
    return when (this) {
        is SessionBoundaryRequest.LeaveSupportedSession -> "Export full and continue"
        is SessionBoundaryRequest.LeaveAlternativeSession -> "Export redacted and continue"
    }
}

private fun SessionBoundaryRequest.continueLabel(): String {
    return when (this) {
        is SessionBoundaryRequest.LeaveSupportedSession -> "Continue without export"
        is SessionBoundaryRequest.LeaveAlternativeSession -> "Continue without export"
    }
}

private fun ReferenceSurface.titleForBoundary(): String {
    return when (this) {
        ReferenceSurface.SOLO_EXPLORATION -> "solo"
        ReferenceSurface.LAB -> "lab"
        ReferenceSurface.ADVANCED_CONTROLS -> "supported"
        else -> "supported"
    }
}
