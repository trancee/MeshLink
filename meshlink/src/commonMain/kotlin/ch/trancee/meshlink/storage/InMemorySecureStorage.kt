package ch.trancee.meshlink.storage

/**
 * In-memory [SecureStorage] implementation backed by a [HashMap].
 *
 * Intended for use in unit tests and commonTest source sets where platform-specific Keychain /
 * Keystore storage is unavailable. Values are stored in plain memory — do not use in production
 * code.
 */
internal class InMemorySecureStorage : SecureStorage {
    private val map = HashMap<String, ByteArray>()

    override fun get(key: String): ByteArray? = map[key]

    override fun put(key: String, value: ByteArray) {
        map[key] = value
    }

    override fun remove(key: String) {
        map.remove(key)
    }

    override fun contains(key: String): Boolean = map.containsKey(key)

    override fun clear() {
        map.clear()
    }
}
