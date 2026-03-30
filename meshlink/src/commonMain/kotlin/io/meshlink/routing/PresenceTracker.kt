package io.meshlink.routing

import io.meshlink.util.ByteArrayKey

enum class PresenceState { CONNECTED, DISCONNECTED, GONE }

class PresenceTracker {

    private val peers = mutableMapOf<ByteArrayKey, PresenceState>()
    private val missCount = mutableMapOf<ByteArrayKey, Int>()

    fun peerSeen(peerId: ByteArrayKey) {
        peers[peerId] = PresenceState.CONNECTED
        missCount[peerId] = 0
    }

    fun state(peerId: ByteArrayKey): PresenceState? = peers[peerId]

    fun markDisconnected(peerId: ByteArrayKey) {
        if (peers.containsKey(peerId)) {
            peers[peerId] = PresenceState.DISCONNECTED
        }
    }

    fun connectedPeerIds(): Set<ByteArrayKey> =
        peers.filterValues { it == PresenceState.CONNECTED }.keys

    fun allPeerIds(): Set<ByteArrayKey> = peers.keys.toSet()

    fun clear() {
        peers.clear()
        missCount.clear()
    }

    /** Run sweep. Returns set of evicted peer IDs (2 consecutive misses → GONE). */
    fun sweep(seenPeers: Set<ByteArrayKey>): Set<ByteArrayKey> {
        val evicted = mutableSetOf<ByteArrayKey>()
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
