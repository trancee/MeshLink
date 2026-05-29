package ch.trancee.meshlink.routing

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.wire.WireFrame

/**
 * Plans route advertisements after route selection has already decided what the new topology should
 * be.
 *
 * `RouteCoordinator` remains the selection seam. This planner owns only the fan-out rules for
 * direct-route announcements, retractions, and route-digest reuse across the currently connected
 * peers.
 */
internal object RouteAdvertisementPlanner {
    internal fun forPeerConnected(
        peerId: PeerId,
        directRoute: RouteEntry,
        connectedPeerIds: Set<String>,
        selectedRoutes: Collection<RouteEntry>,
        routeDigestTracker: RouteDigestTracker,
        localPeerId: PeerId,
    ): List<RoutingAdvertisement> {
        val advertisements = mutableListOf<RoutingAdvertisement>()
        val directRouteFrame = directRoute.asRouteUpdateFrame()
        val digestFrame = routeDigestTracker.routeDigestFrame(localPeerId)

        addFrameAndDigest(
            targetPeerIds =
                connectedPeerIds.asSequence().filterNot { it == peerId.value }.map(::PeerId),
            frame = directRouteFrame,
            digestFrame = digestFrame,
            advertisements = advertisements,
        )
        selectedRoutes.forEach { route ->
            if (
                route.destinationPeerId.value == peerId.value ||
                    route.nextHopPeerId.value == peerId.value
            ) {
                return@forEach
            }
            advertisements +=
                RoutingAdvertisement(targetPeerId = peerId, frame = route.asRouteUpdateFrame())
        }
        advertisements += RoutingAdvertisement(targetPeerId = peerId, frame = digestFrame)
        return advertisements
    }

    internal fun forPeerDisconnected(
        removedRoutes: List<RouteEntry>,
        connectedPeerIds: Set<String>,
        routeDigestTracker: RouteDigestTracker,
        localPeerId: PeerId,
    ): List<RoutingAdvertisement> {
        if (removedRoutes.isEmpty()) {
            return emptyList()
        }

        val advertisements = mutableListOf<RoutingAdvertisement>()
        val targetPeerIds = connectedPeerIds.map(::PeerId)
        removedRoutes.forEach { route ->
            addFrame(
                targetPeerIds = targetPeerIds,
                frame = route.asRouteRetractionFrame(),
                advertisements = advertisements,
            )
        }
        addDigest(
            targetPeerIds = targetPeerIds,
            digestFrame = routeDigestTracker.routeDigestFrame(localPeerId),
            advertisements = advertisements,
        )
        return advertisements
    }

    internal fun forRouteUpdate(
        candidate: RouteEntry,
        fromPeerId: PeerId,
        connectedPeerIds: Set<String>,
        routeDigestTracker: RouteDigestTracker,
        localPeerId: PeerId,
    ): List<RoutingAdvertisement> {
        val targetPeerIds =
            connectedPeerIds.asSequence().filterNot { connectedPeerId ->
                connectedPeerId == fromPeerId.value ||
                    connectedPeerId == candidate.nextHopPeerId.value
            }
        return buildList {
            addFrameAndDigest(
                targetPeerIds = targetPeerIds.map(::PeerId),
                frame = candidate.asRouteUpdateFrame(),
                digestFrame = routeDigestTracker.routeDigestFrame(localPeerId),
                advertisements = this,
            )
        }
    }

    internal fun forRouteRetraction(
        current: RouteEntry,
        fromPeerId: PeerId,
        connectedPeerIds: Set<String>,
        routeDigestTracker: RouteDigestTracker,
        localPeerId: PeerId,
    ): List<RoutingAdvertisement> {
        val targetPeerIds =
            connectedPeerIds.asSequence().filterNot { it == fromPeerId.value }.map(::PeerId)
        return buildList {
            addFrameAndDigest(
                targetPeerIds = targetPeerIds,
                frame = current.asRouteRetractionFrame(),
                digestFrame = routeDigestTracker.routeDigestFrame(localPeerId),
                advertisements = this,
            )
        }
    }

    private fun addFrameAndDigest(
        targetPeerIds: Sequence<PeerId>,
        frame: WireFrame,
        digestFrame: WireFrame.RouteDigest,
        advertisements: MutableList<RoutingAdvertisement>,
    ): Unit {
        targetPeerIds.forEach { targetPeerId ->
            advertisements += RoutingAdvertisement(targetPeerId = targetPeerId, frame = frame)
            advertisements += RoutingAdvertisement(targetPeerId = targetPeerId, frame = digestFrame)
        }
    }

    private fun addFrame(
        targetPeerIds: Iterable<PeerId>,
        frame: WireFrame,
        advertisements: MutableList<RoutingAdvertisement>,
    ): Unit {
        targetPeerIds.forEach { targetPeerId ->
            advertisements += RoutingAdvertisement(targetPeerId = targetPeerId, frame = frame)
        }
    }

    private fun addDigest(
        targetPeerIds: Iterable<PeerId>,
        digestFrame: WireFrame.RouteDigest,
        advertisements: MutableList<RoutingAdvertisement>,
    ): Unit {
        targetPeerIds.forEach { targetPeerId ->
            advertisements += RoutingAdvertisement(targetPeerId = targetPeerId, frame = digestFrame)
        }
    }
}
