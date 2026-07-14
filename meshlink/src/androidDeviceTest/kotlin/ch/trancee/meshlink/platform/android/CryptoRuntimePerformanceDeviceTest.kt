package ch.trancee.meshlink.platform.android

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.platform.android.crypto.JcaCapabilityProbe
import ch.trancee.meshlink.platform.android.crypto.JcaCryptoProviderFactory
import java.util.Locale
import kotlin.test.Test
import org.junit.runner.RunWith

/**
 * Times the crypto primitives MeshLink relies on (X25519 key agreement, Ed25519 signatures,
 * ChaCha20-Poly1305 AEAD) on real device silicon, using whichever [CryptoProvider] the runtime
 * would actually select on this device (JCA-accelerated or the pure-Kotlin fallback).
 *
 * Unlike [CryptoRuntimeValidationDeviceTest], which only asserts capability/correctness, this test
 * logs per-operation timing via Logcat (tag "CryptoBenchmark") so it can be scraped by
 * meshlink-benchmark/scripts/run_fleet_meshlink_benchmark.py across every attached device.
 */
@RunWith(AndroidJUnit4::class)
class CryptoRuntimePerformanceDeviceTest {
    @Test
    fun timeCryptoPrimitives() {
        // Arrange
        val report = JcaCapabilityProbe.detect()
        val provider = JcaCryptoProviderFactory.create(report)
        val iterations = ITERATIONS
        val deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}"

        // Act / Assert (each block times `iterations` repetitions of one primitive)
        timeOp("x25519KeyGen", iterations, deviceLabel, provider) { it.generateX25519KeyPair() }

        val alice = provider.generateX25519KeyPair()
        val bob = provider.generateX25519KeyPair()
        timeOp("x25519Agreement", iterations, deviceLabel, provider) {
            it.x25519(alice.privateKey, bob.publicKey)
        }

        timeOp("ed25519KeyGen", iterations, deviceLabel, provider) { it.generateEd25519KeyPair() }

        val signingKey = provider.generateEd25519KeyPair()
        val message = "meshlink-crypto-benchmark".encodeToByteArray()
        timeOp("ed25519Sign", iterations, deviceLabel, provider) {
            it.ed25519Sign(signingKey.privateKey, message)
        }

        val signature = provider.ed25519Sign(signingKey.privateKey, message)
        timeOp("ed25519Verify", iterations, deviceLabel, provider) {
            it.ed25519Verify(signingKey.publicKey, message, signature)
        }

        val aeadKey = provider.randomBytes(32)
        val nonce = provider.randomBytes(12)
        val aad = "meshlink-crypto-benchmark-aad".encodeToByteArray()
        val plaintext = ByteArray(256) { index -> index.toByte() }
        timeOp("chacha20Seal", iterations, deviceLabel, provider) {
            it.chacha20Poly1305Seal(aeadKey, nonce, aad, plaintext)
        }

        val ciphertext = provider.chacha20Poly1305Seal(aeadKey, nonce, aad, plaintext)
        timeOp("chacha20Open", iterations, deviceLabel, provider) {
            it.chacha20Poly1305Open(aeadKey, nonce, aad, ciphertext)
        }
    }

    private fun <T> timeOp(
        opName: String,
        iterations: Int,
        deviceLabel: String,
        provider: CryptoProvider,
        block: (CryptoProvider) -> T,
    ) {
        // Warm up the JIT/JCA provider before measuring, so cold-path overhead does not skew
        // the reported per-op average.
        repeat(WARMUP_ITERATIONS) { block(provider) }

        val startNanos = System.nanoTime()
        repeat(iterations) { block(provider) }
        val totalNanos = System.nanoTime() - startNanos
        val totalMs = totalNanos / 1_000_000.0
        val avgUs = (totalNanos / 1_000.0) / iterations

        Log.i(
            TAG,
            "CRYPTO_BENCHMARK device=$deviceLabel sdk=${Build.VERSION.SDK_INT} op=$opName " +
                "iterations=$iterations totalMs=${"%.3f".format(Locale.ROOT, totalMs)} " +
                "avgUs=${"%.3f".format(Locale.ROOT, avgUs)} provider=${provider::class.simpleName}",
        )
    }

    private companion object {
        const val TAG = "CryptoBenchmark"
        const val ITERATIONS = 200
        const val WARMUP_ITERATIONS = 10
    }
}
