package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * The frames waiting for each linked peer's link, each a [Parcel]: at most [CAPACITY] relayed frames
 * per peer, while this node's own frames are bounded by its send queue. A lane full of relayed frames
 * drops a new one; a frame whose `message_id` is already waiting replaces it in place.
 *
 * Runs on the node's serial dispatcher.
 */
internal class Lanes {
    private val byPeer = mutableMapOf<NodeId, ArrayDeque<Parcel>>()
    private val changes = MutableStateFlow(0L)

    /** The peers with a lane: those linked now. */
    val peers: List<NodeId> get() = byPeer.keys.toList()

    /** Opens [peer]'s lane, keeping its frames if it is already open. */
    fun open(peer: NodeId) {
        byPeer.getOrPut(peer) { ArrayDeque() }
    }

    /** Closes [peer]'s lane and drops its frames. */
    fun close(peer: NodeId) {
        byPeer -= peer
    }

    /** Queues [parcel] for [peer]; returns whether its lane took it. */
    fun put(
        peer: NodeId,
        parcel: Parcel,
    ): Boolean {
        val lane = byPeer[peer] ?: return false
        val at = lane.indexOfFirst { it.messageId.contentEquals(parcel.messageId) }
        val took =
            when {
                at >= 0 -> true.also { lane[at] = parcel }
                !parcel.relayed || lane.count { it.relayed } < CAPACITY -> lane.add(parcel)
                else -> false
            }
        if (took) changes.value++
        return took
    }

    /** Puts [parcel], whose write failed, back at the front of [peer]'s lane if it is open. */
    fun putBack(
        peer: NodeId,
        parcel: Parcel,
    ) {
        byPeer[peer]?.addFirst(parcel) ?: return
        changes.value++
    }

    /** Takes the next frame for [peer], waiting for one and for its lane to open. */
    suspend fun next(peer: NodeId): Parcel {
        while (true) {
            val seen = changes.value
            byPeer[peer]?.removeFirstOrNull()?.let { return it }
            changes.first { it != seen }
        }
    }

    companion object {
        /** The most relayed frames one lane holds. */
        const val CAPACITY: Int = 32
    }
}

/**
 * A frame waiting in a lane: another node's frame if [relayed], else this node's; [render] makes its
 * bytes at write time, [settle] hears how the write ended.
 */
internal class Parcel(
    val messageId: ByteArray,
    val relayed: Boolean,
    val render: () -> ByteArray,
    val settle: (Settled) -> Unit,
)
