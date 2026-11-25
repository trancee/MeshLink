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

    /** This node's end of delivery receipts. */
    val acknowledger: Acknowledger

    /** This node's current routing tag to answer route requests from a route it knows; `null` if it has none. */
    fun tag(): ByteArray?

    /** A route discovery found a route to [tag]. */
    fun found(tag: ByteArray)

    /** Whether the mesh's profile carries end-to-end container [kind]; relays drop the others. */
    fun carries(kind: Int): Boolean
}

/**
 * This node's end of delivery and read receipts (spec "Delivery receipts", "Read receipts"); called on
 * the node's dispatcher.
 */
internal interface Acknowledger {
    /** Takes a later copy of a `MESSAGE` [frame] this node accepted: its receipt goes again. */
    fun repeated(
        frame: ByteArray,
        nowSeconds: Long,
    )

    /** Takes a `DELIVERY_RECEIPT` [frame] for this node. */
    fun acknowledged(
        frame: ByteArray,
        nowSeconds: Long,
    )

    /** Takes a `READ_RECEIPT` [frame] for this node. */
    fun read(
        frame: ByteArray,
        nowSeconds: Long,
    )
}

/**
 * Where [Router] hands the frames it does not route itself: flood types other than route requests
 * to [floods], and a secured node's lookup frames to its [lookups]; an `unsecured` node has none.
 */
internal class Handoff(
    val floods: Floods,
    val lookups: Lookups? = null,
)

/**
 * Unicast routing (spec "Unicast forwarding", "Origin behavior" and "Route discovery"): processes
 * the frames for this node, relays the others by their header alone, runs route discovery, and
 * places this node's own frames in [lanes]. Flood types other than route requests go to the
 * [handoff]'s floods.
 *
 * A secured node passes its lookups the lookup requests, each new one leaving a reverse route keyed
 * by its salt, and the replies to its own lookups. It relays each other lookup reply on the reverse
 * route of its salt only, the first per salt (spec "Certificate lookup"). An `unsecured` node has
 * no lookups, and relays lookup frames by their headers.
 *
 * [routes] are the node's routes; [random] makes message ids. Runs on the node's serial dispatcher.
 */
internal class Router(
    private val clock: Clock,
    private val lanes: Lanes,
    private val routes: Routes,
    random: (size: Int) -> ByteArray,
    private val endpoint: Endpoint,
    handoff: Handoff,
) {
    private val floods = handoff.floods
    private val lookups = handoff.lookups
    private val seen = MessageIds()
    private val delivered = MessageIds()

    /** This node's hand-over of the unicasts it holds for others. */
    val handOver: HandOver = HandOver(clock, lanes, routes, endpoint, floods.holding, delivered)
    private val discovery =
        RouteDiscovery(clock, lanes, routes, random, endpoint) { tag, peer ->
            endpoint.found(tag)
            handOver.flush(tag, peer)
        }

    /** A routed frame arrived from linked [peer]. */
    fun received(
        peer: NodeId,
        bytes: ByteArray,
    ) {
        val nowSeconds = clock.wallClockMillis() / MILLIS_PER_SECOND
        val flood = bytes.isNotEmpty() && bytes[0].toInt() and FLOOD_MASK == FLOOD_TYPES
        if (flood) return flooded(peer, bytes, nowSeconds)
        val frame = admitted(bytes, nowSeconds) ?: return
        when {
            frame.type == LookupReply.TYPE && lookups?.replied(lookupReply(bytes)) == true -> {}

            endpoint.owns(frame.header.destinationTag, nowSeconds) -> {
                accept(peer, frame, bytes, nowSeconds)
            }

            else -> {
                relay(peer, frame, bytes)
            }
        }
    }

    /**
     * Places this node's [parcel] for the destination whose current tag is [tag], from a node of
     * class [density]: on an active route unless [search], else flooded to every link with a route
     * request from [origin] alongside in the medium and high classes; none without an [origin].
     * [parcel] makes the frame for a hop limit. Returns the hop count of the route it went on, 0 for
     * a route without one, or `null` if flooded.
     */
    fun originate(
        tag: ByteArray,
        origin: ByteArray?,
        density: DensityClass,
        search: Boolean,
        parcel: (hopLimit: Int) -> Parcel,
    ): Int? {
        val now = clock.monotonicMillis()
        val low = density == DensityClass.LOW
        val route = if (low || search) null else routes.use(tag, now)
        if (!low && route == null && origin != null) {
            discovery.search(tag, origin, clock.wallClockMillis() / MILLIS_PER_SECOND)
        }
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

    // A flood frame: route requests go to route discovery, a secured node's lookup requests to its
    // lookups, other flood types to flood forwarding.
    private fun flooded(
        peer: NodeId,
        bytes: ByteArray,
        nowSeconds: Long,
    ) {
        val type = bytes[0].toInt() and BYTE
        when {
            type == RouteRequest.TYPE -> {
                (RouteRequest.decode(bytes) as? Decoded.Valid)?.let { discovery.requested(peer, it.value, nowSeconds) }
            }

            type == LookupRequest.TYPE && lookups != null -> {
                val request = (LookupRequest.decode(bytes) as? Decoded.Valid)?.value
                if (request != null && lookups.answers.requested(peer, request, nowSeconds)) {
                    routes.learn(request.salt, peer, clock.monotonicMillis())
                }
            }

            else -> {
                floods.received(peer, bytes, nowSeconds)
            }
        }
    }

    // Steps 2 to 6 of spec "Unicast forwarding". A relay does not retry: a refused write drops the frame.
    private fun relay(
        peer: NodeId,
        frame: UnicastFrame,
        bytes: ByteArray,
    ) {
        val now = clock.monotonicMillis()
        if (seen.has(frame.header.messageId, now)) return
        routes.learn(frame.header.sourceTag, peer, now)
        handOver.flush(frame.header.sourceTag, peer)
        val relayed = if (frame.header.hopLimit > 1) relayed(peer, frame, bytes) else null
        val targets = relayed?.let { targets(peer, frame, bytes.size, now) }
        if (relayed == null || targets == null) return
        seen.put(frame.header.messageId, now + SEEN_WINDOW_MILLIS)
        targets.forEach { lanes.put(it, Parcel(frame.header.messageId, relayed = true, { relayed }) {}) }
        handOver.relayed(peer, frame, relayed)
    }

    // The links a relayed frame from [peer] goes on: its active route, else every other link for a
    // small frame; null drops it. A secured node's lookup reply goes on a route only, and only the
    // first per salt.
    private fun targets(
        peer: NodeId,
        frame: UnicastFrame,
        size: Int,
        now: Long,
    ): List<NodeId>? {
        val route = routes.use(frame.header.destinationTag, now)?.peer
        return when {
            frame.type == LookupReply.TYPE && lookups != null -> {
                route
                    ?.takeIf { lookups.answers.passes(frame.header.destinationTag, frame.header.expiry.toLong()) }
                    ?.let(::listOf)
            }

            route != null -> {
                listOf(route)
            }

            size <= Reassembly.FRAME_SMALL_MAX -> {
                lanes.peers - peer
            }

            else -> {
                null
            }
        }
    }

    // A MESSAGE, DELIVERY_RECEIPT or READ_RECEIPT must decode and carry a container kind the profile allows, a
    // ROUTE_REPLY must decode, and so must a secured node's LOOKUP_REPLY; other unicast types pass by header.
    private fun carried(
        type: Int,
        bytes: ByteArray,
    ): Boolean =
        when (type) {
            MessageFrame.TYPE -> {
                val message = MessageFrame.decode(bytes)
                message is Decoded.Valid && endpoint.carries(message.value.container.kind)
            }

            DeliveryReceipt.TYPE -> {
                val receipt = DeliveryReceipt.decode(bytes)
                receipt is Decoded.Valid && endpoint.carries(receipt.value.container.kind)
            }

            ReadReceipt.TYPE -> {
                val read = ReadReceipt.decode(bytes)
                read is Decoded.Valid && endpoint.carries(read.value.container.kind)
            }

            RouteReply.TYPE -> {
                RouteReply.decode(bytes) is Decoded.Valid
            }

            LookupReply.TYPE -> {
                lookups == null || LookupReply.decode(bytes) is Decoded.Valid
            }

            else -> {
                true
            }
        }

    // A destination learns the reverse route to each frame's source, which its receipts go back on;
    // takes each MESSAGE once while it is timely and every later copy as a repeat; takes its
    // DELIVERY_RECEIPTs and READ_RECEIPTs; and learns the route each ROUTE_REPLY found. Other types come later.
    private fun accept(
        peer: NodeId,
        frame: UnicastFrame,
        bytes: ByteArray,
        nowSeconds: Long,
    ) {
        if (frame.type == RouteReply.TYPE) return discovery.replied(peer, reply(bytes))
        routes.learn(frame.header.sourceTag, peer, clock.monotonicMillis())
        handOver.flush(frame.header.sourceTag, peer)
        when (frame.type) {
            MessageFrame.TYPE -> message(frame, bytes, nowSeconds)
            DeliveryReceipt.TYPE -> endpoint.acknowledger.acknowledged(bytes, nowSeconds)
            ReadReceipt.TYPE -> endpoint.acknowledger.read(bytes, nowSeconds)
        }
    }

    private fun message(
        frame: UnicastFrame,
        bytes: ByteArray,
        nowSeconds: Long,
    ) {
        val id = frame.header.messageId
        when {
            delivered.has(id, nowSeconds * MILLIS_PER_SECOND) -> endpoint.acknowledger.repeated(bytes, nowSeconds)
            endpoint.deliver(bytes, nowSeconds) -> delivered.put(id, remembered(frame.header.expiry.toLong()))
        }
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
        private const val BYTE = 0xff
    }
}

// A ROUTE_REPLY that carried() admitted.
private fun reply(bytes: ByteArray): RouteReply = (RouteReply.decode(bytes) as Decoded.Valid).value

// A secured node's LOOKUP_REPLY that carried() admitted.
private fun lookupReply(bytes: ByteArray): LookupReply = (LookupReply.decode(bytes) as Decoded.Valid).value

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
