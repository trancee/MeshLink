package ch.trancee.meshlink.reference.model

import kotlinx.serialization.Serializable

/** Bounded retained-session index shown separately from the live session. */
@Serializable
internal data class RecentSessionHistory(
    public val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    public val sessionIds: List<String> = emptyList(),
    public val lastPrunedAtEpochMillis: Long? = null,
) {
    public fun withSession(sessionId: String, nowEpochMillis: Long): RecentSessionHistory {
        val updated = listOf(sessionId) + sessionIds.filterNot { existing -> existing == sessionId }
        return copy(
            sessionIds = updated.take(maxSessions),
            lastPrunedAtEpochMillis = nowEpochMillis,
        )
    }

    public fun withoutSession(sessionId: String, nowEpochMillis: Long): RecentSessionHistory {
        return copy(
            sessionIds = sessionIds.filterNot { existing -> existing == sessionId },
            lastPrunedAtEpochMillis = nowEpochMillis,
        )
    }

    public companion object {
        public const val DEFAULT_MAX_SESSIONS: Int = 20
    }
}
