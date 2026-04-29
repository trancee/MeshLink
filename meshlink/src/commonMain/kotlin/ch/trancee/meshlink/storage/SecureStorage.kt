package ch.trancee.meshlink.storage

/**
 * Platform-agnostic key-value store for binary secrets.
 *
 * Keys are arbitrary strings. Values are raw byte arrays (e.g. serialised key material). Platform
 * implementations are responsible for encryption at rest (Android Keystore, iOS Keychain).
 * [InMemorySecureStorage] provides an in-process implementation suitable for tests.
 */
internal interface SecureStorage {
    /**
     * Returns the value associated with [key], or `null` if no value is stored for that key.
     *
     * @param key The storage key.
     * @return The stored byte array, or `null` if absent.
     */
    fun get(key: String): ByteArray?

    /**
     * Stores [value] under [key], overwriting any existing value.
     *
     * @param key The storage key.
     * @param value The byte array to store.
     */
    fun put(key: String, value: ByteArray)

    /**
     * Removes the value associated with [key]. No-op if the key is absent.
     *
     * @param key The storage key.
     */
    fun remove(key: String)

    /**
     * Returns `true` if a value is stored for [key], `false` otherwise.
     *
     * @param key The storage key.
     * @return `true` if the key exists.
     */
    fun contains(key: String): Boolean

    /**
     * Removes all stored key-value pairs.
     *
     * Used by [ch.trancee.meshlink.api.MeshLink.factoryReset] to wipe all local secrets. Platform
     * implementations should clear both the in-memory cache and the underlying secure store.
     *
     * Default implementation is a no-op for backward compatibility with platform implementations
     * that have not yet overridden this method.
     */
    fun clear() {}
}
