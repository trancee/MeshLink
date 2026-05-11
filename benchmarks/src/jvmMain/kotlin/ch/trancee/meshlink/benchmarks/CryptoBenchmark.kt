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
    private lateinit var nonce: ByteArray
    private lateinit var aad: ByteArray
    private lateinit var plaintext: ByteArray
    private lateinit var ciphertext: ByteArray
    private lateinit var alicePrivateKey: ByteArray
    private lateinit var bobPublicKey: ByteArray

    @Setup
    fun prepare(): Unit {
        key = provider.randomBytes(32)
        nonce = provider.randomBytes(12)
        aad = "meshlink-benchmark".encodeToByteArray()
        plaintext = ByteArray(512) { index -> (index and 0xFF).toByte() }
        ciphertext = provider.chacha20Poly1305Seal(key, nonce, aad, plaintext)
        val alice = provider.generateX25519KeyPair()
        val bob = provider.generateX25519KeyPair()
        alicePrivateKey = alice.privateKey
        bobPublicKey = bob.publicKey
    }

    @Benchmark
    fun aeadEncrypt(blackhole: Blackhole): Unit {
        blackhole.consume(provider.chacha20Poly1305Seal(key, nonce, aad, plaintext))
    }

    @Benchmark
    fun aeadDecrypt(blackhole: Blackhole): Unit {
        blackhole.consume(provider.chacha20Poly1305Open(key, nonce, aad, ciphertext))
    }

    @Benchmark
    fun x25519Agreement(blackhole: Blackhole): Unit {
        blackhole.consume(provider.x25519(alicePrivateKey, bobPublicKey))
    }
}
