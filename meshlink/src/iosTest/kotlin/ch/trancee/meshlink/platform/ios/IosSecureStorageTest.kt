package ch.trancee.meshlink.platform.ios

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class SecureStorageTest {
    @Test
    fun `write read and delete round-trip stored values`() = runBlocking {
        // Arrange
        val appId = "ios-secure-storage-test-roundtrip"
        val key = "meshlink-key"
        val value = byteArrayOf(1, 2, 3, 4)
        val storage = DefaultsSecureStorage(appId)
        storage.delete(key)

        // Act
        storage.write(key, value)
        val firstRead = storage.read(key)
        storage.delete(key)
        val secondRead = storage.read(key)

        // Assert
        assertContentEquals(value, firstRead)
        assertEquals(null, secondRead)
    }

    @Test
    fun `write and read preserve empty byte arrays`() = runBlocking {
        // Arrange
        val appId = "ios-secure-storage-test-empty"
        val key = "meshlink-empty"
        val storage = DefaultsSecureStorage(appId)
        storage.delete(key)

        // Act
        storage.write(key, byteArrayOf())
        val readBack = storage.read(key)
        storage.delete(key)

        // Assert
        assertContentEquals(byteArrayOf(), readBack)
    }
}
