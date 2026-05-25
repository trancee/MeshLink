package ch.trancee.meshlink.routing

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.wire.WireFrame

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

internal class RouteMetrics
internal constructor(
    internal val metric: Int,
    internal val seqNo: Long,
    internal val feasibilityMetric: Int,
    internal val isDirect: Boolean,
)

internal class RoutePublicKeys
internal constructor(ed25519PublicKey: ByteArray, x25519PublicKey: ByteArray) {
    internal val ed25519PublicKey: ByteArray = ed25519PublicKey.copyOf()
    internal val x25519PublicKey: ByteArray = x25519PublicKey.copyOf()
}

internal class RouteEntry
internal constructor(
    internal val destinationPeerId: PeerId,
    internal val nextHopPeerId: PeerId,
    metrics: RouteMetrics,
    publicKeys: RoutePublicKeys,
) {
    internal val metric: Int = metrics.metric
    internal val seqNo: Long = metrics.seqNo
    internal val feasibilityMetric: Int = metrics.feasibilityMetric
    internal val isDirect: Boolean = metrics.isDirect
    internal val destinationPeerIdBytes: ByteArray = destinationPeerId.value.encodeToByteArray()
    internal val nextHopPeerIdBytes: ByteArray = nextHopPeerId.value.encodeToByteArray()
    internal val ed25519PublicKey: ByteArray = publicKeys.ed25519PublicKey.copyOf()
    internal val x25519PublicKey: ByteArray = publicKeys.x25519PublicKey.copyOf()

    internal fun asRouteUpdateFrame(): WireFrame.RouteUpdate {
        return WireFrame.RouteUpdate(
            destinationPeerId = destinationPeerId,
            nextHopPeerId = nextHopPeerId,
            metrics =
                WireFrame.RouteUpdateMetrics(
                    metric = metric,
                    seqNo = seqNo,
                    feasibilityMetric = feasibilityMetric,
                ),
            publicKeys =
                WireFrame.RouteUpdatePublicKeys(
                    destinationEd25519PublicKey = ed25519PublicKey,
                    destinationX25519PublicKey = x25519PublicKey,
                ),
        )
    }

    internal fun asRouteRetractionFrame(): WireFrame.RouteRetraction {
        return WireFrame.RouteRetraction(destinationPeerId = destinationPeerId, seqNo = seqNo)
    }
}

internal class RoutingAdvertisement
internal constructor(internal val targetPeerId: PeerId, internal val frame: WireFrame)

internal class FeasibilityDistance
internal constructor(internal val seqNo: Long, internal val metric: Int)

internal fun shouldSelect(candidate: RouteEntry, current: RouteEntry?): Boolean {
    val directRouteBlocksCandidate =
        current?.isDirect == true &&
            candidate.metric >= current.metric &&
            candidate.seqNo <= current.seqNo
    val sameNextHop = current?.nextHopPeerId?.value == candidate.nextHopPeerId.value
    val newerSequence = candidate.seqNo > (current?.seqNo ?: Long.MIN_VALUE)
    val betterMetricAtSameSequence =
        current != null && candidate.seqNo == current.seqNo && candidate.metric < current.metric

    return current == null ||
        (!directRouteBlocksCandidate &&
            (sameNextHop || newerSequence || betterMetricAtSameSequence))
}
