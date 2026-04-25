package ch.trancee.meshlink.storage

/**
 * iOS Keychain-backed [SecureStorage] — full implementation deferred to M002+.
 *
 * This stub satisfies the compiler on the iOS target. All methods throw [NotImplementedError] at
 * runtime.
 */
internal class IosSecureStorage : SecureStorage {
    override fun get(key: String): ByteArray? =
        throw NotImplementedError("Platform implementation pending")

    override fun put(key: String, value: ByteArray) {
        throw NotImplementedError("Platform implementation pending")
    }

    override fun remove(key: String) {
        throw NotImplementedError("Platform implementation pending")
    }

    override fun contains(key: String): Boolean =
        throw NotImplementedError("Platform implementation pending")
}
