package ch.trancee.meshlink.platform.android

import android.content.Context
import android.util.Base64
import ch.trancee.meshlink.storage.SecureStorage

internal class AndroidSecureStorage(
    context: Context,
    appId: String,
) : SecureStorage {
    private val preferences = context.getSharedPreferences("meshlink-$appId", Context.MODE_PRIVATE)

    override suspend fun read(key: String): ByteArray? {
        val encoded = preferences.getString(key, null) ?: return null
        return Base64.decode(encoded, Base64.NO_WRAP)
    }

    override suspend fun write(key: String, value: ByteArray): Unit {
        val encoded = Base64.encodeToString(value, Base64.NO_WRAP)
        preferences.edit().putString(key, encoded).apply()
    }

    override suspend fun delete(key: String): Unit {
        preferences.edit().remove(key).apply()
    }
}
