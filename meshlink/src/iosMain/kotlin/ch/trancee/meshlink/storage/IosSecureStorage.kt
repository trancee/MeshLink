@file:Suppress("WildcardImport")
@file:OptIn(ExperimentalForeignApi::class)

package ch.trancee.meshlink.storage

import kotlinx.cinterop.*
import platform.Foundation.*
import platform.Security.*

/**
 * iOS Keychain-backed [SecureStorage].
 *
 * All items are stored as `kSecClassGenericPassword` with:
 * - `kSecAttrService = "ch.trancee.meshlink"` — groups all MeshLink items
 * - `kSecAttrAccessible = kSecAttrAccessibleAfterFirstUnlock` — survives device restart while
 *   locked; accessible once the device has been unlocked at least once after boot. Required for
 *   background BLE operations.
 *
 * Keychain items are redacted from logs — only key names are mentioned, never values.
 */
internal class IosSecureStorage : SecureStorage {

    private companion object {
        private const val SERVICE = "ch.trancee.meshlink"
    }

    // ── Base query ────────────────────────────────────────────────────────────

    private fun baseQuery(key: String): MutableMap<Any?, Any?> =
        mutableMapOf(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to SERVICE,
            kSecAttrAccount to key,
            kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlock,
        )

    // ── SecureStorage implementation ──────────────────────────────────────────

    override fun get(key: String): ByteArray? {
        val query =
            baseQuery(key).apply {
                put(kSecReturnData, true)
                put(kSecMatchLimit, kSecMatchLimitOne)
            }
        var result: CFTypeRef? = null
        val status = SecItemCopyMatching(query as CFDictionaryRef, result?.ptr ?: null)

        if (status == errSecItemNotFound) return null
        if (status != errSecSuccess) {
            println("IosSecureStorage get($key) failed status=$status")
            return null
        }

        val data = result as? NSData ?: return null
        return nsDataToByteArray(data)
    }

    override fun put(key: String, value: ByteArray) {
        val nsData = byteArrayToNSData(value)

        // Try add first
        val addQuery = baseQuery(key).apply { put(kSecValueData, nsData) }
        val addStatus = SecItemAdd(addQuery as CFDictionaryRef, null)

        if (addStatus == errSecDuplicateItem) {
            // Item exists — update it
            val findQuery = baseQuery(key)
            val updateAttrs: MutableMap<Any?, Any?> = mutableMapOf(kSecValueData to nsData)
            val updateStatus =
                SecItemUpdate(findQuery as CFDictionaryRef, updateAttrs as CFDictionaryRef)
            if (updateStatus != errSecSuccess) {
                println("IosSecureStorage update($key) failed status=$updateStatus")
            }
            return
        }

        if (addStatus != errSecSuccess) {
            println("IosSecureStorage put($key) failed status=$addStatus")
        }
    }

    override fun remove(key: String) {
        val query = baseQuery(key)
        val status = SecItemDelete(query as CFDictionaryRef)
        if (status != errSecSuccess && status != errSecItemNotFound) {
            println("IosSecureStorage remove($key) failed status=$status")
        }
    }

    override fun contains(key: String): Boolean {
        val query =
            baseQuery(key).apply {
                put(kSecReturnData, false)
                put(kSecMatchLimit, kSecMatchLimitOne)
            }
        val status = SecItemCopyMatching(query as CFDictionaryRef, null)
        return status == errSecSuccess
    }

    // ── NSData ↔ ByteArray helpers ────────────────────────────────────────────

    private fun byteArrayToNSData(bytes: ByteArray): NSData {
        if (bytes.isEmpty()) return NSData.data()!!
        return bytes.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())!!
        }
    }

    private fun nsDataToByteArray(data: NSData): ByteArray {
        val size = data.length.toInt()
        if (size == 0) return ByteArray(0)
        val result = ByteArray(size)
        result.usePinned { pinned ->
            platform.posix.memcpy(pinned.addressOf(0), data.bytes, data.length)
        }
        return result
    }
}
