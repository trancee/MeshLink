package io.meshlink.routing

enum class PresenceState { CONNECTED, DISCONNECTED, GONE }

class PresenceTracker {

    private val peers = mutableMapOf<String, PresenceState>()
    private val missCount = mutableMapOf<String, Int>()

    fun peerSeen(peerId: String) {
        peers[peerId] = PresenceState.CONNECTED
        missCount[peerId] = 0
    }

    fun state(peerId: String): PresenceState? = peers[peerId]

    fun markDisconnected(peerId: String) {
        if (peers.containsKey(peerId)) {
            peers[peerId] = PresenceState.DISCONNECTED
        }
    }

    fun connectedPeerIds(): Set<String> =
        peers.filterValues { it == PresenceState.CONNECTED }.keys

    fun allPeerIds(): Set<String> = peers.keys.toSet()

    fun clear() {
        peers.clear()
        missCount.clear()
    }

    /** Run sweep. Returns set of evicted peer IDs (2 consecutive misses → GONE). */
    fun sweep(seenPeers: Set<String>): Set<String> {
        val evicted = mutableSetOf<String>()
        for (peerId in peers.keys.toList()) {
            if (peerId in seenPeers) {
                peers[peerId] = PresenceState.CONNECTED
                missCount[peerId] = 0
            } else {
                when (peers[peerId]) {
                    PresenceState.CONNECTED -> {
                        peers[peerId] = PresenceState.DISCONNECTED
                        missCount[peerId] = 1
                    }
                    PresenceState.DISCONNECTED -> {
                        peers[peerId] = PresenceState.GONE
                        peers.remove(peerId)
                        missCount.remove(peerId)
                        evicted.add(peerId)
                    }
                    else -> {
                        peers.remove(peerId)
                        missCount.remove(peerId)
                        evicted.add(peerId)
                    }
                }
            }
        }
        return evicted
    }
}
