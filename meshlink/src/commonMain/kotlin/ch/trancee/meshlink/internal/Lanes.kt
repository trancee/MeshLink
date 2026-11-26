package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * The frames waiting for each linked peer's link, each a [Parcel]: at most [CAPACITY] relayed frames
 * per peer, while this node's own frames are bounded by its send queue. A lane full of relayed frames
 * drops a new one; a frame whose `message_id` is already waiting replaces it in place. A frame no
 * longer wanted when its turn comes is skipped, and the frame taken for each peer is being written
 * until its write settles.
 *
 * With [broadcastPriority], a new `BROADCAST` goes ahead of the waiting `MESSAGE` frames, behind the
 * other frames waiting; each waiting `MESSAGE` lets at most [OVERTAKES_MAX] broadcasts ahead of it.
 *
 * Runs on the node's serial dispatcher.
 */
internal class Lanes(
    private val broadcastPriority: Boolean = false,
) {
    private val byPeer = mutableMapOf<NodeId, ArrayDeque<Slot>>()
    private val changes = MutableStateFlow(0L)
    private val taken = mutableMapOf<NodeId, ByteArray>()

    /** The peers with a lane: those linked now. */
    val peers: List<NodeId> get() = byPeer.keys.toList()

    /** Opens [peer]'s lane, keeping its frames if it is already open. */
    fun open(peer: NodeId) {
        byPeer.getOrPut(peer) { ArrayDeque() }
    }

    /** Closes [peer]'s lane and drops its frames. */
    fun close(peer: NodeId) {
        byPeer -= peer
        taken -= peer
    }

    /** Queues [parcel] for [peer]; returns whether its lane took it. */
    fun put(
        peer: NodeId,
        parcel: Parcel,
    ): Boolean {
        val lane = byPeer[peer] ?: return false
        val at = lane.indexOfFirst { it.parcel.messageId.contentEquals(parcel.messageId) }
        val took =
            when {
                at >= 0 -> true.also { lane[at] = Slot(parcel, lane[at].overtaken) }
                !parcel.relayed || lane.count { it.parcel.relayed } < CAPACITY -> true.also { lane.queue(parcel) }
                else -> false
            }
        if (took) changes.value++
        return took
    }

    private fun ArrayDeque<Slot>.queue(parcel: Parcel) {
        val ahead =
            if (broadcastPriority && parcel.kind == Parcel.Kind.BROADCAST) {
                val open = indexOfLast { it.waiting && it.overtaken >= OVERTAKES_MAX } + 1
                (open until size).firstOrNull { this[it].waiting }
            } else {
                null
            }
        if (ahead == null) {
            add(Slot(parcel))
        } else {
            add(ahead, Slot(parcel))
            for (index in ahead + 1 until size) this[index].overtaken++
        }
    }

    /** Puts [parcel], whose write failed, back at the front of [peer]'s lane if it is open. */
    fun putBack(
        peer: NodeId,
        parcel: Parcel,
    ) {
        byPeer[peer]?.addFirst(Slot(parcel)) ?: return
        changes.value++
    }

    /** Takes the next wanted frame for [peer], waiting for one and for its lane to open. */
    suspend fun next(peer: NodeId): Parcel {
        while (true) {
            val seen = changes.value
            val lane = byPeer[peer]
            while (lane != null && lane.isNotEmpty()) {
                val parcel = lane.removeFirst().parcel
                if (parcel.wanted()) return parcel.also { taken[peer] = it.messageId }
            }
            changes.first { it != seen }
        }
    }

    /** Whether a frame of the message [messageId] is being written on a link. */
    fun writing(messageId: ByteArray): Boolean = taken.values.any { it.contentEquals(messageId) }

    /** The frame last taken for [peer] settled: it is no longer being written. */
    fun written(peer: NodeId) {
        taken -= peer
    }

    companion object {
        /** The most relayed frames one lane holds. */
        const val CAPACITY: Int = 32

        /** The most broadcasts a waiting `MESSAGE` lets ahead of it under broadcast priority. */
        const val OVERTAKES_MAX: Int = 3
    }

    // A parcel in a lane, with the broadcasts let ahead of it there.
    private class Slot(
        val parcel: Parcel,
        var overtaken: Int = 0,
    ) {
        val waiting: Boolean get() = parcel.kind == Parcel.Kind.MESSAGE
    }
}

/**
 * A frame waiting in a lane: another node's frame if [relayed], else this node's, of [kind]; [render]
 * makes its bytes at write time, unless [wanted] says it is no longer needed, and [settle] hears how
 * the write ended.
 */
internal class Parcel(
    val messageId: ByteArray,
    val relayed: Boolean,
    val render: () -> ByteArray,
    val wanted: () -> Boolean = { true },
    val kind: Kind = Kind.OTHER,
    val settle: (Settled) -> Unit,
) {
    /** What a lane's broadcast priority sees of a frame. */
    enum class Kind {
        /** A `BROADCAST` frame. */
        BROADCAST,

        /** A `MESSAGE` frame. */
        MESSAGE,

        /** Any other frame. */
        OTHER,
    }
}
