@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package ch.trancee.meshlink.benchmarks

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.trust.TrustPublicKeys
import ch.trancee.meshlink.trust.TrustRecord
import ch.trancee.meshlink.wire.WireFrame
import java.util.concurrent.TimeUnit
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class ConvergenceBenchmark {
    @Benchmark
    fun reconvergeTenNodeTopologyChange(): Int {
        val topology = BenchmarkTopology(nodeCount = 10)
        topology.connectChain()
        topology.connect(4, 8)
        topology.disconnect(4, 5)
        return topology.totalKnownRoutes()
    }
}

private class BenchmarkTopology(nodeCount: Int) {
    private val nodes: List<PeerId> = List(nodeCount) { index -> PeerId("node-$index") }
    private val coordinators: Map<String, RouteCoordinator> = nodes.associate { peerId ->
        peerId.value to RouteCoordinator(peerId)
    }
    private val adjacency: MutableMap<String, MutableSet<String>> =
        nodes.associate { peerId -> peerId.value to linkedSetOf<String>() }.toMutableMap()

    fun connectChain(): Unit {
        repeat(nodes.lastIndex) { index -> connect(index, index + 1) }
    }

    fun connect(leftIndex: Int, rightIndex: Int): Unit {
        val left = nodes[leftIndex]
        val right = nodes[rightIndex]
        if (!adjacency.getValue(left.value).add(right.value)) {
            return
        }
        adjacency.getValue(right.value).add(left.value)
        enqueueAndPropagate(
            sender = left,
            advertisements =
                RouteCoordinatorAccess.onPeerConnected(
                    coordinator = coordinator(left),
                    peerId = right,
                    trustRecord = trustRecord(right),
                ),
        )
        enqueueAndPropagate(
            sender = right,
            advertisements =
                RouteCoordinatorAccess.onPeerConnected(
                    coordinator = coordinator(right),
                    peerId = left,
                    trustRecord = trustRecord(left),
                ),
        )
    }

    fun disconnect(leftIndex: Int, rightIndex: Int): Unit {
        val left = nodes[leftIndex]
        val right = nodes[rightIndex]
        if (!adjacency.getValue(left.value).remove(right.value)) {
            return
        }
        adjacency.getValue(right.value).remove(left.value)
        enqueueAndPropagate(
            sender = left,
            advertisements = RouteCoordinatorAccess.onPeerDisconnected(coordinator(left), right),
        )
        enqueueAndPropagate(
            sender = right,
            advertisements = RouteCoordinatorAccess.onPeerDisconnected(coordinator(right), left),
        )
    }

    fun totalKnownRoutes(): Int {
        return nodes.sumOf { node ->
            nodes.count { destination ->
                RouteCoordinatorAccess.hasRoute(coordinator(node), destination)
            }
        }
    }

    private fun enqueueAndPropagate(
        sender: PeerId,
        advertisements: List<BenchmarkAdvertisement>,
    ): Unit {
        val queue = kotlin.collections.ArrayDeque<PendingAdvertisement>()
        advertisements.forEach { advertisement ->
            queue += PendingAdvertisement(sender = sender, advertisement = advertisement)
        }
        while (queue.isNotEmpty()) {
            val pending = queue.removeFirst()
            val targetPeerId = pending.advertisement.targetPeerId
            if (!adjacency.getValue(pending.sender.value).contains(targetPeerId.value)) {
                continue
            }
            val followUps =
                when (val frame = pending.advertisement.frame) {
                    is WireFrame.RouteUpdate ->
                        RouteCoordinatorAccess.onRouteUpdate(
                            coordinator = coordinator(targetPeerId),
                            fromPeerId = pending.sender,
                            update = frame,
                        )

                    is WireFrame.RouteRetraction ->
                        RouteCoordinatorAccess.onRouteRetraction(
                            coordinator = coordinator(targetPeerId),
                            fromPeerId = pending.sender,
                            retraction = frame,
                        )

                    is WireFrame.RouteDigest -> {
                        RouteCoordinatorAccess.onRouteDigest(
                            coordinator = coordinator(targetPeerId),
                            fromPeerId = pending.sender,
                            digest = frame,
                        )
                        emptyList()
                    }

                    else -> emptyList()
                }
            followUps.forEach { followUp ->
                queue += PendingAdvertisement(sender = targetPeerId, advertisement = followUp)
            }
        }
    }

    private fun coordinator(peerId: PeerId): RouteCoordinator = coordinators.getValue(peerId.value)

    private fun trustRecord(peerId: PeerId): TrustRecord {
        val seed = peerId.value.removePrefix("node-").toIntOrNull() ?: 0
        return TrustRecord(
            peerIdValue = peerId.value,
            identityFingerprintBytes =
                byteArrayOf(((seed + 73) and 0xFF).toByte()).repeatToLength(32),
            firstSeenAtEpochMillis = seed.toLong(),
            lastVerifiedAtEpochMillis = seed.toLong(),
            publicKeys =
                TrustPublicKeys(
                    ed25519PublicKey = byteArrayOf((seed and 0xFF).toByte()).repeatToLength(32),
                    x25519PublicKey =
                        byteArrayOf(((seed + 41) and 0xFF).toByte()).repeatToLength(32),
                ),
        )
    }

    private fun ByteArray.repeatToLength(length: Int): ByteArray {
        return ByteArray(length) { index -> this[index % size] }
    }
}

private class PendingAdvertisement
internal constructor(
    internal val sender: PeerId,
    internal val advertisement: BenchmarkAdvertisement,
)
