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
 * The node sends its list with [send] after each handshake, when its link set changes and before
 * each rotation of its own identifier, at most once per [MIN_INTERVAL_MILLIS]: a list due sooner
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
        heard[peer] = list
    }

    /** [peer]'s link closed. */
    fun unlinked(peer: NodeId) {
        heard.remove(peer)
    }

    /** The linked peer whose latest list named [id] as its current or next identifier, or `null`. */
    fun peerOf(id: ByteArray): NodeId? =
        heard.entries
            .firstOrNull { (_, list) -> list.ownId.contentEquals(id) || list.nextId.contentEquals(id) }
            ?.key

    private fun sendNow() {
        val current = own ?: return
        val neighbors = heard.values.map { it.ownId }.take(LinkFrame.NeighborList.NEIGHBORS_MAX)
        sentAt = clock.monotonicMillis()
        send(LinkFrame.NeighborList(current, next, neighbors))
    }

    companion object {
        /** Spec `neighbor-list-min-interval`. */
        const val MIN_INTERVAL_MILLIS: Long = 2_000
    }
}
