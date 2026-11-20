package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId

/**
 * The routes this node knows (spec "Unicast forwarding"): for a routing tag, the linked peer that
 * leads to it, until an expiry on the monotonic clock. At most [CAPACITY], the oldest learned
 * forgotten first. Runs on the node's serial dispatcher.
 */
internal class Routes {
    private val byTag = LinkedHashMap<String, Route>()

    /** A frame from [tag] arrived from [peer] at [nowMillis]: a reverse route to [tag] through [peer]. */
    fun learn(
        tag: ByteArray,
        peer: NodeId,
        nowMillis: Long,
    ) {
        val key = tag.toHexString()
        val old = byTag.remove(key)
        val expiry = nowMillis + REVERSE_MILLIS
        byTag[key] = Route(peer, if (old != null && old.peer == peer) maxOf(old.expiry, expiry) else expiry)
        if (byTag.size > CAPACITY) byTag -= byTag.keys.first()
    }

    /** The peer of the active route to [tag] at [nowMillis], refreshing it, or `null` if there is none. */
    fun use(
        tag: ByteArray,
        nowMillis: Long,
    ): NodeId? {
        val route = byTag[tag.toHexString()]?.takeIf { it.expiry > nowMillis } ?: return null
        // A learned or used route never holds past `nowMillis + ACTIVE_MILLIS`.
        route.expiry = nowMillis + ACTIVE_MILLIS
        return route.peer
    }

    /** The link to [peer] closed: its routes go. */
    fun unlinked(peer: NodeId) {
        byTag.values.removeAll { it.peer == peer }
    }

    private class Route(
        val peer: NodeId,
        var expiry: Long,
    )

    companion object {
        /** `reverse-route-timeout`. */
        const val REVERSE_MILLIS: Long = 6_000

        /** `route-active-timeout`. */
        const val ACTIVE_MILLIS: Long = 30_000

        /** The most routes kept. */
        const val CAPACITY: Int = 4_096
    }
}
