package ch.trancee.meshlink.storage

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Android Keystore-backed [SecureStorage] using [EncryptedSharedPreferences].
 *
 * Keys are encrypted with AES256-SIV; values are encrypted with AES256-GCM. The master key is
 * stored in the Android Keystore under [MasterKey.DEFAULT_MASTER_KEY_ALIAS]. Byte arrays are stored
 * as Base64-encoded strings (NO_WRAP) because [EncryptedSharedPreferences] only supports string
 * values natively.
 *
 * Excluded from Kover per D024 — Android Keystore and EncryptedSharedPreferences require the full
 * Android runtime and cannot execute on a JVM host test runner.
 *
 * @param context Android context used to open the shared preferences file and access the Keystore.
 */
internal class AndroidSecureStorage(context: Context) : SecureStorage {

    private val prefs =
        EncryptedSharedPreferences.create(
            context,
            "meshlink_secure_prefs",
            MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    override fun get(key: String): ByteArray? {
        val encoded = prefs.getString(key, null) ?: return null
        return Base64.decode(encoded, Base64.NO_WRAP)
    }

    override fun put(key: String, value: ByteArray) {
        prefs.edit().putString(key, Base64.encodeToString(value, Base64.NO_WRAP)).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun contains(key: String): Boolean = prefs.contains(key)
}
