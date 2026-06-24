@file:OptIn(ch.trancee.meshlink.benchmarking.UnstableMeshLinkBenchmarkApi::class)

package ch.trancee.meshlink.benchmarks

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.benchmarking.BenchmarkCryptoProvider as JvmCryptoProvider
import ch.trancee.meshlink.benchmarking.BenchmarkLocalIdentity
import ch.trancee.meshlink.benchmarking.BenchmarkNoiseIdentity
import ch.trancee.meshlink.benchmarking.createBenchmarkLocalIdentity
import ch.trancee.meshlink.benchmarking.createBenchmarkMeshLink
import ch.trancee.meshlink.config.meshLinkConfig
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
class IdentityBootstrapBenchmark {
    private val provider = JvmCryptoProvider()
    private lateinit var peerId: PeerId
    private lateinit var config: ch.trancee.meshlink.config.MeshLinkConfig
    private lateinit var noiseIdentity: BenchmarkNoiseIdentity
    private lateinit var localIdentity: BenchmarkLocalIdentity

    @Setup
    fun prepare(): Unit {
        peerId = PeerId("benchmark-bootstrap-peer")
        config = meshLinkConfig { appId = "benchmark.mesh" }
        noiseIdentity = provider.generateNoiseIdentity()
        localIdentity = createBenchmarkLocalIdentity(noiseIdentity = noiseIdentity, peerId = peerId)
    }

    @Benchmark
    fun generateNoiseIdentity(blackhole: Blackhole): Unit {
        blackhole.consume(provider.generateNoiseIdentity())
    }

    @Benchmark
    fun realizeLocalIdentity(blackhole: Blackhole): Unit {
        blackhole.consume(
            createBenchmarkLocalIdentity(noiseIdentity = noiseIdentity, peerId = peerId)
        )
    }

    @Benchmark
    fun constructMeshLink(blackhole: Blackhole): Unit {
        blackhole.consume(createBenchmarkMeshLink(config = config, localIdentity = localIdentity))
    }
}
