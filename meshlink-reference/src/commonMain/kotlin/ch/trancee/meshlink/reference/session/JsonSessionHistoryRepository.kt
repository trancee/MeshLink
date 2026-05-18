package ch.trancee.meshlink.reference.session

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.RecentSessionHistory
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import kotlinx.serialization.Serializable

/**
 * JSON-backed retained-session repository using a single bounded history document.
 */
public class JsonSessionHistoryRepository(
    private val documentStore: ReferenceDocumentStore,
    private val historyPath: String = DEFAULT_HISTORY_PATH,
) : SessionHistoryRepository {
    override suspend fun loadHistory(): RecentSessionHistory {
        return loadDocument().history
    }

    override suspend fun retainSession(session: ReferenceSession): RecentSessionHistory {
        val now = session.endedAtEpochMillis ?: session.startedAtEpochMillis
        val current = loadDocument()
        val retainedSession = session.copy(historyStatus = ReferenceHistoryStatus.RETAINED)
        val updatedHistory = current.history.withSession(retainedSession.sessionId, now)
        val updatedSessions =
            listOf(retainedSession) + current.sessions.filterNot { existing -> existing.sessionId == retainedSession.sessionId }
        saveDocument(
            StoredHistoryDocument(
                history = updatedHistory,
                sessions = updatedSessions.take(updatedHistory.maxSessions),
                snapshots = current.snapshots.filter { snapshot ->
                    updatedHistory.sessionIds.contains(snapshot.session.sessionId)
                },
            )
        )
        return updatedHistory
    }

    override suspend fun deleteSession(sessionId: String): RecentSessionHistory {
        val current = loadDocument()
        val updatedHistory = current.history.withoutSession(sessionId, 0L)
        saveDocument(
            current.copy(
                history = updatedHistory,
                sessions = current.sessions.filterNot { existing -> existing.sessionId == sessionId },
                snapshots = current.snapshots.filterNot { existing -> existing.session.sessionId == sessionId },
            )
        )
        return updatedHistory
    }

    override suspend fun clearAll(): RecentSessionHistory {
        val empty = StoredHistoryDocument()
        saveDocument(empty)
        return empty.history
    }

    override suspend fun loadRetainedSessions(): List<ReferenceSession> {
        return loadDocument().sessions
    }

    override suspend fun loadRetainedSnapshot(sessionId: String): ReferenceControllerSnapshot? {
        return loadDocument().snapshots.firstOrNull { snapshot -> snapshot.session.sessionId == sessionId }
    }

    public suspend fun retainSnapshot(snapshot: ReferenceControllerSnapshot): RecentSessionHistory {
        val current = loadDocument()
        val retainedSession = snapshot.session.copy(historyStatus = ReferenceHistoryStatus.RETAINED)
        val updatedHistory = current.history.withSession(retainedSession.sessionId, retainedSession.startedAtEpochMillis)
        val updatedSnapshots =
            listOf(snapshot.copy(session = retainedSession)) +
                current.snapshots.filterNot { existing -> existing.session.sessionId == retainedSession.sessionId }
        val updatedSessions = updatedSnapshots.map { retained -> retained.session }.take(updatedHistory.maxSessions)
        saveDocument(
            StoredHistoryDocument(
                history = updatedHistory,
                sessions = updatedSessions,
                snapshots = updatedSnapshots.take(updatedHistory.maxSessions),
            )
        )
        return updatedHistory
    }

    private suspend fun loadDocument(): StoredHistoryDocument {
        val raw = documentStore.readText(historyPath) ?: return StoredHistoryDocument()
        return ReferenceJson.codec.decodeFromString(StoredHistoryDocument.serializer(), raw)
    }

    private suspend fun saveDocument(document: StoredHistoryDocument): Unit {
        val serialized = ReferenceJson.codec.encodeToString(StoredHistoryDocument.serializer(), document)
        documentStore.writeText(historyPath, serialized)
    }

    @Serializable
    private data class StoredHistoryDocument(
        val history: RecentSessionHistory = RecentSessionHistory(),
        val sessions: List<ReferenceSession> = emptyList(),
        val snapshots: List<ReferenceControllerSnapshot> = emptyList(),
    )

    public companion object {
        public const val DEFAULT_HISTORY_PATH: String = "reference/history.json"
    }
}
