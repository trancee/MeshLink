package ch.trancee.meshlink.test

import ch.trancee.meshlink.storage.SecureStorage

internal class InMemorySecureStorage : SecureStorage {
    private val values: MutableMap<String, ByteArray> = linkedMapOf()

    override suspend fun read(key: String): ByteArray? {
        return values[key]?.copyOf()
    }

    override suspend fun write(key: String, value: ByteArray): Unit {
        values[key] = value.copyOf()
    }

    override suspend fun delete(key: String): Unit {
        values.remove(key)
    }
}
