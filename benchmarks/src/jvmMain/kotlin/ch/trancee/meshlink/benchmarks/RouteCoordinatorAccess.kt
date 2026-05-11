@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package ch.trancee.meshlink.benchmarks

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.routing.RoutingAdvertisement
import ch.trancee.meshlink.trust.TrustRecord
import ch.trancee.meshlink.wire.WireFrame

internal class BenchmarkAdvertisement
internal constructor(internal val targetPeerId: PeerId, internal val frame: WireFrame)

internal object RouteCoordinatorAccess {
    private val onPeerConnected =
        RouteCoordinator::class
            .java
            .getMethod("onPeerConnected\$meshlink", PeerId::class.java, TrustRecord::class.java)
    private val onPeerDisconnected =
        RouteCoordinator::class.java.getMethod("onPeerDisconnected\$meshlink", PeerId::class.java)
    private val onRouteUpdate =
        RouteCoordinator::class
            .java
            .getMethod(
                "onRouteUpdate\$meshlink",
                PeerId::class.java,
                WireFrame.RouteUpdate::class.java,
            )
    private val onRouteRetraction =
        RouteCoordinator::class
            .java
            .getMethod(
                "onRouteRetraction\$meshlink",
                PeerId::class.java,
                WireFrame.RouteRetraction::class.java,
            )
    private val onRouteDigest =
        RouteCoordinator::class
            .java
            .getMethod(
                "onRouteDigest\$meshlink",
                PeerId::class.java,
                WireFrame.RouteDigest::class.java,
            )
    private val nextHopFor =
        RouteCoordinator::class.java.getMethod("nextHopFor\$meshlink", PeerId::class.java)
    private val routeFor =
        RouteCoordinator::class.java.getMethod("routeFor\$meshlink", PeerId::class.java)
    private val targetPeerId =
        RoutingAdvertisement::class.java.getMethod("getTargetPeerId\$meshlink")
    private val frame = RoutingAdvertisement::class.java.getMethod("getFrame\$meshlink")

    internal fun onPeerConnected(
        coordinator: RouteCoordinator,
        peerId: PeerId,
        trustRecord: TrustRecord,
    ): List<BenchmarkAdvertisement> {
        return toBenchmarkAdvertisements(onPeerConnected.invoke(coordinator, peerId, trustRecord))
    }

    internal fun onPeerDisconnected(
        coordinator: RouteCoordinator,
        peerId: PeerId,
    ): List<BenchmarkAdvertisement> {
        return toBenchmarkAdvertisements(onPeerDisconnected.invoke(coordinator, peerId))
    }

    internal fun onRouteUpdate(
        coordinator: RouteCoordinator,
        fromPeerId: PeerId,
        update: WireFrame.RouteUpdate,
    ): List<BenchmarkAdvertisement> {
        return toBenchmarkAdvertisements(onRouteUpdate.invoke(coordinator, fromPeerId, update))
    }

    internal fun onRouteRetraction(
        coordinator: RouteCoordinator,
        fromPeerId: PeerId,
        retraction: WireFrame.RouteRetraction,
    ): List<BenchmarkAdvertisement> {
        return toBenchmarkAdvertisements(
            onRouteRetraction.invoke(coordinator, fromPeerId, retraction)
        )
    }

    internal fun onRouteDigest(
        coordinator: RouteCoordinator,
        fromPeerId: PeerId,
        digest: WireFrame.RouteDigest,
    ): Unit {
        onRouteDigest.invoke(coordinator, fromPeerId, digest)
    }

    internal fun nextHopFor(coordinator: RouteCoordinator, destinationPeerId: PeerId): PeerId? {
        return nextHopFor.invoke(coordinator, destinationPeerId) as? PeerId
    }

    internal fun hasRoute(coordinator: RouteCoordinator, destinationPeerId: PeerId): Boolean {
        return routeFor.invoke(coordinator, destinationPeerId) != null
    }

    @Suppress("UNCHECKED_CAST")
    private fun toBenchmarkAdvertisements(result: Any?): List<BenchmarkAdvertisement> {
        return (result as List<RoutingAdvertisement>).map { advertisement ->
            BenchmarkAdvertisement(
                targetPeerId = targetPeerId.invoke(advertisement) as PeerId,
                frame = frame.invoke(advertisement) as WireFrame,
            )
        }
    }
}
