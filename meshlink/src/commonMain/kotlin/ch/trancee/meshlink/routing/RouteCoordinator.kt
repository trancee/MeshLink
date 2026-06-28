package ch.trancee.meshlink.routing

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.trust.TrustRecord
import ch.trancee.meshlink.wire.WireFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class RouteCoordinator internal constructor(private val localPeerId: PeerId) {
    private val connectedPeers: MutableSet<String> = linkedSetOf()
    private val directRouteSeqNos: MutableMap<String, Long> = linkedMapOf()
    private val selectedRoutes: MutableMap<String, RouteEntry> = linkedMapOf()
    private val feasibilityDistances: MutableMap<String, FeasibilityDistance> = linkedMapOf()
    private val mutableTopologyVersion: MutableStateFlow<Long> = MutableStateFlow(0L)
    private val routeDigestTracker = RouteDigestTracker()

    internal val topologyVersion: StateFlow<Long> = mutableTopologyVersion.asStateFlow()

    internal fun onPeerConnected(peerId: PeerId, trustRecord: TrustRecord): RoutingMutation {
        connectedPeers += peerId.value

        val seqNo = (directRouteSeqNos[peerId.value] ?: 0L) + 1L
        directRouteSeqNos[peerId.value] = seqNo
        val directRoute =
            RouteEntry(
                destinationPeerId = peerId,
                nextHopPeerId = peerId,
                metrics =
                    RouteMetrics(
                        metric = DIRECT_ROUTE_METRIC,
                        seqNo = seqNo,
                        feasibilityMetric = DIRECT_ROUTE_METRIC,
                        isDirect = true,
                    ),
                publicKeys =
                    RoutePublicKeys(
                        ed25519PublicKey = trustRecord.ed25519PublicKey,
                        x25519PublicKey = trustRecord.x25519PublicKey,
                    ),
            )
        val previousRoute = selectedRoutes[peerId.value]
        selectedRoutes[peerId.value] = directRoute
        updateFeasibilityDistance(directRoute)
        routeDigestTracker.upsert(directRoute)

        val advertisements =
            RouteAdvertisementPlanner.forPeerConnected(
                peerId = peerId,
                directRoute = directRoute,
                connectedPeerIds = connectedPeers,
                selectedRoutes = selectedRoutes.values,
                routeDigestTracker = routeDigestTracker,
                localPeerId = localPeerId,
            )

        advanceTopologyVersion()
        val routeChange =
            if (previousRoute == null) {
                RouteSelectionChange.Available(directRoute)
            } else {
                RouteSelectionChange.Updated(route = directRoute, previousRoute = previousRoute)
            }
        return RoutingMutation(advertisements = advertisements, routeChanges = listOf(routeChange))
    }

    internal fun onPeerDisconnected(peerId: PeerId): RoutingMutation {
        connectedPeers -= peerId.value

        val removedRoutes =
            selectedRoutes.values
                .filter { route ->
                    route.destinationPeerId.value == peerId.value ||
                        route.nextHopPeerId.value == peerId.value
                }
                .toList()
        removedRoutes.forEach { route ->
            selectedRoutes.remove(route.destinationPeerId.value)
            feasibilityDistances.remove(route.destinationPeerId.value)
        }

        val advertisements =
            if (removedRoutes.isNotEmpty()) {
                removedRoutes.forEach { route ->
                    routeDigestTracker.remove(route.destinationPeerId.value)
                }
                advanceTopologyVersion()
                RouteAdvertisementPlanner.forPeerDisconnected(
                    removedRoutes = removedRoutes,
                    connectedPeerIds = connectedPeers,
                    routeDigestTracker = routeDigestTracker,
                    localPeerId = localPeerId,
                )
            } else {
                emptyList()
            }
        return RoutingMutation(
            advertisements = advertisements,
            routeChanges = removedRoutes.map(RouteSelectionChange::Removed),
        )
    }

    internal fun clearConnectedPeers(): RoutingMutation {
        if (connectedPeers.isEmpty() && selectedRoutes.isEmpty()) {
            return RoutingMutation.EMPTY
        }

        connectedPeers.clear()
        val removedRoutes = selectedRoutes.values.toList()
        selectedRoutes.clear()
        feasibilityDistances.clear()
        routeDigestTracker.clear()
        if (removedRoutes.isNotEmpty()) {
            advanceTopologyVersion()
        }
        return RoutingMutation(
            advertisements = emptyList(),
            routeChanges = removedRoutes.map(RouteSelectionChange::Removed),
        )
    }

    internal fun onRouteUpdate(fromPeerId: PeerId, update: WireFrame.RouteUpdate): RoutingMutation {
        val candidate =
            RouteEntry(
                destinationPeerId = update.destinationPeerId,
                nextHopPeerId = fromPeerId,
                metrics =
                    RouteMetrics(
                        metric = update.metric + 1,
                        seqNo = update.seqNo,
                        feasibilityMetric = update.feasibilityMetric,
                        isDirect = false,
                    ),
                publicKeys =
                    RoutePublicKeys(
                        ed25519PublicKey = update.destinationEd25519PublicKey,
                        x25519PublicKey = update.destinationX25519PublicKey,
                    ),
            )
        val current = selectedRoutes[update.destinationPeerId.value]
        val shouldIgnoreUpdate =
            update.destinationPeerId.value == localPeerId.value ||
                (!isFeasible(candidate) && current?.nextHopPeerId?.value != fromPeerId.value) ||
                !shouldSelect(candidate, current)

        return if (shouldIgnoreUpdate) {
            RoutingMutation.EMPTY
        } else {
            selectedRoutes[update.destinationPeerId.value] = candidate
            updateFeasibilityDistance(candidate)
            routeDigestTracker.upsert(candidate)
            advanceTopologyVersion()
            val routeChange =
                if (current == null) {
                    RouteSelectionChange.Available(candidate)
                } else {
                    RouteSelectionChange.Updated(route = candidate, previousRoute = current)
                }
            val advertisements =
                RouteAdvertisementPlanner.forRouteUpdate(
                    candidate = candidate,
                    fromPeerId = fromPeerId,
                    connectedPeerIds = connectedPeers,
                    routeDigestTracker = routeDigestTracker,
                    localPeerId = localPeerId,
                )
            RoutingMutation(advertisements = advertisements, routeChanges = listOf(routeChange))
        }
    }

    internal fun onRouteRetraction(
        fromPeerId: PeerId,
        retraction: WireFrame.RouteRetraction,
    ): RoutingMutation {
        val current = selectedRoutes[retraction.destinationPeerId.value]
        val shouldIgnoreRetraction =
            current == null || current.nextHopPeerId.value != fromPeerId.value

        return if (shouldIgnoreRetraction) {
            RoutingMutation.EMPTY
        } else {
            selectedRoutes.remove(retraction.destinationPeerId.value)
            feasibilityDistances.remove(retraction.destinationPeerId.value)
            routeDigestTracker.remove(retraction.destinationPeerId.value)
            advanceTopologyVersion()
            val advertisements =
                RouteAdvertisementPlanner.forRouteRetraction(
                    current = current,
                    fromPeerId = fromPeerId,
                    connectedPeerIds = connectedPeers,
                    routeDigestTracker = routeDigestTracker,
                    localPeerId = localPeerId,
                )
            RoutingMutation(
                advertisements = advertisements,
                routeChanges = listOf(RouteSelectionChange.Removed(current)),
            )
        }
    }

    @Suppress("UNUSED_PARAMETER")
    internal fun onRouteDigest(fromPeerId: PeerId, frame: WireFrame.RouteDigest): Unit {}

    internal fun nextHopFor(destinationPeerId: PeerId): PeerId? {
        val route = selectedRoutes[destinationPeerId.value] ?: return null
        return route.nextHopPeerId
    }

    internal fun routeFor(destinationPeerId: PeerId): RouteEntry? {
        return selectedRoutes[destinationPeerId.value]
    }

    private fun isFeasible(candidate: RouteEntry): Boolean {
        val feasibilityDistance =
            feasibilityDistances[candidate.destinationPeerId.value] ?: return true
        return candidate.seqNo > feasibilityDistance.seqNo ||
            (candidate.seqNo == feasibilityDistance.seqNo &&
                candidate.metric < feasibilityDistance.metric)
    }

    private fun updateFeasibilityDistance(route: RouteEntry): Unit {
        val current = feasibilityDistances[route.destinationPeerId.value]
        if (current == null || route.seqNo > current.seqNo || route.metric < current.metric) {
            feasibilityDistances[route.destinationPeerId.value] =
                FeasibilityDistance(route.seqNo, route.metric)
        }
    }

    private fun advanceTopologyVersion(): Unit {
        mutableTopologyVersion.value += 1L
    }

    internal companion object {
        private const val DIRECT_ROUTE_METRIC: Int = 1
    }
}
