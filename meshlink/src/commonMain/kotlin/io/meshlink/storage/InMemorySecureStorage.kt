package io.meshlink.storage

/**
 * In-memory [SecureStorage] for testing. Stores defensive copies of all values.
 */
class InMemorySecureStorage : SecureStorage {

    private val store = mutableMapOf<String, ByteArray>()

    override fun put(key: String, value: ByteArray) {
        store[key] = value.copyOf()
    }

    override fun get(key: String): ByteArray? = store[key]?.copyOf()

    override fun delete(key: String) {
        store.remove(key)
    }

    override fun clear() {
        store.clear()
    }

    override fun size(): Int = store.size
}
