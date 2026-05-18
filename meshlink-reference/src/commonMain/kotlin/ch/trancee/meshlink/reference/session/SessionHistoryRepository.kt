package ch.trancee.meshlink.reference.session

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.RecentSessionHistory
import ch.trancee.meshlink.reference.model.ReferenceSession

/** Persistence contract for retained recent session history. */
public interface SessionHistoryRepository {
    public suspend fun loadHistory(): RecentSessionHistory

    public suspend fun retainSession(session: ReferenceSession): RecentSessionHistory

    public suspend fun deleteSession(sessionId: String): RecentSessionHistory

    public suspend fun clearAll(): RecentSessionHistory

    public suspend fun loadRetainedSessions(): List<ReferenceSession>

    public suspend fun loadRetainedSnapshot(sessionId: String): ReferenceControllerSnapshot?
}
