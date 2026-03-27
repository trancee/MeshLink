package io.meshlink.routing

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

    // peerHex → (destination → SentRouteState)
    private val peerState: MutableMap<String, MutableMap<String, SentRouteState>> = mutableMapOf()

    /**
     * Compute which routes need to be sent to [peerHex].
     * Returns only routes that are new or changed since last sent.
     * If peer has no prior state, returns ALL routes (full exchange).
     */
    fun computeDiff(peerHex: String, currentRoutes: List<RoutingTable.Route>): List<RoutingTable.Route> {
        val sent = peerState[peerHex] ?: return currentRoutes

        return currentRoutes.filter { route ->
            val previous = sent[route.destination] ?: return@filter true
            previous.sequenceNumber != route.sequenceNumber || previous.cost != route.cost
        }
    }

    /**
     * Record that routes were successfully sent to [peerHex].
     * Call this AFTER sending to update tracked state.
     */
    fun recordSent(peerHex: String, sentRoutes: List<RoutingTable.Route>) {
        val map = peerState.getOrPut(peerHex) { mutableMapOf() }
        for (route in sentRoutes) {
            map[route.destination] = SentRouteState(route.sequenceNumber, route.cost)
        }
    }

    /**
     * Compute routes that the peer knows about but are no longer in our table.
     * These should be sent as withdrawals (cost = Double.MAX_VALUE).
     */
    fun computeWithdrawals(peerHex: String, currentDestinations: Set<String>): Set<String> {
        val sent = peerState[peerHex] ?: return emptySet()

        return sent.keys.filter { destination ->
            destination !in currentDestinations &&
                sent[destination]?.cost != Double.MAX_VALUE
        }.toSet()
    }

    /**
     * Clear state for a disconnected peer.
     */
    fun removePeer(peerHex: String) {
        peerState.remove(peerHex)
    }

    /**
     * Force full exchange on next gossip round for this peer.
     */
    fun resetPeer(peerHex: String) {
        peerState.remove(peerHex)
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
