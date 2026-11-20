package ch.trancee.meshlink.internal

import ch.trancee.meshlink.Peer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The peers a node lists (ADR 0007 decision 10): its direct peers, and the routed peers that are not
 * also direct, ordered by NodeId. Runs on the node's serial dispatcher.
 */
internal class Roster {
    private val listed = MutableStateFlow<List<Peer>>(emptyList())
    private var direct = emptyList<Peer>()
    private var routed = emptyList<Peer>()

    /** The listed peers. */
    val peers: StateFlow<List<Peer>> = listed.asStateFlow()

    /** The peers this node has a link to are now [peers]. */
    fun direct(peers: List<Peer>) {
        direct = peers
        publish()
    }

    /** The peers this node reaches on a route are now [peers]. */
    fun routed(peers: List<Peer>) {
        routed = peers
        publish()
    }

    private fun publish() {
        val linked = direct.map { it.nodeId }.toSet()
        listed.value = (direct + routed.filter { it.nodeId !in linked }).sortedBy { it.nodeId.toString() }
    }
}
