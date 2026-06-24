package ch.trancee.meshlink.platform.android

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CryptoRuntimeValidationDeviceTest {
    @Test
    fun runtimeReportAndSelectedProviderStayConsistent() {
        // Arrange
        val report = JcaCapabilityProbe.detect()
        val provider = JcaCryptoProviderFactory.create(report)

        // Act
        val alice = provider.generateX25519KeyPair()
        val bob = provider.generateX25519KeyPair()
        val aliceShared = provider.x25519(alice.privateKey, bob.publicKey)
        val bobShared = provider.x25519(bob.privateKey, alice.publicKey)
        val key = provider.randomBytes(32)
        val nonce = provider.randomBytes(12)
        val aad = "meshlink-runtime-device-test".encodeToByteArray()
        val plaintext = "runtime-check".encodeToByteArray()
        val ciphertext = provider.chacha20Poly1305Seal(key, nonce, aad, plaintext)
        val decrypted = provider.chacha20Poly1305Open(key, nonce, aad, ciphertext)

        // Assert
        println(
            "CRYPTO_RUNTIME_VALIDATION sdk=${Build.VERSION.SDK_INT} x25519=${report.supportsX25519} ed25519=${report.supportsEd25519} chacha=${report.supportsChaCha20Poly1305} meshRuntime=${report.supportsMeshLinkRuntime} provider=${provider::class.simpleName}"
        )
        assertContentEquals(aliceShared, bobShared)
        assertTrue(aliceShared.any { it != 0.toByte() })
        assertContentEquals(plaintext, decrypted)
        if (!report.supportsMeshLinkRuntime) {
            assertTrue(provider is AndroidFallbackCryptoProvider)
        }
    }

    @Test
    fun api26Through28RuntimesStillNeedFallbackPathForFullMeshRuntime() {
        // Arrange
        val report = JcaCapabilityProbe.detect()

        // Act / Assert
        if (Build.VERSION.SDK_INT in 26..28) {
            assertFalse(report.supportsMeshLinkRuntime)
        }
    }
}
