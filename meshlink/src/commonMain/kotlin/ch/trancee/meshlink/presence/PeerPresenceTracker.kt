package ch.trancee.meshlink.presence

import ch.trancee.meshlink.api.PeerId

internal class PeerPresenceTracker {
    private val connectedPeers: MutableSet<String> = linkedSetOf()

    internal fun onPeerConnected(peerId: PeerId): PresenceTransition {
        val firstObservation = connectedPeers.add(peerId.value)
        return if (firstObservation) {
            PresenceTransition.FOUND
        } else {
            PresenceTransition.STATE_CHANGED
        }
    }

    internal fun onPeerDisconnected(peerId: PeerId): Boolean {
        return connectedPeers.remove(peerId.value)
    }
}

internal enum class PresenceTransition {
    FOUND,
    STATE_CHANGED,
}
