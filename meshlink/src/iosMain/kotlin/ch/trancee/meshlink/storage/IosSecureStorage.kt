@file:Suppress("WildcardImport")
@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package ch.trancee.meshlink.storage

import cnames.structs.__CFDictionary
import kotlinx.cinterop.*
import platform.CoreFoundation.CFRelease
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

    // ── CF ↔ ObjC bridge helpers ──────────────────────────────────────────────

    /**
     * Toll-free bridge: reinterprets a CoreFoundation pointer (CFStringRef, CFTypeRef, etc.) as the
     * equivalent Foundation object for use with NSDictionary APIs.
     */
    private fun cfToNS(ref: CPointer<*>?): Any {
        requireNotNull(ref) { "Null CF reference" }
        return interpretObjCPointerOrNull<ObjCObject>(ref.rawValue)
            ?: error("Cannot interpret CF pointer as ObjC object")
    }

    /**
     * Toll-free bridge: obtains a CFDictionaryRef from an NSMutableDictionary. The returned pointer
     * is only valid while the Kotlin reference to the dictionary is alive.
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun NSMutableDictionary.cfDictRef(): CPointer<__CFDictionary> =
        interpretCPointer<__CFDictionary>(this.objcPtr()) ?: error("Null NSDictionary objcPtr")

    /**
     * Convenience: sets a key-value pair using toll-free bridged CF references as keys. Avoids the
     * NSCopyingProtocol cast boilerplate at each call site.
     */
    private fun NSMutableDictionary.cfPut(key: CPointer<*>?, value: Any?) {
        @Suppress("UNCHECKED_CAST") setObject(value, forKey = cfToNS(key) as NSCopyingProtocol)
    }

    // ── Base query ────────────────────────────────────────────────────────────

    private fun baseQuery(key: String): NSMutableDictionary =
        NSMutableDictionary().apply {
            cfPut(kSecClass, cfToNS(kSecClassGenericPassword))
            cfPut(kSecAttrService, SERVICE)
            cfPut(kSecAttrAccount, key)
            cfPut(kSecAttrAccessible, cfToNS(kSecAttrAccessibleAfterFirstUnlock))
        }

    // ── SecureStorage implementation ──────────────────────────────────────────

    override fun get(key: String): ByteArray? {
        val query =
            baseQuery(key).apply {
                cfPut(kSecReturnData, true)
                cfPut(kSecMatchLimit, cfToNS(kSecMatchLimitOne))
            }
        return memScoped {
            val resultRef = alloc<COpaquePointerVar>()
            val status = SecItemCopyMatching(query.cfDictRef(), resultRef.ptr)

            if (status == errSecItemNotFound) return null
            if (status != errSecSuccess) {
                println("IosSecureStorage get($key) failed status=$status")
                return null
            }

            val resultPtr = resultRef.value ?: return null
            try {
                val data = interpretObjCPointerOrNull<NSData>(resultPtr.rawValue) ?: return null
                nsDataToByteArray(data)
            } finally {
                // Balance the +1 CF retain from SecItemCopyMatching
                CFRelease(resultPtr)
            }
        }
    }

    override fun put(key: String, value: ByteArray) {
        val nsData = byteArrayToNSData(value)

        // Try add first
        val addQuery = baseQuery(key).apply { cfPut(kSecValueData, nsData) }
        val addStatus = SecItemAdd(addQuery.cfDictRef(), null)

        if (addStatus == errSecDuplicateItem) {
            // Item exists — update it
            val findQuery = baseQuery(key)
            val updateAttrs = NSMutableDictionary().apply { cfPut(kSecValueData, nsData) }
            val updateStatus = SecItemUpdate(findQuery.cfDictRef(), updateAttrs.cfDictRef())
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
        val status = SecItemDelete(query.cfDictRef())
        if (status != errSecSuccess && status != errSecItemNotFound) {
            println("IosSecureStorage remove($key) failed status=$status")
        }
    }

    override fun contains(key: String): Boolean {
        val query =
            baseQuery(key).apply {
                cfPut(kSecReturnData, false)
                cfPut(kSecMatchLimit, cfToNS(kSecMatchLimitOne))
            }
        val status = SecItemCopyMatching(query.cfDictRef(), null)
        return status == errSecSuccess
    }

    // ── NSData ↔ ByteArray helpers ────────────────────────────────────────────

    private fun byteArrayToNSData(bytes: ByteArray): NSData {
        if (bytes.isEmpty()) return NSData.data()
        return bytes.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
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
