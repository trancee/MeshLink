package ch.trancee.meshlink.reference.session

/**
 * Minimal document store abstraction used for retained history and exports.
 */
public interface ReferenceDocumentStore {
    public suspend fun readText(path: String): String?

    public suspend fun writeText(path: String, content: String): Unit

    public suspend fun delete(path: String): Unit
}

public class InMemoryReferenceDocumentStore : ReferenceDocumentStore {
    private val documents: LinkedHashMap<String, String> = linkedMapOf()

    override suspend fun readText(path: String): String? {
        return documents[path]
    }

    override suspend fun writeText(path: String, content: String): Unit {
        documents[path] = content
    }

    override suspend fun delete(path: String): Unit {
        documents.remove(path)
    }
}
