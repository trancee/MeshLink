package ch.trancee.meshlink.crypto

import ch.trancee.meshlink.platform.android.crypto.AndroidFallbackCryptoProvider

/**
 * Exercises the pure-Kotlin [AndroidFallbackCryptoProvider] (rather than `null`/the platform JCA
 * provider) against the shared Wycheproof vectors. Devices without JCA support for X25519/XDH or
 * ChaCha20-Poly1305 fall back to this implementation at runtime, so it must be held to the same
 * adversarial-test-vector bar as the platform-backed providers.
 */
internal actual fun wycheproofProviderOrNull(): CryptoProvider? = AndroidFallbackCryptoProvider()

internal actual fun wycheproofResourceLinesOrNull(fileName: String): List<String>? {
    val classLoader = AndroidFallbackCryptoProvider::class.java.classLoader ?: return null
    return classLoader.getResourceAsStream("wycheproof/$fileName")?.bufferedReader()?.use { reader
        ->
        reader.readLines()
    }
}
