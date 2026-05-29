package ch.trancee.meshlink.reference.session

/**
 * Small document seam for the bounded retained-history file.
 *
 * The repository owns history behavior, while this store owns the single-file read, default, and
 * write mechanics for `StoredHistoryDocument`.
 */
internal class StoredHistoryDocumentStore(
    private val documentStore: ReferenceDocumentStore,
    private val historyPath: String,
) {
    suspend fun read(): StoredHistoryDocument {
        val raw = documentStore.readText(historyPath) ?: return StoredHistoryDocument()
        return ReferenceJson.codec.decodeFromString(StoredHistoryDocument.serializer(), raw)
    }

    suspend fun replace(document: StoredHistoryDocument): Unit {
        val serialized =
            ReferenceJson.codec.encodeToString(StoredHistoryDocument.serializer(), document)
        documentStore.writeText(historyPath, serialized)
    }

    suspend fun update(
        transform: (StoredHistoryDocument) -> StoredHistoryDocument
    ): StoredHistoryDocument {
        val updatedDocument = transform(read())
        replace(updatedDocument)
        return updatedDocument
    }
}
