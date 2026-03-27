package io.meshlink.storage

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryRef
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitAll
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnAttributes
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.darwin.OSStatus
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.refTo
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFTypeRefVar
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataWithBytes
import platform.posix.memcpy

/**
 * iOS [SecureStorage] backed by the system Keychain.
 *
 * Each entry is stored as a `kSecClassGenericPassword` item with
 * [SERVICE_NAME] as the service and the storage key as the account.
 * Values are persisted as raw [NSData] blobs and protected with
 * [kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly].
 */
@OptIn(ExperimentalForeignApi::class)
class IosSecureStorage : SecureStorage {

    override fun put(key: String, value: ByteArray) {
        val data = value.toNSData()

        val query = baseQuery(key)
        val status: OSStatus = SecItemCopyMatching(query.toCFDictionary(), null)

        if (status == errSecSuccess) {
            // Item already exists — update it.
            val update = mapOf<Any?, Any?>(kSecValueData to data)
            SecItemUpdate(query.toCFDictionary(), update.toCFDictionary())
        } else {
            // Insert a new item.
            val attrs = query.toMutableMap().apply {
                put(kSecValueData, data)
                put(kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
            }
            SecItemAdd(attrs.toCFDictionary(), null)
        }
    }

    override fun get(key: String): ByteArray? {
        val query = baseQuery(key).toMutableMap().apply {
            put(kSecReturnData, true)
            put(kSecMatchLimit, kSecMatchLimitOne)
        }

        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query.toCFDictionary(), result.ptr)
            if (status != errSecSuccess) return null

            val data = CFBridgingRelease(result.value) as? NSData ?: return null
            return data.toByteArray()
        }
    }

    override fun delete(key: String) {
        SecItemDelete(baseQuery(key).toCFDictionary())
    }

    override fun clear() {
        val query = mapOf<Any?, Any?>(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to SERVICE_NAME,
        )
        SecItemDelete(query.toCFDictionary())
    }

    override fun size(): Int {
        val query = mapOf<Any?, Any?>(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to SERVICE_NAME,
            kSecReturnAttributes to true,
            kSecMatchLimit to kSecMatchLimitAll,
        )

        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query.toCFDictionary(), result.ptr)
            if (status == errSecItemNotFound) return 0
            if (status != errSecSuccess) return 0

            val items = CFBridgingRelease(result.value) as? List<*> ?: return 0
            return items.size
        }
    }

    // ---- internal helpers ----

    private fun baseQuery(key: String): Map<Any?, Any?> = mapOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to SERVICE_NAME,
        kSecAttrAccount to key,
    )

    private companion object {
        const val SERVICE_NAME = "io.meshlink.securestorage"
    }
}

// ---- ByteArray ↔ NSData conversion helpers ----

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), size.convert())
        }
    }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val length = this.length.toInt()
    if (length == 0) return byteArrayOf()
    val bytes = ByteArray(length)
    bytes.usePinned { pinned ->
        memcpy(pinned.addressOf(0), this.bytes, this.length)
    }
    return bytes
}

/**
 * Bridge a Kotlin [Map] to a Core Foundation [CFDictionaryRef]
 * via `CFBridgingRetain` on the NSDictionary representation.
 */
@OptIn(ExperimentalForeignApi::class)
@Suppress("UNCHECKED_CAST")
private fun Map<Any?, Any?>.toCFDictionary(): CFDictionaryRef? {
    val nsDict = this as Map<Any, Any> // NSDictionary bridge
    return CFBridgingRetain(nsDict) as CFDictionaryRef?
}
