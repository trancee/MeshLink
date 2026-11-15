@file:OptIn(ExperimentalForeignApi::class)

package ch.trancee.meshlink.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataAppendBytes
import platform.CoreFoundation.CFDataCreateMutable
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataGetMutableBytePtr
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecAttrSynchronizable
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memset

/**
 * The [Keychain] of this app: one generic-password item, service `ch.trancee.meshlink`, account
 * `identity`. The `CFData` that `SecItemCopyMatching` returns is immutable, so its bytes stay in
 * memory until Core Foundation frees them; [add] wipes the `CFMutableData` it hands to `SecItemAdd`.
 */
internal class IosKeychain : Keychain {
    override fun copy(): KeychainCopy =
        withQuery { query ->
            memScoped {
                val result = alloc<CFTypeRefVar>()
                val status = SecItemCopyMatching(query, result.ptr)
                val data: CFDataRef? = result.value?.reinterpret()
                val bytes = data?.let { CFDataGetBytePtr(it)?.readBytes(CFDataGetLength(it).toInt()) }
                result.value?.let(::CFRelease)
                KeychainCopy(status, bytes)
            }
        }

    override fun add(seed: ByteArray): Int = withAddition(seed) { SecItemAdd(it, null) }

    override fun delete(): Int = withItem { SecItemDelete(it) }

    /** Runs [block] with a query for the item's data. */
    internal fun <T> withQuery(block: (CFMutableDictionaryRef?) -> T): T =
        withItem { query ->
            CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
            block(query)
        }

    /** Runs [block] with the item holding a copy of [seed], which is zeroed afterwards. */
    internal fun <T> withAddition(
        seed: ByteArray,
        block: (CFMutableDictionaryRef?) -> T,
    ): T =
        withItem { item ->
            val data = CFDataCreateMutable(null, seed.size.toLong())
            seed.usePinned { CFDataAppendBytes(data, it.addressOf(0).reinterpret<UByteVar>(), seed.size.toLong()) }
            CFDictionaryAddValue(item, kSecValueData, data)
            try {
                block(item)
            } finally {
                memset(CFDataGetMutableBytePtr(data), 0, seed.size.toULong())
                CFRelease(data)
            }
        }

    /** Runs [block] with a new dictionary of the item's attributes, released afterwards. */
    internal fun <T> withItem(block: (CFMutableDictionaryRef?) -> T): T {
        val item =
            CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        val service = CFStringCreateWithCString(null, SERVICE, kCFStringEncodingUTF8)
        val account = CFStringCreateWithCString(null, ACCOUNT, kCFStringEncodingUTF8)
        CFDictionaryAddValue(item, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(item, kSecAttrService, service)
        CFDictionaryAddValue(item, kSecAttrAccount, account)
        CFDictionaryAddValue(item, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        CFDictionaryAddValue(item, kSecAttrSynchronizable, kCFBooleanFalse)
        CFRelease(service)
        CFRelease(account)
        try {
            return block(item)
        } finally {
            CFRelease(item)
        }
    }

    internal companion object {
        const val SERVICE = "ch.trancee.meshlink"
        const val ACCOUNT = "identity"
    }
}
