package io.meshlink.routing

enum class PresenceState { CONNECTED, DISCONNECTED }

class PresenceTracker {

    private val peers = mutableMapOf<String, PresenceState>()
    private val missCount = mutableMapOf<String, Int>()

    fun peerSeen(peerId: String) {
        peers[peerId] = PresenceState.CONNECTED
        missCount[peerId] = 0
    }

    fun state(peerId: String): PresenceState? = peers[peerId]

    fun connectedPeerIds(): Set<String> =
        peers.filterValues { it == PresenceState.CONNECTED }.keys

    fun allPeerIds(): Set<String> = peers.keys.toSet()

    fun clear() {
        peers.clear()
        missCount.clear()
    }

    /** Run sweep. Returns set of evicted peer IDs (2 consecutive misses). */
    fun sweep(seenPeers: Set<String>): Set<String> {
        val evicted = mutableSetOf<String>()
        for (peerId in peers.keys.toList()) {
            if (peerId in seenPeers) {
                peers[peerId] = PresenceState.CONNECTED
                missCount[peerId] = 0
            } else {
                val misses = (missCount[peerId] ?: 0) + 1
                missCount[peerId] = misses
                if (misses >= 2) {
                    peers.remove(peerId)
                    missCount.remove(peerId)
                    evicted.add(peerId)
                } else {
                    peers[peerId] = PresenceState.DISCONNECTED
                }
            }
        }
        return evicted
    }
}
