package ch.trancee.meshlink.reference.session

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import okio.FileSystem

class OkioReferenceDocumentStoreJvmTest {
    @Test
    fun readWriteAndDeleteRoundTripThroughTheFileSystem() = runTest {
        // Arrange
        val baseDirectory = Files.createTempDirectory("meshlink-reference-doc-store").toString()
        val store =
            OkioReferenceDocumentStore(
                baseDirectory = baseDirectory,
                fileSystem = FileSystem.SYSTEM,
            )
        val relativePath = "history/session-1.json"
        val expectedContent = "{\"sessionId\":\"session-1\"}"

        // Act
        val missingBeforeWrite = store.readText(relativePath)
        store.writeText(relativePath, expectedContent)
        val actualAfterWrite = store.readText(relativePath)
        store.delete(relativePath)
        val missingAfterDelete = store.readText(relativePath)

        // Assert
        assertNull(missingBeforeWrite)
        assertEquals(expectedContent, actualAfterWrite)
        assertNull(missingAfterDelete)
    }
}
