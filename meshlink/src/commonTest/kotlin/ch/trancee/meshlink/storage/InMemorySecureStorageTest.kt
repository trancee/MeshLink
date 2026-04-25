package ch.trancee.meshlink.storage

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemorySecureStorageTest {

    @Test
    fun putAndGetRoundTrip() {
        val storage = InMemorySecureStorage()
        val value = byteArrayOf(10, 20, 30)
        storage.put("key1", value)
        assertContentEquals(value, storage.get("key1"))
    }

    @Test
    fun getMissingKeyReturnsNull() {
        val storage = InMemorySecureStorage()
        assertNull(storage.get("missing"))
    }

    @Test
    fun containsTrueAfterPut() {
        val storage = InMemorySecureStorage()
        storage.put("k", byteArrayOf(1))
        assertTrue(storage.contains("k"))
    }

    @Test
    fun containsFalseForMissingKey() {
        val storage = InMemorySecureStorage()
        assertFalse(storage.contains("absent"))
    }

    @Test
    fun removeThenGetReturnsNull() {
        val storage = InMemorySecureStorage()
        storage.put("key", byteArrayOf(9))
        storage.remove("key")
        assertNull(storage.get("key"))
    }

    @Test
    fun overwriteExistingKey() {
        val storage = InMemorySecureStorage()
        storage.put("key", byteArrayOf(1, 2))
        storage.put("key", byteArrayOf(3, 4))
        assertContentEquals(byteArrayOf(3, 4), storage.get("key"))
    }

    @Test
    fun removeNonExistentKeyIsNoOp() {
        val storage = InMemorySecureStorage()
        // must not throw
        storage.remove("does-not-exist")
        assertFalse(storage.contains("does-not-exist"))
    }
}
