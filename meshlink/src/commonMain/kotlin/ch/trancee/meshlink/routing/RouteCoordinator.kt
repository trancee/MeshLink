package ch.trancee.meshlink.routing

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.trust.TrustRecord
import ch.trancee.meshlink.wire.WireFrame
import ch.trancee.meshlink.wire.WriteBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class RouteCoordinator internal constructor(private val localPeerId: PeerId) {
    private val connectedPeers: MutableSet<String> = linkedSetOf()
    private val directRouteSeqNos: MutableMap<String, Long> = linkedMapOf()
    private val selectedRoutes: MutableMap<String, RouteEntry> = linkedMapOf()
    private val feasibilityDistances: MutableMap<String, FeasibilityDistance> = linkedMapOf()
    private val peerDigests: MutableMap<String, ByteArray> = linkedMapOf()
    private val mutableTopologyVersion: MutableStateFlow<Long> = MutableStateFlow(0L)
    private var cachedRouteDigest: ByteArray? = null

    internal val topologyVersion: StateFlow<Long> = mutableTopologyVersion.asStateFlow()

    internal fun onPeerConnected(peerId: PeerId, trustRecord: TrustRecord): RoutingMutation {
        connectedPeers += peerId.value

        val seqNo = (directRouteSeqNos[peerId.value] ?: 0L) + 1L
        directRouteSeqNos[peerId.value] = seqNo
        val directRoute =
            RouteEntry(
                destinationPeerId = peerId,
                nextHopPeerId = peerId,
                metric = DIRECT_ROUTE_METRIC,
                seqNo = seqNo,
                feasibilityMetric = DIRECT_ROUTE_METRIC,
                isDirect = true,
                ed25519PublicKey = trustRecord.ed25519PublicKey,
                x25519PublicKey = trustRecord.x25519PublicKey,
            )
        val previousRoute = selectedRoutes[peerId.value]
        selectedRoutes[peerId.value] = directRoute
        updateFeasibilityDistance(directRoute)
        invalidateRouteDigest()

        val directRouteFrame = directRoute.asRouteUpdateFrame()
        val digestFrame = routeDigestFrame()
        val advertisements = mutableListOf<RoutingAdvertisement>()
        connectedPeers.forEach { connectedPeerId ->
            if (connectedPeerId == peerId.value) {
                return@forEach
            }
            val targetPeerId = PeerId(connectedPeerId)
            advertisements +=
                RoutingAdvertisement(targetPeerId = targetPeerId, frame = directRouteFrame)
            advertisements += RoutingAdvertisement(targetPeerId = targetPeerId, frame = digestFrame)
        }

        selectedRoutes.values.forEach { route ->
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
        peerDigests.remove(peerId.value)

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

        val advertisements = mutableListOf<RoutingAdvertisement>()
        if (removedRoutes.isNotEmpty()) {
            invalidateRouteDigest()
            advanceTopologyVersion()
            val digestFrame = routeDigestFrame()
            removedRoutes.forEach { route ->
                val retractionFrame = route.asRouteRetractionFrame()
                connectedPeers.forEach { connectedPeerId ->
                    advertisements +=
                        RoutingAdvertisement(
                            targetPeerId = PeerId(connectedPeerId),
                            frame = retractionFrame,
                        )
                }
            }
            connectedPeers.forEach { connectedPeerId ->
                advertisements +=
                    RoutingAdvertisement(
                        targetPeerId = PeerId(connectedPeerId),
                        frame = digestFrame,
                    )
            }
        }
        return RoutingMutation(
            advertisements = advertisements,
            routeChanges = removedRoutes.map(RouteSelectionChange::Removed),
        )
    }

    internal fun onRouteUpdate(fromPeerId: PeerId, update: WireFrame.RouteUpdate): RoutingMutation {
        if (update.destinationPeerId.value == localPeerId.value) {
            return RoutingMutation.EMPTY
        }

        val candidate =
            RouteEntry(
                destinationPeerId = update.destinationPeerId,
                nextHopPeerId = fromPeerId,
                metric = update.metric + 1,
                seqNo = update.seqNo,
                feasibilityMetric = update.feasibilityMetric,
                isDirect = false,
                ed25519PublicKey = update.destinationEd25519PublicKey,
                x25519PublicKey = update.destinationX25519PublicKey,
            )
        val current = selectedRoutes[update.destinationPeerId.value]
        if (!isFeasible(candidate) && current?.nextHopPeerId?.value != fromPeerId.value) {
            return RoutingMutation.EMPTY
        }
        if (!shouldSelect(candidate, current)) {
            return RoutingMutation.EMPTY
        }

        selectedRoutes[update.destinationPeerId.value] = candidate
        updateFeasibilityDistance(candidate)
        invalidateRouteDigest()
        advanceTopologyVersion()
        val routeChange =
            if (current == null) {
                RouteSelectionChange.Available(candidate)
            } else {
                RouteSelectionChange.Updated(route = candidate, previousRoute = current)
            }
        val candidateFrame = candidate.asRouteUpdateFrame()
        val digestFrame = routeDigestFrame()
        val advertisements = mutableListOf<RoutingAdvertisement>()
        connectedPeers.forEach { connectedPeerId ->
            if (
                connectedPeerId == fromPeerId.value ||
                    connectedPeerId == candidate.nextHopPeerId.value
            ) {
                return@forEach
            }
            val targetPeerId = PeerId(connectedPeerId)
            advertisements +=
                RoutingAdvertisement(targetPeerId = targetPeerId, frame = candidateFrame)
            advertisements += RoutingAdvertisement(targetPeerId = targetPeerId, frame = digestFrame)
        }
        return RoutingMutation(advertisements = advertisements, routeChanges = listOf(routeChange))
    }

    internal fun onRouteRetraction(
        fromPeerId: PeerId,
        retraction: WireFrame.RouteRetraction,
    ): RoutingMutation {
        val current =
            selectedRoutes[retraction.destinationPeerId.value] ?: return RoutingMutation.EMPTY
        if (current.nextHopPeerId.value != fromPeerId.value) {
            return RoutingMutation.EMPTY
        }

        selectedRoutes.remove(retraction.destinationPeerId.value)
        feasibilityDistances.remove(retraction.destinationPeerId.value)
        invalidateRouteDigest()
        advanceTopologyVersion()
        val retractionFrame = current.asRouteRetractionFrame()
        val digestFrame = routeDigestFrame()
        val advertisements = mutableListOf<RoutingAdvertisement>()
        connectedPeers.forEach { connectedPeerId ->
            if (connectedPeerId == fromPeerId.value) {
                return@forEach
            }
            val targetPeerId = PeerId(connectedPeerId)
            advertisements +=
                RoutingAdvertisement(targetPeerId = targetPeerId, frame = retractionFrame)
            advertisements += RoutingAdvertisement(targetPeerId = targetPeerId, frame = digestFrame)
        }
        return RoutingMutation(
            advertisements = advertisements,
            routeChanges = listOf(RouteSelectionChange.Removed(current)),
        )
    }

    internal fun onRouteDigest(fromPeerId: PeerId, frame: WireFrame.RouteDigest): Unit {
        peerDigests[fromPeerId.value] = frame.digest.copyOf()
    }

    internal fun nextHopFor(destinationPeerId: PeerId): PeerId? {
        val route = selectedRoutes[destinationPeerId.value] ?: return null
        return route.nextHopPeerId
    }

    internal fun routeFor(destinationPeerId: PeerId): RouteEntry? {
        return selectedRoutes[destinationPeerId.value]
    }

    private fun shouldSelect(candidate: RouteEntry, current: RouteEntry?): Boolean {
        if (current == null) {
            return true
        }
        if (
            current.isDirect &&
                candidate.metric >= current.metric &&
                candidate.seqNo <= current.seqNo
        ) {
            return false
        }
        if (current.nextHopPeerId.value == candidate.nextHopPeerId.value) {
            return true
        }
        if (candidate.seqNo > current.seqNo) {
            return true
        }
        return candidate.seqNo == current.seqNo && candidate.metric < current.metric
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

    private fun routeDigestFrame(): WireFrame.RouteDigest {
        return WireFrame.RouteDigest(peerId = localPeerId, digest = routeDigest())
    }

    private fun routeDigest(): ByteArray {
        cachedRouteDigest?.let {
            return it
        }

        val buffer = WriteBuffer()
        selectedRoutes.values
            .sortedWith(
                compareBy<RouteEntry> { route -> route.destinationPeerId.value }
                    .thenBy { route -> route.nextHopPeerId.value }
            )
            .forEach { route ->
                buffer.writeIntLittleEndian(route.destinationPeerIdBytes.size)
                buffer.writeBytes(route.destinationPeerIdBytes)
                buffer.writeIntLittleEndian(route.nextHopPeerIdBytes.size)
                buffer.writeBytes(route.nextHopPeerIdBytes)
                buffer.writeIntLittleEndian(route.metric)
                buffer.writeIntLittleEndian(route.seqNo.toInt())
                buffer.writeIntLittleEndian(route.ed25519PublicKey.size)
                buffer.writeBytes(route.ed25519PublicKey)
                buffer.writeIntLittleEndian(route.x25519PublicKey.size)
                buffer.writeBytes(route.x25519PublicKey)
            }
        return fnv1a32(buffer.toByteArray()).also { digest -> cachedRouteDigest = digest }
    }

    private fun invalidateRouteDigest(): Unit {
        cachedRouteDigest = null
    }

    private fun fnv1a32(bytes: ByteArray): ByteArray {
        var hash = 0x811C9DC5u
        bytes.forEach { byte ->
            hash = hash xor byte.toUByte().toUInt()
            hash *= 0x01000193u
        }
        return byteArrayOf(
            (hash and 0xFFu).toByte(),
            ((hash shr 8) and 0xFFu).toByte(),
            ((hash shr 16) and 0xFFu).toByte(),
            ((hash shr 24) and 0xFFu).toByte(),
        )
    }

    private fun advanceTopologyVersion(): Unit {
        mutableTopologyVersion.value += 1L
    }

    internal companion object {
        private const val DIRECT_ROUTE_METRIC: Int = 1
    }
}

internal class RoutingMutation
internal constructor(
    internal val advertisements: List<RoutingAdvertisement>,
    internal val routeChanges: List<RouteSelectionChange>,
) {
    internal companion object {
        internal val EMPTY: RoutingMutation = RoutingMutation(emptyList(), emptyList())
    }
}

internal sealed class RouteSelectionChange {
    internal data class Available(internal val route: RouteEntry) : RouteSelectionChange()

    internal data class Updated(
        internal val route: RouteEntry,
        internal val previousRoute: RouteEntry,
    ) : RouteSelectionChange()

    internal data class Removed(internal val previousRoute: RouteEntry) : RouteSelectionChange()
}

internal class RouteEntry
internal constructor(
    internal val destinationPeerId: PeerId,
    internal val nextHopPeerId: PeerId,
    internal val metric: Int,
    internal val seqNo: Long,
    internal val feasibilityMetric: Int,
    internal val isDirect: Boolean,
    ed25519PublicKey: ByteArray,
    x25519PublicKey: ByteArray,
) {
    internal val destinationPeerIdBytes: ByteArray = destinationPeerId.value.encodeToByteArray()
    internal val nextHopPeerIdBytes: ByteArray = nextHopPeerId.value.encodeToByteArray()
    internal val ed25519PublicKey: ByteArray = ed25519PublicKey.copyOf()
    internal val x25519PublicKey: ByteArray = x25519PublicKey.copyOf()

    internal fun asRouteUpdateFrame(): WireFrame.RouteUpdate {
        return WireFrame.RouteUpdate(
            destinationPeerId = destinationPeerId,
            nextHopPeerId = nextHopPeerId,
            metric = metric,
            seqNo = seqNo,
            feasibilityMetric = feasibilityMetric,
            destinationEd25519PublicKey = ed25519PublicKey,
            destinationX25519PublicKey = x25519PublicKey,
        )
    }

    internal fun asRouteRetractionFrame(): WireFrame.RouteRetraction {
        return WireFrame.RouteRetraction(destinationPeerId = destinationPeerId, seqNo = seqNo)
    }
}

internal class RoutingAdvertisement
internal constructor(internal val targetPeerId: PeerId, internal val frame: WireFrame)

private class FeasibilityDistance
internal constructor(internal val seqNo: Long, internal val metric: Int)
