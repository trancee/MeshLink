package io.meshlink.storage

/**
 * Abstraction for platform-specific secure key-value storage.
 *
 * Production implementations:
 * - Android: EncryptedSharedPreferences (backed by Keystore)
 * - iOS: Keychain with kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
 *
 * Test implementation: [InMemorySecureStorage]
 */
interface SecureStorage {
    fun put(key: String, value: ByteArray)
    fun get(key: String): ByteArray?
    fun delete(key: String)
    fun clear()
    fun size(): Int
}
