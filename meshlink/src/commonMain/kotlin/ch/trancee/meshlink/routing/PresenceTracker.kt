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

    data class Gone(val peerId: ByteArray) : PeerEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Gone) return false
            return peerId.contentEquals(other.peerId)
        }

        override fun hashCode(): Int = peerId.contentHashCode()
    }
}

/**
 * Internal peer state used by [PresenceTracker]'s three-state FSM.
 *
 * Peers transition: Connected → Disconnected → Gone (evicted).
 */
internal enum class InternalPeerState {
    CONNECTED,
    DISCONNECTED,
}

/**
 * Per-peer presence record tracking FSM state, last-seen timestamp, and sweep count.
 *
 * - [state] is the current FSM state (Connected or Disconnected).
 * - [lastSeenMillis] is updated on activity or connect; used by MeshStateManager for display.
 * - [sweepCount] counts how many sweep ticks a peer has been Disconnected. On sweep: sweepCount < 2
 *   → increment; sweepCount ≥ 2 → transition to Gone (evict from map).
 */
internal data class PeerPresence(
    val state: InternalPeerState,
    val lastSeenMillis: Long,
    val sweepCount: Int,
)

internal class PresenceTracker(private val clock: () -> Long = { 0L }) {
    private val _peerEvents = MutableSharedFlow<PeerEvent>(replay = 0, extraBufferCapacity = 64)
    val peerEvents: Flow<PeerEvent> = _peerEvents

    private val _peers = HashMap<List<Byte>, PeerPresence>()

    var presenceTimeoutMillis: Long = 30_000L
        private set

    /**
     * Called when a peer connects (or reconnects).
     *
     * Resets the peer to Connected state with sweepCount=0, regardless of prior state. A peer that
     * was Gone (evicted) and reconnects is treated as a fresh peer.
     */
    fun onPeerConnected(peerId: ByteArray) {
        _peers[peerId.toList()] =
            PeerPresence(
                state = InternalPeerState.CONNECTED,
                lastSeenMillis = clock(),
                sweepCount = 0,
            )
        _peerEvents.tryEmit(PeerEvent.Connected(peerId.copyOf()))
    }

    /**
     * Called when a peer disconnects.
     *
     * Transitions Connected → Disconnected. Idempotent: if the peer is already Disconnected, this
     * is a no-op (no event emitted). If the peer is unknown, adds it as Disconnected.
     */
    fun onPeerDisconnected(peerId: ByteArray) {
        val key = peerId.toList()
        val existing = _peers[key]
        if (existing != null && existing.state == InternalPeerState.DISCONNECTED) {
            // Already disconnected — idempotent no-op.
            return
        }
        _peers[key] =
            PeerPresence(
                state = InternalPeerState.DISCONNECTED,
                lastSeenMillis = existing?.lastSeenMillis ?: clock(),
                sweepCount = 0,
            )
        _peerEvents.tryEmit(PeerEvent.Disconnected(peerId.copyOf()))
    }

    /**
     * Update the last-seen timestamp for a peer (e.g. on keepalive receipt).
     *
     * No-op if the peer is not currently tracked.
     */
    fun onPeerActivity(peerId: ByteArray) {
        val key = peerId.toList()
        val existing = _peers[key] ?: return
        _peers[key] = existing.copy(lastSeenMillis = clock())
    }

    /**
     * Run one sweep tick. Transitions Disconnected peers through the sweep counter:
     * - sweepCount < 2 → increment sweepCount
     * - sweepCount ≥ 2 → evict (Gone), emit [PeerEvent.Gone], remove from map
     *
     * @return list of peer IDs that transitioned to Gone (evicted) in this sweep.
     */
    fun sweep(): List<ByteArray> {
        val gonePeers = mutableListOf<ByteArray>()
        val iterator = _peers.iterator()
        while (iterator.hasNext()) {
            val (key, presence) = iterator.next()
            if (presence.state != InternalPeerState.DISCONNECTED) continue

            if (presence.sweepCount >= 2) {
                // Evict — transition to Gone.
                val peerId = key.toByteArray()
                gonePeers.add(peerId)
                _peerEvents.tryEmit(PeerEvent.Gone(peerId.copyOf()))
                iterator.remove()
            } else {
                // Increment sweep count.
                _peers[key] = presence.copy(sweepCount = presence.sweepCount + 1)
            }
        }
        return gonePeers
    }

    /** Returns only peers in the Connected state — matches the prior `_connectedPeers` contract. */
    fun connectedPeers(): Set<ByteArray> =
        _peers.entries
            .filter { it.value.state == InternalPeerState.CONNECTED }
            .map { it.key.toByteArray() }
            .toSet()

    /** Query the internal state of a specific peer, or null if not tracked. */
    fun peerState(peerId: ByteArray): InternalPeerState? = _peers[peerId.toList()]?.state

    /**
     * Returns a snapshot of all tracked peers and their presence records. Used by MeshStateManager
     * for sweep coordination and health reporting.
     */
    fun allPeerStates(): Map<List<Byte>, PeerPresence> = HashMap(_peers)

    fun updatePresenceTimeout(newTimeoutMillis: Long) {
        presenceTimeoutMillis = newTimeoutMillis
    }
}
