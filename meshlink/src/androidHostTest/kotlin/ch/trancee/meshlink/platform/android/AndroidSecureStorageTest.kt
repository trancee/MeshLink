package ch.trancee.meshlink.platform.android

import android.app.Application
import android.content.SharedPreferences
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class SecureStorageTest {
    @Test
    fun `write stores a defensive copy and read returns the original bytes`() = runBlocking {
        // Arrange
        val context = FakeContext()
        val storage = PreferencesSecureStorage(context = context, appId = "demo.meshlink")
        val key = "identity-key"
        val sourceValue = byteArrayOf(1, 2, 3, 4)

        // Act
        storage.write(key, sourceValue)
        sourceValue[0] = 9
        val firstRead = storage.read(key)
        firstRead!![1] = 8
        val secondRead = storage.read(key)

        // Assert
        assertContentEquals(byteArrayOf(1, 2, 3, 4), secondRead)
        assertEquals("meshlink-demo.meshlink", context.lastPreferencesName)
    }

    @Test
    fun `delete removes stored values`() = runBlocking {
        // Arrange
        val storage = PreferencesSecureStorage(context = FakeContext(), appId = "demo.meshlink")
        val key = "identity-key"
        storage.write(key, byteArrayOf(1, 2, 3, 4))

        // Act
        storage.delete(key)
        val remaining = storage.read(key)

        // Assert
        assertEquals(null, remaining)
    }
}

private class FakeContext : Application() {
    private val preferences = FakeSharedPreferences()
    var lastPreferencesName: String? = null
        private set

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        lastPreferencesName = name
        return preferences
    }
}

private class FakeSharedPreferences : SharedPreferences {
    private val values: MutableMap<String, String?> = linkedMapOf()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? = values[key] ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        defValues

    override fun getInt(key: String?, defValue: Int): Int = defValue

    override fun getLong(key: String?, defValue: Long): Long = defValue

    override fun getFloat(key: String?, defValue: Float): Float = defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor(values)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    private class Editor(private val values: MutableMap<String, String?>) :
        SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            values[key.orEmpty()] = value
            return this
        }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = this

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this

        override fun remove(key: String?): SharedPreferences.Editor {
            values.remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            values.clear()
            return this
        }

        override fun commit(): Boolean = true

        override fun apply() = Unit
    }
}
