package ch.trancee.meshlink.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JCA-backed symmetric-primitive plumbing shared verbatim between [JvmCryptoProvider] (`jvmMain`)
 * and Android's `JcaCryptoProvider` (`platform.android.crypto`, `androidMain`) -- both platforms
 * use the exact same `MessageDigest`/`Mac`/`Cipher` algorithm names and thread-local caching
 * strategy for SHA-256, HMAC-SHA256, and ChaCha20-Poly1305, since these are ordinary JCA algorithms
 * with no platform-specific behavior difference between the JVM and Android's JCA implementation.
 * Only X25519/Ed25519 key (de)serialization genuinely differs between the two platforms'
 * `KeyFactory` APIs (Android's JCA provider doesn't support the JVM's structured
 * `XECPrivateKey`/`EdECPrivateKey` key types, so it round-trips through raw PKCS#8/X.509 DER
 * instead) -- that logic intentionally stays in each platform's own `CryptoProvider`
 * implementation, not here.
 *
 * Lives in the `jvmAndroidMain` intermediate source set (`meshlink/build.gradle.kts`), a hand-wired
 * `dependsOn` link between `jvmMain` and `androidMain` that `applyDefaultHierarchyTemplate()` does
 * not create by default (its default hierarchy only groups Android with the Kotlin/Native targets,
 * not with the JVM target). This is a compile-time code-sharing seam only: it introduces no new
 * dependency, and nothing in it is reachable from `commonMain`, `iosMain`, or any other target.
 */
internal class JcaSymmetricPrimitives {
    private val secureRandom: SecureRandom = SecureRandom()
    private val sha256Digests = threadLocal { MessageDigest.getInstance("SHA-256") }
    private val hmacSha256Macs = threadLocal { Mac.getInstance("HmacSHA256") }
    private val chacha20Poly1305EncryptCiphers = threadLocal {
        Cipher.getInstance("ChaCha20-Poly1305")
    }
    private val chacha20Poly1305DecryptCiphers = threadLocal {
        Cipher.getInstance("ChaCha20-Poly1305")
    }

    internal fun randomBytes(size: Int): ByteArray {
        return ByteArray(size).also(secureRandom::nextBytes)
    }

    internal fun sha256(input: ByteArray): ByteArray {
        return sha256Digests.value().digest(input)
    }

    internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = hmacSha256Macs.value()
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    internal fun chacha20Poly1305Seal(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        val cipher = chacha20Poly1305EncryptCiphers.value()
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    internal fun chacha20Poly1305Open(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        val cipher = chacha20Poly1305DecryptCiphers.value()
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun <T> threadLocal(create: () -> T): ThreadLocal<T> {
        return object : ThreadLocal<T>() {
            override fun initialValue(): T {
                return create()
            }
        }
    }

    private fun <T> ThreadLocal<T>.value(): T {
        return checkNotNull(get())
    }
}
