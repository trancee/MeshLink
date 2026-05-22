package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.storage.SecureStorage
import platform.Foundation.NSUserDefaults

internal class IosSecureStorage(appId: String) : SecureStorage {
    private val defaults = NSUserDefaults(suiteName = "meshlink.$appId")

    override suspend fun read(key: String): ByteArray? {
        val encoded = defaults.stringForKey(key) ?: return null
        return if (encoded.isEmpty()) {
            ByteArray(0)
        } else {
            encoded.split(",").map { token -> token.toInt().toByte() }.toByteArray()
        }
    }

    override suspend fun write(key: String, value: ByteArray): Unit {
        val encoded = value.joinToString(separator = ",") { byte -> byte.toInt().toString() }
        defaults.setObject(encoded, forKey = key)
    }

    override suspend fun delete(key: String): Unit {
        defaults.removeObjectForKey(key)
    }
}
