package ch.trancee.meshlink.reference.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class InMemoryReferenceDocumentStoreTest {
    @Test
    fun supportsReadWriteOverwriteAndDeleteCycles() = runTest {
        // Arrange
        val store = InMemoryReferenceDocumentStore()
        val path = "history/session.json"

        // Act
        val missingBeforeWrite = store.readText(path)
        store.writeText(path, "first-value")
        store.writeText(path, "second-value")
        val persistedValue = store.readText(path)
        store.delete(path)
        val missingAfterDelete = store.readText(path)

        // Assert
        assertNull(missingBeforeWrite)
        assertEquals("second-value", persistedValue)
        assertNull(missingAfterDelete)
    }
}
