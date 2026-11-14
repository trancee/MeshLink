package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The NodeIds that completed a hello with this node since it was created: the peers an `unsecured`
 * send may address (ADR 0007, `UnknownPeer`). Keeps the [CAPACITY] most recently seen. Thread-safe.
 */
internal class KnownPeers {
    private val ids = MutableStateFlow(Order(emptySet()))

    operator fun contains(id: NodeId): Boolean = id in ids.value.ids

    /** Records that [id] completed a hello now, forgetting the least recently seen NodeId when full. */
    fun touch(id: NodeId) {
        ids.update {
            val next = it.ids - id + id
            Order(if (next.size > CAPACITY) next.drop(1).toSet() else next)
        }
    }

    // Compared by identity: sets that differ only in order are equal, and a StateFlow drops an equal value.
    private class Order(
        val ids: Set<NodeId>,
    )

    companion object {
        const val CAPACITY: Int = 1_024
    }
}
