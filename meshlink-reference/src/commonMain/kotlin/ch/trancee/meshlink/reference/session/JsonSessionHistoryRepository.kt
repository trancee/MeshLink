package ch.trancee.meshlink.reference.session

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.RecentSessionHistory
import ch.trancee.meshlink.reference.model.ReferenceSession

/** JSON-backed retained-session repository using a single bounded history document. */
internal class JsonSessionHistoryRepository(
    documentStore: ReferenceDocumentStore,
    historyPath: String = DEFAULT_HISTORY_PATH,
) : SessionHistoryRepository {
    private val historyDocumentStore: StoredHistoryDocumentStore =
        StoredHistoryDocumentStore(documentStore = documentStore, historyPath = historyPath)

    override suspend fun loadHistory(): RecentSessionHistory {
        return historyDocumentStore.read().history
    }

    override suspend fun retainSession(session: ReferenceSession): RecentSessionHistory {
        return historyDocumentStore.update { document -> document.retainSession(session) }.history
    }

    override suspend fun deleteSession(sessionId: String): RecentSessionHistory {
        return historyDocumentStore.update { document -> document.deleteSession(sessionId) }.history
    }

    override suspend fun clearAll(): RecentSessionHistory {
        val emptyDocument = StoredHistoryDocument()
        historyDocumentStore.replace(emptyDocument)
        return emptyDocument.history
    }

    override suspend fun loadRetainedSessions(): List<ReferenceSession> {
        return historyDocumentStore.read().sessions
    }

    override suspend fun loadRetainedSnapshot(sessionId: String): ReferenceControllerSnapshot? {
        return historyDocumentStore.read().retainedSnapshot(sessionId)
    }

    public suspend fun retainSnapshot(snapshot: ReferenceControllerSnapshot): RecentSessionHistory {
        return historyDocumentStore.update { document -> document.retainSnapshot(snapshot) }.history
    }

    public companion object {
        public const val DEFAULT_HISTORY_PATH: String = "reference/history.json"
    }
}
