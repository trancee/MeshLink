package ch.trancee.meshlink.routing

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.trust.TrustRecord
import ch.trancee.meshlink.wire.WireFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * All routing-table mutations and reads are serialized behind [routingMutex] because the engine's
 * default coroutine scope runs on [kotlinx.coroutines.Dispatchers.Default], a genuinely
 * multi-threaded pool, and routing frames for different peers can be handled concurrently. The
 * [topologyVersion] `StateFlow` remains readable without the mutex since `StateFlow.value` is
 * already a safe, atomic snapshot read.
 */
internal class RouteCoordinator internal constructor(private val localPeerId: PeerId) {
    private val routingMutex = Mutex()
    private val connectedPeers: MutableSet<String> = linkedSetOf()
    private val directRouteSeqNos: MutableMap<String, Long> = linkedMapOf()
    private val selectedRoutes: MutableMap<String, RouteEntry> = linkedMapOf()
    private val feasibilityDistances: MutableMap<String, FeasibilityDistance> = linkedMapOf()
    private val mutableTopologyVersion: MutableStateFlow<Long> = MutableStateFlow(0L)
    private val routeDigestTracker = RouteDigestTracker()

    internal val topologyVersion: StateFlow<Long> = mutableTopologyVersion.asStateFlow()

    internal suspend fun onPeerConnected(
        peerId: PeerId,
        trustRecord: TrustRecord,
    ): RoutingMutation = routingMutex.withLock {
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
        RoutingMutation(advertisements = advertisements, routeChanges = listOf(routeChange))
    }

    internal suspend fun onPeerDisconnected(peerId: PeerId): RoutingMutation =
        routingMutex.withLock {
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
            RoutingMutation(
                advertisements = advertisements,
                routeChanges = removedRoutes.map(RouteSelectionChange::Removed),
            )
        }

    internal suspend fun clearConnectedPeers(): RoutingMutation = routingMutex.withLock {
        if (connectedPeers.isEmpty() && selectedRoutes.isEmpty()) {
            return@withLock RoutingMutation.EMPTY
        }

        connectedPeers.clear()
        val removedRoutes = selectedRoutes.values.toList()
        selectedRoutes.clear()
        feasibilityDistances.clear()
        routeDigestTracker.clear()
        if (removedRoutes.isNotEmpty()) {
            advanceTopologyVersion()
        }
        RoutingMutation(
            advertisements = emptyList(),
            routeChanges = removedRoutes.map(RouteSelectionChange::Removed),
        )
    }

    internal suspend fun onRouteUpdate(
        fromPeerId: PeerId,
        update: WireFrame.RouteUpdate,
    ): RoutingMutation = routingMutex.withLock {
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

        if (shouldIgnoreUpdate) {
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

    internal suspend fun onRouteRetraction(
        fromPeerId: PeerId,
        retraction: WireFrame.RouteRetraction,
    ): RoutingMutation = routingMutex.withLock {
        val current = selectedRoutes[retraction.destinationPeerId.value]
        val shouldIgnoreRetraction =
            current == null || current.nextHopPeerId.value != fromPeerId.value

        if (shouldIgnoreRetraction) {
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

    internal suspend fun nextHopFor(destinationPeerId: PeerId): PeerId? = routingMutex.withLock {
        selectedRoutes[destinationPeerId.value]?.nextHopPeerId
    }

    internal suspend fun routeFor(destinationPeerId: PeerId): RouteEntry? = routingMutex.withLock {
        selectedRoutes[destinationPeerId.value]
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
