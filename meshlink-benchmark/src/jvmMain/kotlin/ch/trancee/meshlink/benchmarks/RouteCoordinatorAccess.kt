@file:OptIn(ch.trancee.meshlink.benchmarking.UnstableMeshLinkBenchmarkApi::class)

package ch.trancee.meshlink.benchmarks

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.benchmarking.BenchmarkAdvertisement
import ch.trancee.meshlink.benchmarking.BenchmarkRouteCoordinator as RouteCoordinator
import ch.trancee.meshlink.benchmarking.BenchmarkTrustRecord as TrustRecord
import ch.trancee.meshlink.benchmarking.BenchmarkWireFrame as WireFrame

internal object RouteCoordinatorAccess {
    internal fun onPeerConnected(
        coordinator: RouteCoordinator,
        peerId: PeerId,
        trustRecord: TrustRecord,
    ): List<BenchmarkAdvertisement> {
        return coordinator.onPeerConnected(peerId = peerId, trustRecord = trustRecord)
    }

    internal fun onPeerDisconnected(
        coordinator: RouteCoordinator,
        peerId: PeerId,
    ): List<BenchmarkAdvertisement> {
        return coordinator.onPeerDisconnected(peerId = peerId)
    }

    internal fun onRouteUpdate(
        coordinator: RouteCoordinator,
        fromPeerId: PeerId,
        update: WireFrame.RouteUpdate,
    ): List<BenchmarkAdvertisement> {
        return coordinator.onRouteUpdate(fromPeerId = fromPeerId, update = update)
    }

    internal fun onRouteRetraction(
        coordinator: RouteCoordinator,
        fromPeerId: PeerId,
        retraction: WireFrame.RouteRetraction,
    ): List<BenchmarkAdvertisement> {
        return coordinator.onRouteRetraction(fromPeerId = fromPeerId, retraction = retraction)
    }

    internal fun onRouteDigest(
        coordinator: RouteCoordinator,
        fromPeerId: PeerId,
        digest: WireFrame.RouteDigest,
    ): Unit {
        coordinator.onRouteDigest(fromPeerId = fromPeerId, digest = digest)
    }

    internal fun nextHopFor(coordinator: RouteCoordinator, destinationPeerId: PeerId): PeerId? {
        return coordinator.nextHopFor(destinationPeerId = destinationPeerId)
    }

    internal fun hasRoute(coordinator: RouteCoordinator, destinationPeerId: PeerId): Boolean {
        return coordinator.hasRoute(destinationPeerId = destinationPeerId)
    }
}
