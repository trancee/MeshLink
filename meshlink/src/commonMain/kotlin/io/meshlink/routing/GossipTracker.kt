package io.meshlink.routing

import io.meshlink.util.ByteArrayKey

/**
 * Tracks per-neighbor gossip state for differential exchange.
 * Instead of flooding all routes to every peer on each gossip round,
 * only send routes that have changed since the last exchange with that peer.
 */
class GossipTracker {

    data class SentRouteState(
        val sequenceNumber: UInt,
        val cost: Double,
    )

    // peerId → (destination → SentRouteState)
    private val peerState: MutableMap<ByteArrayKey, MutableMap<ByteArrayKey, SentRouteState>> = mutableMapOf()

    /**
     * Compute which routes need to be sent to [peerId].
     * Returns only routes that are new or changed since last sent.
     * If peer has no prior state, returns ALL routes (full exchange).
     */
    fun computeDiff(peerId: ByteArrayKey, currentRoutes: List<RoutingTable.Route>): List<RoutingTable.Route> {
        val sent = peerState[peerId] ?: return currentRoutes

        return currentRoutes.filter { route ->
            val previous = sent[route.destination] ?: return@filter true
            previous.sequenceNumber != route.sequenceNumber || previous.cost != route.cost
        }
    }

    /**
     * Record that routes were successfully sent to [peerId].
     * Call this AFTER sending to update tracked state.
     */
    fun recordSent(peerId: ByteArrayKey, sentRoutes: List<RoutingTable.Route>) {
        val map = peerState.getOrPut(peerId) { mutableMapOf() }
        for (route in sentRoutes) {
            map[route.destination] = SentRouteState(route.sequenceNumber, route.cost)
        }
    }

    /**
     * Compute routes that the peer knows about but are no longer in our table.
     * These should be sent as withdrawals (cost = Double.MAX_VALUE).
     */
    fun computeWithdrawals(peerId: ByteArrayKey, currentDestinations: Set<ByteArrayKey>): Set<ByteArrayKey> {
        val sent = peerState[peerId] ?: return emptySet()

        return sent.keys.filter { destination ->
            destination !in currentDestinations &&
                sent[destination]?.cost != Double.MAX_VALUE
        }.toSet()
    }

    /**
     * Clear state for a disconnected peer.
     */
    fun removePeer(peerId: ByteArrayKey) {
        peerState.remove(peerId)
    }

    /**
     * Force full exchange on next gossip round for this peer.
     */
    fun resetPeer(peerId: ByteArrayKey) {
        peerState.remove(peerId)
    }

    /**
     * Clear all tracked state.
     */
    fun clear() {
        peerState.clear()
    }

    /**
     * Number of peers being tracked.
     */
    fun trackedPeerCount(): Int = peerState.size
}
