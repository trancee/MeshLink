package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId

/**
 * The routes this node knows (spec "Unicast forwarding" and "Route discovery"): for a routing tag,
 * the linked peer that leads to it until an expiry on the monotonic clock, and for a route learned
 * by route discovery its destination sequence and hop count. At most [CAPACITY], the oldest learned
 * forgotten first. Runs on the node's serial dispatcher.
 */
internal class Routes {
    private val byTag = LinkedHashMap<String, Route>()

    /**
     * A frame from [tag] arrived from [peer] at [nowMillis]: a reverse route to [tag] through [peer],
     * without a sequence. It refreshes a live route on the same link, and never replaces a live
     * sequenced route on another link.
     */
    fun learn(
        tag: ByteArray,
        peer: NodeId,
        nowMillis: Long,
    ) {
        val key = tag.toHexString()
        val live = byTag[key]?.takeIf { it.expiry > nowMillis }
        val expiry = nowMillis + REVERSE_MILLIS
        when {
            live == null -> put(key, Route(peer, expiry))
            live.peer == peer -> put(key, live.also { it.expiry = maxOf(it.expiry, expiry) })
            live.sequence == 0u -> put(key, Route(peer, expiry))
        }
    }

    /**
     * A route discovery found [route] to [tag] at [nowMillis]. It replaces a live route with an older
     * sequence, the same sequence and more hops, or no sequence; the same route offered again keeps
     * the later expiry. A route taken was found at [nowMillis]. Returns whether [route] is now the route
     * to [tag].
     */
    fun offer(
        tag: ByteArray,
        route: Route,
        nowMillis: Long,
    ): Boolean {
        val key = tag.toHexString()
        val live = byTag[key]?.takeIf { it.expiry > nowMillis }
        val taken =
            when {
                live == null || live.sequence == 0u -> true
                route.sequence != live.sequence -> Sequence.newer(route.sequence, live.sequence)
                route.hops != live.hops -> route.hops < live.hops
                else -> route.peer == live.peer
            }
        if (taken) {
            route.foundMillis = nowMillis
            if (live?.peer == route.peer) route.expiry = maxOf(route.expiry, live.expiry)
            put(key, route)
        }
        return taken
    }

    /**
     * The active route to [tag] at [nowMillis], only one route discovery found within [ACTIVE_MILLIS]
     * if [discovered], or `null` if there is none. Using it keeps it active at least [ACTIVE_MILLIS] more.
     */
    fun use(
        tag: ByteArray,
        nowMillis: Long,
        discovered: Boolean = false,
    ): Route? = active(tag, nowMillis, discovered)?.also { it.expiry = maxOf(it.expiry, nowMillis + ACTIVE_MILLIS) }

    /**
     * The active route to [tag] at [nowMillis], unchanged, only one route discovery found within
     * [ACTIVE_MILLIS] if [discovered], or `null` if there is none. A relay's copy of a found route lasts
     * at least [FORWARD_MILLIS] from when it was found unless used, so a route found that recently still
     * holds along its whole path for a large frame's airtime; one kept active only by this node's use
     * may not (D-58).
     */
    fun active(
        tag: ByteArray,
        nowMillis: Long,
        discovered: Boolean = false,
    ): Route? =
        byTag[tag.toHexString()]?.takeIf {
            it.expiry > nowMillis && (!discovered || (it.sequence != 0u && nowMillis - it.foundMillis < ACTIVE_MILLIS))
        }

    /** The last sequence this node knows for [tag], even if its route expired; 0 if none. */
    fun sequence(tag: ByteArray): UInt = byTag[tag.toHexString()]?.sequence ?: 0u

    /** The link to [peer] closed: its routes go. */
    fun unlinked(peer: NodeId) {
        byTag.values.removeAll { it.peer == peer }
    }

    // Re-inserted as the newest; past capacity the oldest goes.
    private fun put(
        key: String,
        route: Route,
    ) {
        byTag.remove(key)
        byTag[key] = route
        if (byTag.size > CAPACITY) byTag -= byTag.keys.first()
    }

    /**
     * A route through [peer] until [expiry]: one route discovery found has a [sequence] of at least 1,
     * [hops] of at least 1, and the wall-clock millisecond it was [heardMillis]; a reverse route has
     * none of them, all 0.
     */
    class Route(
        val peer: NodeId,
        var expiry: Long,
        val sequence: UInt = 0u,
        val hops: Int = 0,
        val heardMillis: Long = 0,
    ) {
        /** When on the monotonic clock [offer] took it. */
        var foundMillis: Long = 0
    }

    companion object {
        /** `reverse-route-timeout`. */
        const val REVERSE_MILLIS: Long = 6_000

        /** `route-active-timeout`. */
        const val ACTIVE_MILLIS: Long = 30_000

        /** `forward-route-timeout`: how long a route a reply found first holds. */
        const val FORWARD_MILLIS: Long = 60_000

        /** The most routes kept. */
        const val CAPACITY: Int = 4_096
    }
}
