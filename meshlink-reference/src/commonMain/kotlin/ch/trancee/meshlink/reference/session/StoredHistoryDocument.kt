package ch.trancee.meshlink.reference.session

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.RecentSessionHistory
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import kotlinx.serialization.Serializable

@Serializable
internal data class StoredHistoryDocument(
    val history: RecentSessionHistory = RecentSessionHistory(),
    val sessions: List<ReferenceSession> = emptyList(),
    val snapshots: List<ReferenceControllerSnapshot> = emptyList(),
) {
    fun retainSession(session: ReferenceSession): StoredHistoryDocument {
        val now = session.endedAtEpochMillis ?: session.startedAtEpochMillis
        val retainedSession = session.copy(historyStatus = ReferenceHistoryStatus.RETAINED)
        val updatedHistory = history.withSession(retainedSession.sessionId, now)
        val retainedSessionIds = updatedHistory.sessionIds.toSet()
        return copy(
            history = updatedHistory,
            sessions =
                (listOf(retainedSession) +
                        sessions.filterNot { it.sessionId == retainedSession.sessionId })
                    .take(updatedHistory.maxSessions),
            snapshots =
                snapshots.filter { snapshot -> snapshot.session.sessionId in retainedSessionIds },
        )
    }

    fun deleteSession(sessionId: String): StoredHistoryDocument {
        return copy(
            history = history.withoutSession(sessionId, 0L),
            sessions = sessions.filterNot { it.sessionId == sessionId },
            snapshots = snapshots.filterNot { it.session.sessionId == sessionId },
        )
    }

    fun retainSnapshot(snapshot: ReferenceControllerSnapshot): StoredHistoryDocument {
        val retainedSession = snapshot.session.copy(historyStatus = ReferenceHistoryStatus.RETAINED)
        val updatedHistory =
            history.withSession(retainedSession.sessionId, retainedSession.startedAtEpochMillis)
        val updatedSnapshots =
            (listOf(snapshot.copy(session = retainedSession)) +
                    snapshots.filterNot { it.session.sessionId == retainedSession.sessionId })
                .take(updatedHistory.maxSessions)
        return copy(
            history = updatedHistory,
            sessions = updatedSnapshots.map { it.session }.take(updatedHistory.maxSessions),
            snapshots = updatedSnapshots,
        )
    }

    fun retainedSnapshot(sessionId: String): ReferenceControllerSnapshot? {
        return snapshots.firstOrNull { it.session.sessionId == sessionId }
    }
}
