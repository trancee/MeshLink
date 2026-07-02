package ch.trancee.meshlink.presence

import ch.trancee.meshlink.api.PeerId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class PeerPresenceTracker {
    private val presenceMutex = Mutex()
    private val connectedPeers: MutableSet<String> = linkedSetOf()

    internal suspend fun onPeerConnected(peerId: PeerId): PresenceTransition {
        return presenceMutex.withLock {
            val firstObservation = connectedPeers.add(peerId.value)
            if (firstObservation) {
                PresenceTransition.FOUND
            } else {
                PresenceTransition.STATE_CHANGED
            }
        }
    }

    internal suspend fun onPeerDisconnected(peerId: PeerId): Boolean {
        return presenceMutex.withLock { connectedPeers.remove(peerId.value) }
    }

    internal suspend fun clear(): List<PeerId> {
        return presenceMutex.withLock {
            val clearedPeers = connectedPeers.map(::PeerId)
            connectedPeers.clear()
            clearedPeers
        }
    }
}

internal enum class PresenceTransition {
    FOUND,
    STATE_CHANGED,
}
