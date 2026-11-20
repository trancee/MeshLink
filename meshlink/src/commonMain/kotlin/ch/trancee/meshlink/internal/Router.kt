package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId

/** What [Router] needs from the node's end-to-end layer; called on the node's dispatcher. */
internal interface Endpoint {
    /** Whether routing [tag] is this node's at Unix second [nowSeconds]. */
    fun owns(
        tag: ByteArray,
        nowSeconds: Long,
    ): Boolean

    /** Takes a `MESSAGE` [frame] for this node; says whether it accepted it. */
    fun deliver(
        frame: ByteArray,
        nowSeconds: Long,
    ): Boolean

    /** This node's current routing tag to answer and send route requests from; `null` if it has none. */
    fun tag(): ByteArray?

    /** A route discovery found a route to [tag]. */
    fun found(tag: ByteArray)

    /** Whether the mesh's profile carries `MESSAGE` container [kind]; relays drop the others. */
    fun carries(kind: Int): Boolean
}

/**
 * Unicast routing (spec "Unicast forwarding", "Origin behavior" and "Route discovery"): processes
 * the frames for this node, relays the others by their header alone, runs route discovery, and
 * places this node's own frames in [lanes]. Flood types other than route requests go to [floods].
 *
 * [routes] are the node's routes; [random] makes message ids. Runs on the node's serial dispatcher.
 */
internal class Router(
    private val clock: Clock,
    private val lanes: Lanes,
    private val routes: Routes,
    random: (size: Int) -> ByteArray,
    private val endpoint: Endpoint,
    private val floods: Floods,
) {
    private val discovery = RouteDiscovery(clock, lanes, routes, random, endpoint)
    private val seen = MessageIds()
    private val delivered = MessageIds()

    /** A routed frame arrived from linked [peer]. */
    fun received(
        peer: NodeId,
        bytes: ByteArray,
    ) {
        val nowSeconds = clock.wallClockMillis() / MILLIS_PER_SECOND
        val flood = bytes.isNotEmpty() && bytes[0].toInt() and FLOOD_MASK == FLOOD_TYPES
        if (flood) return flooded(peer, bytes, nowSeconds)
        val frame = admitted(bytes, nowSeconds) ?: return
        if (endpoint.owns(frame.header.destinationTag, nowSeconds)) {
            accept(peer, frame, bytes, nowSeconds)
        } else {
            relay(peer, frame, bytes)
        }
    }

    /**
     * Places this node's [parcel] for the destination whose current tag is [tag], from a node of
     * class [density]: on an active route unless [search], else flooded to every link with a route
     * request alongside in the medium and high classes. [parcel] makes the frame for a hop limit.
     * Returns the hop count of the route it went on, 0 for a route without one, or `null` if flooded.
     */
    fun originate(
        tag: ByteArray,
        density: DensityClass,
        search: Boolean,
        parcel: (hopLimit: Int) -> Parcel,
    ): Int? {
        val now = clock.monotonicMillis()
        val low = density == DensityClass.LOW
        val route = if (low || search) null else routes.use(tag, now)
        if (!low && route == null) discovery.search(tag, clock.wallClockMillis() / MILLIS_PER_SECOND)
        val made = parcel(if (low || route != null) HOP_LIMIT_MAX else FLOOD_RADIUS)
        seen.put(made.messageId, now + SEEN_WINDOW_MILLIS)
        val targets = if (route != null) listOf(route.peer) else lanes.peers
        targets.forEach { lanes.put(it, made) }
        return route?.hops
    }

    /** The link to [peer] closed: routes through it go. */
    fun unlinked(peer: NodeId) {
        routes.unlinked(peer)
    }

    // A frame that decodes, is timely and is carried by this mesh's profile; otherwise null.
    private fun admitted(
        bytes: ByteArray,
        nowSeconds: Long,
    ): UnicastFrame? {
        val decoded = UnicastFrame.decode(bytes)
        return when {
            decoded !is Decoded.Valid -> null

            timely(
                decoded.value.header.expiry
                    .toLong(),
                nowSeconds,
            ) && carried(decoded.value.type, bytes) -> decoded.value

            else -> null
        }
    }

    // A flood frame: route requests go to route discovery, other flood types to flood forwarding.
    private fun flooded(
        peer: NodeId,
        bytes: ByteArray,
        nowSeconds: Long,
    ) {
        if (bytes[0] != RouteRequest.TYPE.toByte()) return floods.received(peer, bytes, nowSeconds)
        val decoded = RouteRequest.decode(bytes)
        if (decoded is Decoded.Valid) discovery.requested(peer, decoded.value, nowSeconds)
    }

    // Steps 2 to 6 of spec "Unicast forwarding". A relay does not retry: a refused write drops the frame.
    private fun relay(
        peer: NodeId,
        frame: UnicastFrame,
        bytes: ByteArray,
    ) {
        val now = clock.monotonicMillis()
        routes.learn(frame.header.sourceTag, peer, now)
        if (seen.has(frame.header.messageId, now) || frame.header.hopLimit - 1 < 1) return
        val relayed = relayed(peer, frame, bytes)
        val route = relayed?.let { routes.use(frame.header.destinationTag, now)?.peer }
        val targets =
            when {
                relayed == null -> null
                route != null -> listOf(route)
                bytes.size <= Reassembly.FRAME_SMALL_MAX -> lanes.peers - peer
                else -> null
            }
        if (relayed == null || targets == null) return
        seen.put(frame.header.messageId, now + SEEN_WINDOW_MILLIS)
        targets.forEach { lanes.put(it, Parcel(frame.header.messageId, relayed = true, { relayed }) {}) }
    }

    // A MESSAGE must decode and carry a container kind the profile allows, a ROUTE_REPLY must decode;
    // other unicast types pass by header.
    private fun carried(
        type: Int,
        bytes: ByteArray,
    ): Boolean =
        when (type) {
            MessageFrame.TYPE -> {
                val message = MessageFrame.decode(bytes)
                message is Decoded.Valid && endpoint.carries(message.value.container.kind)
            }

            RouteReply.TYPE -> {
                RouteReply.decode(bytes) is Decoded.Valid
            }

            else -> {
                true
            }
        }

    // A destination processes each MESSAGE once while it is timely, and learns the route
    // each ROUTE_REPLY found; other types come later.
    private fun accept(
        peer: NodeId,
        frame: UnicastFrame,
        bytes: ByteArray,
        nowSeconds: Long,
    ) {
        if (frame.type == RouteReply.TYPE) return discovery.replied(peer, reply(bytes))
        val id = frame.header.messageId
        val now = nowSeconds * MILLIS_PER_SECOND
        if (frame.type != MessageFrame.TYPE || delivered.has(id, now) || !endpoint.deliver(bytes, nowSeconds)) return
        delivered.put(id, remembered(frame.header.expiry.toLong()))
    }

    // The frame one hop on; a ROUTE_REPLY also teaches its route and counts the hop, or is dropped
    // (null) if its hop count can't grow.
    private fun relayed(
        peer: NodeId,
        frame: UnicastFrame,
        bytes: ByteArray,
    ): ByteArray? {
        val header = frame.header
        val hopLimit = header.hopLimit - 1
        val next = UnicastHeader(hopLimit, header.messageId, header.expiry, header.destinationTag, header.sourceTag)
        if (frame.type != RouteReply.TYPE) return UnicastFrame(frame.type, next, frame.body).encode()
        return discovery.relaying(peer, reply(bytes))?.let {
            RouteReply(next, it.targetTag, it.targetSequence, it.hopCount).encode()
        }
    }

    // A ROUTE_REPLY that carried() admitted.
    private fun reply(bytes: ByteArray): RouteReply = (RouteReply.decode(bytes) as Decoded.Valid).value

    companion object {
        /** The hop limit of a frame on a route, or flooded by a low-class origin. */
        const val HOP_LIMIT_MAX: Int = 255

        /** `u6-flood-radius`: the hop limit of a frame a medium- or high-class origin floods. */
        const val FLOOD_RADIUS: Int = 3

        /** `unicast-seen-window`. */
        const val SEEN_WINDOW_MILLIS: Long = 1_000

        // Flood frames have types 0x80 to 0xBF (spec "Routing").
        private const val FLOOD_MASK = 0xc0
        private const val FLOOD_TYPES = 0x80
    }
}

/** Past [expiry] plus clock skew, or more than a lifetime plus skew ahead of [nowSeconds], a frame is dropped. */
internal fun timely(
    expiry: Long,
    nowSeconds: Long,
): Boolean =
    nowSeconds <= expiry + UnsecuredMessaging.CLOCK_SKEW_SECONDS &&
        expiry <= nowSeconds + UnsecuredMessaging.LIFETIME_MAX_SECONDS + UnsecuredMessaging.CLOCK_SKEW_SECONDS

/** The wall-clock millisecond until which a frame of [expiry] is [timely]: the end of its last timely second. */
internal fun remembered(expiry: Long): Long = (expiry + UnsecuredMessaging.CLOCK_SKEW_SECONDS + 1) * MILLIS_PER_SECOND

private const val MILLIS_PER_SECOND = 1_000L
