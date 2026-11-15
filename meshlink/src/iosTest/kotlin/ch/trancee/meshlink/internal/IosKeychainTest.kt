@file:OptIn(ExperimentalForeignApi::class)

package ch.trancee.meshlink.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryGetCount
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFStringCompare
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFCompareEqualTo
import platform.CoreFoundation.kCFStringEncodingUTF8
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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosKeychainTest {
    @Test
    fun theItemIsALocalGenericPasswordReadableAfterFirstUnlock() {
        // Act
        val matches =
            IosKeychain().withItem { item ->
                // The item holds the Security constants themselves, so identity compares them.
                fun has(
                    key: CFTypeRef?,
                    value: CFTypeRef?,
                ) = CFDictionaryGetValue(item, key) == value

                fun hasText(
                    key: CFTypeRef?,
                    text: String,
                ): Boolean {
                    val expected = CFStringCreateWithCString(null, text, kCFStringEncodingUTF8)
                    val actual: CFStringRef? = CFDictionaryGetValue(item, key)?.reinterpret()
                    val equal = CFStringCompare(actual, expected, 0u) == kCFCompareEqualTo
                    CFRelease(expected)
                    return equal
                }

                listOf(
                    CFDictionaryGetCount(item).toInt() == ATTRIBUTES,
                    has(kSecClass, kSecClassGenericPassword),
                    hasText(kSecAttrService, "ch.trancee.meshlink"),
                    hasText(kSecAttrAccount, "identity"),
                    has(kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly),
                    has(kSecAttrSynchronizable, kCFBooleanFalse),
                )
            }

        // Assert
        assertEquals(List(ATTRIBUTES + 1) { true }, matches)
    }

    @Test
    fun theQueryAsksForTheDataOfOneItem() {
        // Act
        val asks =
            IosKeychain().withQuery { query ->
                listOf(
                    CFDictionaryGetValue(query, kSecReturnData) == kCFBooleanTrue,
                    CFDictionaryGetValue(query, kSecMatchLimit) == kSecMatchLimitOne,
                    CFDictionaryGetValue(query, kSecClass) == kSecClassGenericPassword,
                )
            }

        // Assert
        assertEquals(listOf(true, true, true), asks)
    }

    @Test
    fun theAdditionHoldsTheSeedAndZeroesItAfterwards() {
        // Arrange
        val seed = ByteArray(SEED_SIZE) { (it + 1).toByte() }
        var data: CFDataRef? = null

        // Act
        val held =
            IosKeychain().withAddition(seed) { item ->
                data = CFDictionaryGetValue(item, kSecValueData)?.reinterpret()
                CFRetain(data)
                CFDictionaryGetValue(item, kSecClass) == kSecClassGenericPassword
            }
        val kept = data?.let { CFDataGetBytePtr(it)?.readBytes(CFDataGetLength(it).toInt()) }
        val before = IosKeychain().withAddition(seed) { item -> bytesOf(item) }
        CFRelease(data)

        // Assert
        assertTrue(held)
        assertContentEquals(seed, before)
        assertContentEquals(ByteArray(SEED_SIZE), kept)
    }

    @Test
    fun theSimulatorTestHostHasNoKeychainButTakesEachWellFormedCall() {
        // Arrange: a malformed query fails with errSecParam (-50) instead.
        val keychain = IosKeychain()

        // Act
        val statuses = listOf(keychain.copy().status, keychain.add(ByteArray(SEED_SIZE)), keychain.delete())

        // Assert
        assertTrue(statuses.all { it == ERR_SEC_NOT_AVAILABLE }, "$statuses")
    }

    private fun bytesOf(item: CFMutableDictionaryRef?): ByteArray? {
        val data: CFDataRef? = CFDictionaryGetValue(item, kSecValueData)?.reinterpret()
        return data?.let { CFDataGetBytePtr(it)?.readBytes(CFDataGetLength(it).toInt()) }
    }

    private companion object {
        const val ATTRIBUTES = 5
        const val SEED_SIZE = 32
        const val ERR_SEC_NOT_AVAILABLE = -25291
    }
}
