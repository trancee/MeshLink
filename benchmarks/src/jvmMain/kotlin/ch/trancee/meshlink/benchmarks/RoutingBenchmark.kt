@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package ch.trancee.meshlink.benchmarks

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.trust.TrustRecord
import ch.trancee.meshlink.wire.WireFrame
import java.util.concurrent.TimeUnit
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
class RoutingBenchmark {
    @Param("64", "256", "1024") var routeCount: Int = 0

    private lateinit var coordinator: RouteCoordinator
    private lateinit var lookupTarget: PeerId
    private lateinit var relays: List<PeerId>

    @Setup
    fun prepare(): Unit {
        coordinator = RouteCoordinator(PeerId("local-benchmark"))
        relays = List(8) { index -> PeerId("relay-$index") }
        relays.forEachIndexed { index, relayPeerId ->
            RouteCoordinatorAccess.onPeerConnected(
                coordinator = coordinator,
                peerId = relayPeerId,
                trustRecord = trustRecord(peerId = relayPeerId, seed = index + 1),
            )
        }
        repeat(routeCount) { index ->
            val relay = relays[index % relays.size]
            val destination = PeerId("peer-$index")
            RouteCoordinatorAccess.onRouteUpdate(
                coordinator = coordinator,
                fromPeerId = relay,
                update =
                    WireFrame.RouteUpdate(
                        destinationPeerId = destination,
                        nextHopPeerId = relay,
                        metric = 1 + (index % 4),
                        seqNo = index.toLong() + 1L,
                        feasibilityMetric = 1 + (index % 4),
                        destinationEd25519PublicKey =
                            byteArrayOf((index and 0xFF).toByte()).repeatToLength(32),
                        destinationX25519PublicKey =
                            byteArrayOf(((index + 17) and 0xFF).toByte()).repeatToLength(32),
                    ),
            )
        }
        lookupTarget = PeerId("peer-${routeCount - 1}")
    }

    @Benchmark
    fun nextHopLookup(): String {
        return RouteCoordinatorAccess.nextHopFor(coordinator, lookupTarget)?.value.orEmpty()
    }

    private fun trustRecord(peerId: PeerId, seed: Int): TrustRecord {
        return TrustRecord(
            peerIdValue = peerId.value,
            identityFingerprintBytes = byteArrayOf(((seed + 59) and 0xFF).toByte()).repeatToLength(32),
            firstSeenAtEpochMillis = seed.toLong(),
            lastVerifiedAtEpochMillis = seed.toLong(),
            ed25519PublicKey = byteArrayOf((seed and 0xFF).toByte()).repeatToLength(32),
            x25519PublicKey = byteArrayOf(((seed + 31) and 0xFF).toByte()).repeatToLength(32),
        )
    }

    private fun ByteArray.repeatToLength(length: Int): ByteArray {
        return ByteArray(length) { index -> this[index % size] }
    }
}
