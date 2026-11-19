package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A node's neighbor lists (spec "Neighbor list", D-24; ADR 0016 decision 3): the list it sends on
 * every link, and the latest one each linked peer sent it. Not thread-safe: the node calls it, and
 * runs [scope], on its serial dispatcher.
 *
 * It keeps a closed link's last list for [FORMER_MILLIS] for the overlap and bridge decisions.
 *
 * The node sends its list with [send] after each handshake, when its link set changes, when a
 * linked peer's list first names it or names it by a new identifier (so two-hop neighbors learn a
 * rotated identifier before it can pass for a bridge), and before each rotation of its own
 * identifier, at most once per [MIN_INTERVAL_MILLIS]: a list due sooner
 * waits for the interval and carries the state when it goes out. It carries the identifier the node
 * advertises, the one it advertises after its next rotation and its link peers' current ones, as
 * their latest lists named them. A node that doesn't advertise has no identifier and sends no list.
 */
internal class NeighborLists(
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val send: (LinkFrame.NeighborList) -> Unit,
) {
    private var own: ByteArray? = null
    private var next: ByteArray = ByteArray(0)
    private var sentAt: Long? = null
    private var due: Job? = null

    // The latest list from each linked peer.
    private val heard = mutableMapOf<NodeId, LinkFrame.NeighborList>()

    // The last list from each peer whose link closed, and when it closed.
    private val former = mutableMapOf<NodeId, Pair<LinkFrame.NeighborList, Long>>()

    /** This node advertises [own] now and [next] after its next rotation. */
    fun advertised(
        own: ByteArray,
        next: ByteArray,
    ) {
        this.own = own
        this.next = next
    }

    /** Whether [id] is the identifier this node advertises now or after its next rotation. */
    fun advertises(id: ByteArray): Boolean = own?.contentEquals(id) == true || next.contentEquals(id)

    /** The node's link set or identifier changes: sends its list as soon as the interval allows. */
    fun changed() {
        if (due != null) return
        val wait = sentAt?.let { it + MIN_INTERVAL_MILLIS - clock.monotonicMillis() } ?: 0
        if (wait <= 0) {
            sendNow()
        } else {
            due =
                scope.launch {
                    delay(wait)
                    due = null
                    sendNow()
                }
        }
    }

    /** [peer] sent [list] on its link. */
    fun received(
        peer: NodeId,
        list: LinkFrame.NeighborList,
    ) {
        former.remove(peer)
        val previous = heard.put(peer, list)
        if (previous == null || !previous.ownId.contentEquals(list.ownId)) changed()
    }

    /** [peer]'s link closed. */
    fun unlinked(peer: NodeId) {
        val list = heard.remove(peer) ?: return
        former[peer] = list to clock.monotonicMillis()
    }

    /** The linked peer whose latest list named [id] as its current or next identifier, or `null`. */
    fun peerOf(id: ByteArray): NodeId? =
        heard.entries
            .firstOrNull { (_, list) -> list.ownId.contentEquals(id) || list.nextId.contentEquals(id) }
            ?.key

    /** Whether a linked peer's latest list, or a former one, names [id]. */
    fun nearby(id: ByteArray): Boolean = known().any { it.names(id) }

    /** How many linked peers' latest lists, and former ones, name [id] as their neighbor. */
    fun overlap(id: ByteArray): Int = known().count { list -> list.neighbors.any { it.contentEquals(id) } }

    /** The linked peers' latest lists and the former lists closed less than [FORMER_MILLIS] ago. */
    private fun known(): List<LinkFrame.NeighborList> {
        val now = clock.monotonicMillis()
        former.values.removeAll { (_, closed) -> now - closed >= FORMER_MILLIS }
        return heard.values + former.values.map { it.first }
    }

    /**
     * How many of linked [peer]'s neighbors another linked peer's latest list names too: the
     * neighbors this node still reaches in two hops without [peer]. 0 if [peer] sent no list.
     */
    fun redundancy(peer: NodeId): Int {
        val list = heard[peer] ?: return 0
        val others = heard.filterKeys { it != peer }.values
        return list.neighbors.count { id -> !advertises(id) && others.any { it.names(id) } }
    }

    private fun sendNow() {
        val current = own ?: return
        val neighbors = heard.values.map { it.ownId }.take(LinkFrame.NeighborList.NEIGHBORS_MAX)
        sentAt = clock.monotonicMillis()
        send(LinkFrame.NeighborList(current, next, neighbors))
    }

    companion object {
        /** Spec `neighbor-list-min-interval`. */
        const val MIN_INTERVAL_MILLIS: Long = 2_000

        /** How long a node keeps using a peer's last list after their link closed (former neighbor list reuse). */
        const val FORMER_MILLIS: Long = 120_000
    }
}

/** Whether this list names [id] as its sender's current or next identifier, or as a neighbor. */
private fun LinkFrame.NeighborList.names(id: ByteArray): Boolean =
    ownId.contentEquals(id) || nextId.contentEquals(id) || neighbors.any { it.contentEquals(id) }
