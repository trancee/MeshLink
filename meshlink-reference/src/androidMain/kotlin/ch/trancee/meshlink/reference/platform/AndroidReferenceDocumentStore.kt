package ch.trancee.meshlink.reference.platform

import ch.trancee.meshlink.reference.session.InMemoryReferenceDocumentStore
import ch.trancee.meshlink.reference.session.ReferenceDocumentStore

/**
 * Temporary Android document store wrapper until platform file-backed persistence is added.
 */
internal class AndroidReferenceDocumentStore : ReferenceDocumentStore {
    private val delegate: InMemoryReferenceDocumentStore = InMemoryReferenceDocumentStore()

    override suspend fun readText(path: String): String? = delegate.readText(path)

    override suspend fun writeText(path: String, content: String): Unit = delegate.writeText(path, content)

    override suspend fun delete(path: String): Unit = delegate.delete(path)
}
