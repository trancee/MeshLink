package io.meshlink.routing

import io.meshlink.util.ByteArrayKey

enum class PresenceState { CONNECTED, DISCONNECTED, GONE }

class PresenceTracker(
    private val maxPeers: Int = 1000,
) {

    private val peers = mutableMapOf<ByteArrayKey, PresenceState>()
    private val missCount = mutableMapOf<ByteArrayKey, Int>()
    private val firstSeen = mutableMapOf<ByteArrayKey, Long>()
    private var monotonicCounter = 0L

    /**
     * Mark a peer as seen. If the peer table is at capacity, the oldest
     * non-connected peer is evicted (LRU). Returns true if the peer was
     * accepted, false if the table is full and all peers are connected.
     */
    fun peerSeen(peerId: ByteArrayKey): Boolean {
        if (peers.containsKey(peerId)) {
            peers[peerId] = PresenceState.CONNECTED
            missCount[peerId] = 0
            return true
        }
        // New peer — check capacity
        if (peers.size >= maxPeers) {
            // Evict oldest non-connected peer (LRU by firstSeen order)
            val evictCandidate = peers.entries
                .filter { it.value != PresenceState.CONNECTED }
                .minByOrNull { firstSeen[it.key] ?: 0L }
                ?.key
            if (evictCandidate != null) {
                peers.remove(evictCandidate)
                missCount.remove(evictCandidate)
                firstSeen.remove(evictCandidate)
            } else {
                return false // all peers are connected, cannot evict
            }
        }
        peers[peerId] = PresenceState.CONNECTED
        missCount[peerId] = 0
        firstSeen[peerId] = monotonicCounter++
        return true
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
        firstSeen.clear()
        monotonicCounter = 0L
    }

    /** Run sweep. Returns set of presence-evicted peer IDs (2 consecutive misses → GONE). */
    fun sweep(seenPeers: Set<ByteArrayKey>): Set<ByteArrayKey> {
        val presenceEvicted = mutableSetOf<ByteArrayKey>()
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
                        firstSeen.remove(peerId)
                        presenceEvicted.add(peerId)
                    }
                    else -> {
                        peers.remove(peerId)
                        missCount.remove(peerId)
                        firstSeen.remove(peerId)
                        presenceEvicted.add(peerId)
                    }
                }
            }
        }
        return presenceEvicted
    }
}
