package ch.trancee.meshlink.storage

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class InMemorySecureStorageTest {
    @Test
    fun `write stores a defensive copy and read returns a defensive copy`() = runBlocking {
        // Arrange
        val storage = InMemorySecureStorage()
        val key = "identity:demo"
        val sourceValue = byteArrayOf(1, 2, 3, 4)
        storage.write(key, sourceValue)
        sourceValue[0] = 9

        // Act
        val firstRead = storage.read(key)
        firstRead!![1] = 8
        val secondRead = storage.read(key)

        // Assert
        assertContentEquals(byteArrayOf(1, 2, 3, 4), secondRead)
    }

    @Test
    fun `delete removes stored values`() = runBlocking {
        // Arrange
        val storage = InMemorySecureStorage()
        val key = "identity:demo"
        storage.write(key, byteArrayOf(1, 2, 3, 4))

        // Act
        storage.delete(key)
        val remaining = storage.read(key)

        // Assert
        assertEquals(null, remaining)
    }
}
