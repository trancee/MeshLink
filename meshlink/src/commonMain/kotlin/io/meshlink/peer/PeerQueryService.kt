package io.meshlink.peer

import io.meshlink.crypto.SecurityEngine
import io.meshlink.model.PeerDetail
import io.meshlink.routing.RoutingEngine
import io.meshlink.util.ByteArrayKey
import io.meshlink.util.hexToBytes
import io.meshlink.util.toHex

/**
 * Provides peer detail queries, extracted from MeshLink for clarity.
 */
internal class PeerQueryService(
    private val routingEngine: RoutingEngine,
    private val securityEngine: SecurityEngine?,
) {

    fun peerPublicKey(peerIdHex: String): ByteArray? =
        securityEngine?.peerPublicKey(ByteArrayKey(hexToBytes(peerIdHex)))

    fun peerDetail(peerIdHex: String): PeerDetail? {
        val peerId = ByteArrayKey(hexToBytes(peerIdHex))
        val state = routingEngine.presenceState(peerId) ?: return null
        val route = routingEngine.bestRoute(peerId)
        val connectedIds = routingEngine.connectedPeerIds()
        val pubKey = securityEngine?.peerPublicKey(peerId)
        return PeerDetail(
            peerIdHex = peerIdHex,
            presenceState = state,
            isDirectNeighbor = peerId in connectedIds,
            routeNextHop = route?.nextHop?.toString(),
            routeCost = route?.cost,
            routeSequenceNumber = route?.sequenceNumber,
            publicKeyHex = pubKey?.toHex(),
            nextHopFailureRate = routingEngine.nextHopFailureRate(peerId),
            nextHopFailureCount = routingEngine.nextHopFailureCount(peerId),
        )
    }

    fun allPeerDetails(): List<PeerDetail> {
        val allIds = routingEngine.allPeerIds()
        val connectedIds = routingEngine.connectedPeerIds()
        val allRoutes = routingEngine.allBestRoutes().associateBy { it.destination }
        return allIds.mapNotNull { peerId ->
            val state = routingEngine.presenceState(peerId) ?: return@mapNotNull null
            val route = allRoutes[peerId]
            val pubKey = securityEngine?.peerPublicKey(peerId)
            PeerDetail(
                peerIdHex = peerId.toString(),
                presenceState = state,
                isDirectNeighbor = peerId in connectedIds,
                routeNextHop = route?.nextHop?.toString(),
                routeCost = route?.cost,
                routeSequenceNumber = route?.sequenceNumber,
                publicKeyHex = pubKey?.toHex(),
                nextHopFailureRate = routingEngine.nextHopFailureRate(peerId),
                nextHopFailureCount = routingEngine.nextHopFailureCount(peerId),
            )
        }
    }
}
