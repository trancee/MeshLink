@file:OptIn(ch.trancee.meshlink.benchmarking.UnstableMeshLinkBenchmarkApi::class)

package ch.trancee.meshlink.benchmarks

import ch.trancee.meshlink.benchmarking.BenchmarkCryptoProvider
import ch.trancee.meshlink.benchmarking.BenchmarkPureX25519Provider
import java.util.concurrent.TimeUnit
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
class X25519ProviderBenchmark {
    @Param("jvm", "pure") lateinit var providerKind: String

    private lateinit var provider: X25519BenchmarkProvider
    private lateinit var alicePrivateKey: ByteArray
    private lateinit var bobPublicKey: ByteArray

    @Setup
    fun prepare(): Unit {
        provider =
            when (providerKind) {
                "jvm" -> JvmProviderAdapter(BenchmarkCryptoProvider())
                "pure" -> PureProviderAdapter(BenchmarkPureX25519Provider())
                else -> error("Unsupported providerKind=$providerKind")
            }
        val alice = provider.generateX25519KeyPair()
        val bob = provider.generateX25519KeyPair()
        alicePrivateKey = alice.privateKey
        bobPublicKey = bob.publicKey
    }

    @Benchmark
    fun generateX25519KeyPair(blackhole: Blackhole): Unit {
        blackhole.consume(provider.generateX25519KeyPair())
    }

    @Benchmark
    fun x25519Agreement(blackhole: Blackhole): Unit {
        blackhole.consume(provider.x25519(alicePrivateKey, bobPublicKey))
    }

    private interface X25519BenchmarkProvider {
        fun generateX25519KeyPair(): BenchmarkX25519KeyPairLike

        fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray
    }

    private class JvmProviderAdapter(private val delegate: BenchmarkCryptoProvider) :
        X25519BenchmarkProvider {
        override fun generateX25519KeyPair(): BenchmarkX25519KeyPairLike {
            val keyPair = delegate.generateX25519KeyPair()
            return BenchmarkX25519KeyPairLike(keyPair.privateKey, keyPair.publicKey)
        }

        override fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
            return delegate.x25519(privateKey, publicKey)
        }
    }

    private class PureProviderAdapter(private val delegate: BenchmarkPureX25519Provider) :
        X25519BenchmarkProvider {
        override fun generateX25519KeyPair(): BenchmarkX25519KeyPairLike {
            val keyPair = delegate.generateX25519KeyPair()
            return BenchmarkX25519KeyPairLike(keyPair.privateKey, keyPair.publicKey)
        }

        override fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
            return delegate.x25519(privateKey, publicKey)
        }
    }

    private class BenchmarkX25519KeyPairLike(val privateKey: ByteArray, val publicKey: ByteArray)
}
