package ch.trancee.meshlink.routing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

internal sealed class PeerEvent {
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

internal class PresenceTracker {
    private val _peerEvents = MutableSharedFlow<PeerEvent>(replay = 0, extraBufferCapacity = 64)
    val peerEvents: Flow<PeerEvent> = _peerEvents

    private val _connectedPeers = HashSet<List<Byte>>()

    var presenceTimeoutMillis: Long = 30_000L
        private set

    fun onPeerConnected(peerId: ByteArray) {
        _connectedPeers.add(peerId.toList())
        _peerEvents.tryEmit(PeerEvent.Connected(peerId.copyOf()))
    }

    fun onPeerDisconnected(peerId: ByteArray) {
        _connectedPeers.remove(peerId.toList())
        _peerEvents.tryEmit(PeerEvent.Disconnected(peerId.copyOf()))
    }

    fun connectedPeers(): Set<ByteArray> = _connectedPeers.map { it.toByteArray() }.toSet()

    fun updatePresenceTimeout(newTimeoutMillis: Long) {
        presenceTimeoutMillis = newTimeoutMillis
    }
}
