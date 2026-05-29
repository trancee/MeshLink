@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package ch.trancee.meshlink.benchmarks

import ch.trancee.meshlink.crypto.JvmCryptoProvider
import java.util.concurrent.TimeUnit
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
class CryptoBenchmark {
    private val provider = JvmCryptoProvider()
    private lateinit var key: ByteArray
    private lateinit var decryptNonce: ByteArray
    private lateinit var encryptionNonces: Array<ByteArray>
    private lateinit var aad: ByteArray
    private lateinit var plaintext: ByteArray
    private lateinit var ciphertext: ByteArray
    private lateinit var alicePrivateKey: ByteArray
    private lateinit var bobPublicKey: ByteArray
    private var nextEncryptionNonceIndex: Int = 0

    @Setup
    fun prepare(): Unit {
        key = provider.randomBytes(32)
        decryptNonce = provider.randomBytes(12)
        encryptionNonces = Array(ENCRYPTION_NONCE_POOL_SIZE, ::createEncryptionNonce)
        nextEncryptionNonceIndex = 0
        aad = "meshlink-benchmark".encodeToByteArray()
        plaintext = ByteArray(512) { index -> (index and 0xFF).toByte() }
        ciphertext = provider.chacha20Poly1305Seal(key, decryptNonce, aad, plaintext)
        val alice = provider.generateX25519KeyPair()
        val bob = provider.generateX25519KeyPair()
        alicePrivateKey = alice.privateKey
        bobPublicKey = bob.publicKey
    }

    @Benchmark
    fun aeadEncrypt(blackhole: Blackhole): Unit {
        blackhole.consume(provider.chacha20Poly1305Seal(key, nextEncryptionNonce(), aad, plaintext))
    }

    @Benchmark
    fun aeadDecrypt(blackhole: Blackhole): Unit {
        blackhole.consume(provider.chacha20Poly1305Open(key, decryptNonce, aad, ciphertext))
    }

    @Benchmark
    fun x25519Agreement(blackhole: Blackhole): Unit {
        blackhole.consume(provider.x25519(alicePrivateKey, bobPublicKey))
    }

    private fun createEncryptionNonce(index: Int): ByteArray {
        // The JCA ChaCha20-Poly1305 cipher rejects re-initialization with the same key+nonce pair
        // on one cipher instance. Rotate deterministic benchmark nonces so the encrypt benchmark
        // measures encryption work instead of tripping that provider safeguard.
        val counter = index + 1
        return decryptNonce.copyOf().apply {
            this[0] = (this[0].toInt() xor 0x80).toByte()
            this[8] = (counter ushr 24).toByte()
            this[9] = (counter ushr 16).toByte()
            this[10] = (counter ushr 8).toByte()
            this[11] = counter.toByte()
        }
    }

    private fun nextEncryptionNonce(): ByteArray {
        val nonce = encryptionNonces[nextEncryptionNonceIndex]
        nextEncryptionNonceIndex = (nextEncryptionNonceIndex + 1) % encryptionNonces.size
        return nonce
    }

    private companion object {
        private const val ENCRYPTION_NONCE_POOL_SIZE: Int = 1024
    }
}
