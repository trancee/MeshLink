package ch.trancee.meshlink.internal

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.StrongBoxUnavailableException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreException
import java.security.KeyStoreSpi
import java.security.ProviderException
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * A stand-in for the `AndroidKeyStore` JCA provider, which Robolectric lacks: AES keys in memory,
 * generated from the [KeyGenParameterSpec] it records. Its keys are ordinary [SecretKeySpec]s, so
 * the JDK's AES-GCM encrypts with them. It can't prove what real Keystore hardware does.
 */
internal class FakeAndroidKeyStore : java.security.Provider("AndroidKeyStore", 1.0, "Android Keystore stand-in") {
    init {
        put("KeyStore.AndroidKeyStore", Store::class.java.name)
        put("KeyGenerator.AES", Generator::class.java.name)
    }

    /** The provider's key store: the keys of [keys]. */
    class Store : KeyStoreSpi() {
        override fun engineLoad(
            stream: InputStream?,
            password: CharArray?,
        ) {
            if (unavailable) throw IOException("Keystore unavailable")
        }

        override fun engineGetKey(
            alias: String,
            password: CharArray?,
        ): Key? = keys[alias]

        override fun engineContainsAlias(alias: String): Boolean = alias in keys

        override fun engineDeleteEntry(alias: String) {
            if (deleteFails) throw KeyStoreException("delete refused")
            keys.remove(alias)
        }

        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())

        override fun engineSize(): Int = keys.size

        override fun engineIsKeyEntry(alias: String): Boolean = alias in keys

        override fun engineIsCertificateEntry(alias: String): Boolean = false

        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null

        override fun engineGetCertificate(alias: String): Certificate? = null

        override fun engineGetCertificateAlias(cert: Certificate): String? = null

        override fun engineGetCreationDate(alias: String): Date? = null

        override fun engineSetKeyEntry(
            alias: String,
            key: Key,
            password: CharArray?,
            chain: Array<out Certificate>?,
        ): Unit = throw UnsupportedOperationException()

        override fun engineSetKeyEntry(
            alias: String,
            key: ByteArray,
            chain: Array<out Certificate>?,
        ): Unit = throw UnsupportedOperationException()

        override fun engineSetCertificateEntry(
            alias: String,
            cert: Certificate,
        ): Unit = throw UnsupportedOperationException()

        override fun engineStore(
            stream: OutputStream?,
            password: CharArray?,
        ): Unit = throw UnsupportedOperationException()
    }

    /** The provider's AES key generator: records each spec in [specs] and stores the key in [keys]. */
    class Generator : KeyGeneratorSpi() {
        private lateinit var spec: KeyGenParameterSpec

        override fun engineInit(
            params: AlgorithmParameterSpec,
            random: SecureRandom?,
        ) {
            spec = params as KeyGenParameterSpec
            specs += spec
        }

        override fun engineInit(random: SecureRandom?): Unit = throw UnsupportedOperationException()

        override fun engineInit(
            keysize: Int,
            random: SecureRandom?,
        ): Unit = throw UnsupportedOperationException()

        override fun engineGenerateKey(): SecretKey {
            if (generationFails) throw ProviderException("key generation failed")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && spec.isStrongBoxBacked && !hasStrongBox) {
                throw StrongBoxUnavailableException()
            }
            val key = SecretKeySpec(ByteArray(spec.keySize / Byte.SIZE_BITS).also(SecureRandom()::nextBytes), "AES")
            keys[spec.keystoreAlias] = key
            return key
        }
    }

    companion object {
        /** The stored keys by alias. */
        val keys = mutableMapOf<String, SecretKey>()

        /** Every spec a key was generated from, in order. */
        val specs = mutableListOf<KeyGenParameterSpec>()

        /** Whether the device has StrongBox; without it a StrongBox key fails to generate. */
        var hasStrongBox = true

        /** Whether loading the key store fails, as while the Keystore daemon is unreachable. */
        var unavailable = false

        /** Whether key generation fails. */
        var generationFails = false

        /** Whether deleting a key fails. */
        var deleteFails = false

        /** Installs a fresh provider ahead of all others. */
        fun install() {
            keys.clear()
            specs.clear()
            hasStrongBox = true
            unavailable = false
            generationFails = false
            deleteFails = false
            Security.insertProviderAt(FakeAndroidKeyStore(), 1)
        }

        /** Removes the provider. */
        fun uninstall() {
            Security.removeProvider("AndroidKeyStore")
        }
    }
}
