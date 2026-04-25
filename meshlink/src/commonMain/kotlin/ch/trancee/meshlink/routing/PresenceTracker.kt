package ch.trancee.meshlink.routing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

sealed class PeerEvent {
    data class Connected(val peerId: ByteArray) : PeerEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Connected) return false
            return peerId.contentEquals(other.peerId)
        }

        override fun hashCode(): Int = peerId.contentHashCode()
    }

    data class Disconnected(val peerId: ByteArray) : PeerEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Disconnected) return false
            return peerId.contentEquals(other.peerId)
        }

        override fun hashCode(): Int = peerId.contentHashCode()
    }
}

class PresenceTracker {
    private val _peerEvents = MutableSharedFlow<PeerEvent>(replay = 0, extraBufferCapacity = 64)
    val peerEvents: Flow<PeerEvent> = _peerEvents

    fun onPeerConnected(peerId: ByteArray) {
        _peerEvents.tryEmit(PeerEvent.Connected(peerId.copyOf()))
    }

    fun onPeerDisconnected(peerId: ByteArray) {
        _peerEvents.tryEmit(PeerEvent.Disconnected(peerId.copyOf()))
    }
}
