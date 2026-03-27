package io.meshlink.storage

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InMemorySecureStorageTest {

    // --- TDD Cycle 1: put then get returns stored value ---
    @Test
    fun putThenGetReturnsValue() {
        val storage = InMemorySecureStorage()
        val key = "identity_key"
        val value = byteArrayOf(0x01, 0x02, 0x03)

        storage.put(key, value)
        val retrieved = storage.get(key)

        assertContentEquals(value, retrieved)
    }

    // --- TDD Cycle 2: get for absent key returns null ---
    @Test
    fun getAbsentKeyReturnsNull() {
        val storage = InMemorySecureStorage()
        assertNull(storage.get("nonexistent"))
    }

    // --- TDD Cycle 3: delete removes stored value ---
    @Test
    fun deleteRemovesValue() {
        val storage = InMemorySecureStorage()
        storage.put("key1", byteArrayOf(0xAA.toByte()))
        storage.delete("key1")
        assertNull(storage.get("key1"))
    }

    // --- TDD Cycle 4: clear removes all values ---
    @Test
    fun clearRemovesAllValues() {
        val storage = InMemorySecureStorage()
        storage.put("a", byteArrayOf(1))
        storage.put("b", byteArrayOf(2))
        storage.clear()
        assertNull(storage.get("a"))
        assertNull(storage.get("b"))
    }

    // --- TDD Cycle 5: put overwrites existing value ---
    @Test
    fun putOverwritesExistingValue() {
        val storage = InMemorySecureStorage()
        storage.put("key", byteArrayOf(1))
        storage.put("key", byteArrayOf(2, 3))
        assertContentEquals(byteArrayOf(2, 3), storage.get("key"))
    }

    // --- TDD Cycle 6: stored value is a defensive copy (mutation-safe) ---
    @Test
    fun storedValueIsDefensiveCopy() {
        val storage = InMemorySecureStorage()
        val original = byteArrayOf(0x01, 0x02)
        storage.put("key", original)

        // Mutate the original array
        original[0] = 0xFF.toByte()

        // Stored value should be unaffected
        assertContentEquals(byteArrayOf(0x01, 0x02), storage.get("key"))
    }

    // --- TDD Cycle 7: returned value is a defensive copy ---
    @Test
    fun returnedValueIsDefensiveCopy() {
        val storage = InMemorySecureStorage()
        storage.put("key", byteArrayOf(0x01, 0x02))

        val first = storage.get("key")!!
        first[0] = 0xFF.toByte()

        // Second get should return the original
        assertContentEquals(byteArrayOf(0x01, 0x02), storage.get("key"))
    }

    // --- TDD Cycle 8: delete on absent key is no-op ---
    @Test
    fun deleteAbsentKeyIsNoOp() {
        val storage = InMemorySecureStorage()
        storage.delete("nonexistent") // should not throw
        assertEquals(0, storage.size())
    }

    // --- TDD Cycle 9: size tracks stored entries ---
    @Test
    fun sizeTracksEntries() {
        val storage = InMemorySecureStorage()
        assertEquals(0, storage.size())
        storage.put("a", byteArrayOf(1))
        assertEquals(1, storage.size())
        storage.put("b", byteArrayOf(2))
        assertEquals(2, storage.size())
        storage.delete("a")
        assertEquals(1, storage.size())
    }

    // --- TDD Cycle 10: conforms to SecureStorage interface ---
    @Test
    fun conformsToSecureStorageInterface() {
        val storage: SecureStorage = InMemorySecureStorage()
        storage.put("test", byteArrayOf(42))
        assertContentEquals(byteArrayOf(42), storage.get("test"))
    }
}
