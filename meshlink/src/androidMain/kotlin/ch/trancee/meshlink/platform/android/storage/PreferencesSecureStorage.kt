package ch.trancee.meshlink.platform.android.storage

import android.content.Context
import ch.trancee.meshlink.storage.SecureStorage
import java.util.Base64

internal class PreferencesSecureStorage(context: Context, appId: String) : SecureStorage {
    private val preferences = context.getSharedPreferences("meshlink-$appId", Context.MODE_PRIVATE)

    override suspend fun read(key: String): ByteArray? {
        val encoded = preferences.getString(key, null) ?: return null
        return Base64.getDecoder().decode(encoded)
    }

    override suspend fun write(key: String, value: ByteArray): Unit {
        val encoded = Base64.getEncoder().encodeToString(value)
        preferences.edit().putString(key, encoded).commit()
    }

    override suspend fun delete(key: String): Unit {
        preferences.edit().remove(key).commit()
    }
}
