package ch.trancee.meshlink.reference.session

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.RecentSessionHistory
import ch.trancee.meshlink.reference.model.ReferenceSession

/** JSON-backed retained-session repository using a single bounded history document. */
public class JsonSessionHistoryRepository(
    private val documentStore: ReferenceDocumentStore,
    private val historyPath: String = DEFAULT_HISTORY_PATH,
) : SessionHistoryRepository {
    override suspend fun loadHistory(): RecentSessionHistory {
        return loadDocument().history
    }

    override suspend fun retainSession(session: ReferenceSession): RecentSessionHistory {
        val updatedDocument = loadDocument().retainSession(session)
        saveDocument(updatedDocument)
        return updatedDocument.history
    }

    override suspend fun deleteSession(sessionId: String): RecentSessionHistory {
        val updatedDocument = loadDocument().deleteSession(sessionId)
        saveDocument(updatedDocument)
        return updatedDocument.history
    }

    override suspend fun clearAll(): RecentSessionHistory {
        val emptyDocument = StoredHistoryDocument()
        saveDocument(emptyDocument)
        return emptyDocument.history
    }

    override suspend fun loadRetainedSessions(): List<ReferenceSession> {
        return loadDocument().sessions
    }

    override suspend fun loadRetainedSnapshot(sessionId: String): ReferenceControllerSnapshot? {
        return loadDocument().retainedSnapshot(sessionId)
    }

    public suspend fun retainSnapshot(snapshot: ReferenceControllerSnapshot): RecentSessionHistory {
        val updatedDocument = loadDocument().retainSnapshot(snapshot)
        saveDocument(updatedDocument)
        return updatedDocument.history
    }

    private suspend fun loadDocument(): StoredHistoryDocument {
        val raw = documentStore.readText(historyPath) ?: return StoredHistoryDocument()
        return ReferenceJson.codec.decodeFromString(StoredHistoryDocument.serializer(), raw)
    }

    private suspend fun saveDocument(document: StoredHistoryDocument): Unit {
        val serialized =
            ReferenceJson.codec.encodeToString(StoredHistoryDocument.serializer(), document)
        documentStore.writeText(historyPath, serialized)
    }

    public companion object {
        public const val DEFAULT_HISTORY_PATH: String = "reference/history.json"
    }
}
