package ch.trancee.meshlink.storage

internal interface SecureStorage {
    suspend fun read(key: String): ByteArray?

    suspend fun write(key: String, value: ByteArray): Unit

    suspend fun delete(key: String): Unit
}
