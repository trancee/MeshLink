package ch.trancee.meshlink.reference.navigation

/** Compatibility boundary for ending a supported session. */
internal enum class BoundaryContinuation {
    EXPORT_AND_CONTINUE,
    CONTINUE_WITHOUT_EXPORT,
}

internal class SessionTransitionService {
    suspend fun endSupportedSession(): Unit = Unit
}
