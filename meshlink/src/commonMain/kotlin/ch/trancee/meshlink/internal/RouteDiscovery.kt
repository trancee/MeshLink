package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId

/**
 * Route discovery (spec "Route discovery" and "Flood forwarding"): sends this node's route requests,
 * answers or floods on the requests it receives, and learns the routes replies find into [routes].
 * Each new route, to a request's origin or a reply's target, goes to [found] with its link.
 * Runs on the node's serial dispatcher.
 */
internal class RouteDiscovery(
    private val clock: Clock,
    private val lanes: Lanes,
    private val routes: Routes,
    private val random: (size: Int) -> ByteArray,
    private val endpoint: Endpoint,
    private val found: (tag: ByteArray, peer: NodeId) -> Unit,
) {
    private val floods = MessageIds()
    private var sequence = 1u

    /** Sends a route request for [targetTag] from [origin], the tag its replies come back to, on every link. */
    fun search(
        targetTag: ByteArray,
        origin: ByteArray,
        nowSeconds: Long,
    ) {
        sequence = Sequence.next(sequence)
        val header = FloodHeader(RADIUS, random(MESSAGE_ID), (nowSeconds + LIFETIME_MIN_SECONDS).toUInt())
        val request = RouteRequest(header, targetTag, origin, sequence, routes.sequence(targetTag), 0).encode()
        floods.put(header.messageId, remembered(header.expiry.toLong()))
        lanes.peers.forEach { lanes.put(it, Parcel(header.messageId, relayed = false, { request }) {}) }
    }

    /**
     * Timely [request] arrived from [peer] at Unix second [nowSeconds] (the router drops untimely
     * ones). Unless it is seen or its hop count can't grow, it teaches a route back to its origin;
     * then this node answers it as its target, answers it from a fresh enough route, or floods it on.
     */
    fun requested(
        peer: NodeId,
        request: RouteRequest,
        nowSeconds: Long,
        admits: () -> Boolean,
    ) {
        val header = request.header
        val hops = request.hopCount + 1
        if (floods.has(header.messageId, clock.wallClockMillis()) || hops > HOPS_MAX) return
        floods.put(header.messageId, remembered(header.expiry.toLong()))
        val now = clock.monotonicMillis()
        val back =
            Routes.Route(peer, now + Routes.REVERSE_MILLIS, request.originSequence, hops, clock.wallClockMillis())
        if (routes.offer(request.originTag, back, now)) found(request.originTag, peer)
        val known = routes.active(request.targetTag, now)
        val own = endpoint.tag()
        when {
            endpoint.owns(request.targetTag, nowSeconds) -> {
                sequence = Sequence.next(sequence)
                reply(peer, request, request.targetTag, sequence, 0)
            }

            own != null && known != null && fresh(known, request, peer) -> {
                reply(peer, request, own, known.sequence, known.hops)
            }

            header.hopLimit > 1 && admits() -> {
                val next = FloodHeader(header.hopLimit - 1, header.messageId, header.expiry)
                val bytes =
                    with(request) { RouteRequest(next, targetTag, originTag, originSequence, targetSequence, hops) }
                        .encode()
                (lanes.peers - peer).forEach { lanes.put(it, Parcel(header.messageId, relayed = true, { bytes }) {}) }
            }
        }
    }

    /** A [reply] for this node arrived from [peer]: it teaches the route to its target. */
    fun replied(
        peer: NodeId,
        reply: RouteReply,
    ) {
        learn(peer, reply)
    }

    /**
     * A [reply] for another node arrived from [peer] to be relayed: it teaches the route to its
     * target, and the reply goes on with one hop more; `null` if its hop count can't grow.
     */
    fun relaying(
        peer: NodeId,
        reply: RouteReply,
    ): RouteReply? {
        val hops = learn(peer, reply) ?: return null
        return RouteReply(reply.header, reply.targetTag, reply.targetSequence, hops)
    }

    // The route a reply found, one hop more than its hop count; null if that is past a byte.
    private fun learn(
        peer: NodeId,
        reply: RouteReply,
    ): Int? {
        val hops = reply.hopCount + 1
        if (hops > HOPS_MAX) return null
        val now = clock.monotonicMillis()
        val route = Routes.Route(peer, now + Routes.FORWARD_MILLIS, reply.targetSequence, hops, clock.wallClockMillis())
        if (routes.offer(reply.targetTag, route, now)) found(reply.targetTag, peer)
        return hops
    }

    // An intermediate node answers from a route whose sequence is at least the one asked for, and
    // that doesn't lead back through the asker.
    private fun fresh(
        known: Routes.Route,
        request: RouteRequest,
        peer: NodeId,
    ): Boolean =
        known.sequence != 0u &&
            known.peer != peer &&
            (request.targetSequence == 0u || !Sequence.newer(request.targetSequence, known.sequence))

    // A reply from [source] to the request's origin, on the link the request came from.
    private fun reply(
        peer: NodeId,
        request: RouteRequest,
        source: ByteArray,
        sequence: UInt,
        hops: Int,
    ) {
        val expiry = (clock.wallClockMillis() / MILLIS_PER_SECOND + LIFETIME_MIN_SECONDS).toUInt()
        val header = UnicastHeader(Router.HOP_LIMIT_MAX, random(MESSAGE_ID), expiry, request.originTag, source)
        val bytes = RouteReply(header, request.targetTag, sequence, hops).encode()
        lanes.put(peer, Parcel(header.messageId, relayed = true, { bytes }) {})
    }

    companion object {
        /** `route-request-radius`: the hop limit of this node's route requests. */
        const val RADIUS: Int = 64

        /** `message-lifetime-min`: how long this node's route requests and replies live. */
        const val LIFETIME_MIN_SECONDS: Long = 120

        private const val MESSAGE_ID = 12
        private const val HOPS_MAX = 255
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
