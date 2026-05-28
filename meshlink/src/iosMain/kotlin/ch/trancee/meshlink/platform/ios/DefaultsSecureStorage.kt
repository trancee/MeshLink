package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.storage.SecureStorage
import platform.Foundation.NSUserDefaults

internal class DefaultsSecureStorage(appId: String) : SecureStorage {
    private val defaults = NSUserDefaults(suiteName = "meshlink.$appId")

    override suspend fun read(key: String): ByteArray? {
        return defaults.dataForKey(key)?.toByteArray()
    }

    override suspend fun write(key: String, value: ByteArray): Unit {
        defaults.setObject(value.toNSData(), forKey = key)
    }

    override suspend fun delete(key: String): Unit {
        defaults.removeObjectForKey(key)
    }
}
