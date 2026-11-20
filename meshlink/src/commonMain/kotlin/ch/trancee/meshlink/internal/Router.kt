package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId

/**
 * Unicast routing (spec "Unicast forwarding" and "Origin behavior"): processes the frames for this
 * node, relays the others by their header alone, and places this node's own frames in [lanes].
 *
 * [kinds] are the `MESSAGE` container kinds the mesh's profile carries; [owns] says whether a
 * routing tag is this node's at a Unix second; [deliver] takes a `MESSAGE` for this node and says
 * whether it accepted it. Runs on the node's serial dispatcher.
 */
internal class Router(
    private val clock: Clock,
    private val lanes: Lanes,
    private val kinds: Set<Int>,
    private val owns: (tag: ByteArray, nowSeconds: Long) -> Boolean,
    private val deliver: (frame: ByteArray, nowSeconds: Long) -> Boolean,
) {
    private val routes = Routes()
    private val seen = MessageIds()
    private val delivered = MessageIds()

    /** A routed frame arrived from linked [peer]. */
    fun received(
        peer: NodeId,
        bytes: ByteArray,
    ) {
        val nowSeconds = clock.wallClockMillis() / MILLIS_PER_SECOND
        val frame = admitted(bytes, nowSeconds) ?: return
        if (owns(frame.header.destinationTag, nowSeconds)) {
            accept(frame, bytes, nowSeconds)
        } else {
            relay(peer, frame, bytes.size)
        }
    }

    /**
     * Places this node's message [messageId] for the destination whose current tag is [tag], from a
     * node of class [density]: on an active route, else flooded to every link. [render] makes the
     * frame's bytes for a hop limit at write time; [settle] hears how each write ended.
     */
    fun originate(
        messageId: ByteArray,
        tag: ByteArray,
        density: DensityClass,
        render: (hopLimit: Int) -> ByteArray,
        settle: (Settled) -> Unit,
    ) {
        val now = clock.monotonicMillis()
        seen.put(messageId, now + SEEN_WINDOW_MILLIS)
        val route = if (density == DensityClass.LOW) null else routes.use(tag, now)
        val hopLimit = if (density == DensityClass.LOW || route != null) HOP_LIMIT_MAX else FLOOD_RADIUS
        val targets = if (route != null) listOf(route) else lanes.peers
        targets.forEach { lanes.put(it, Parcel(messageId, relayed = false, { render(hopLimit) }, settle)) }
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

    // Steps 2 to 6 of spec "Unicast forwarding". A relay does not retry: a refused write drops the frame.
    private fun relay(
        peer: NodeId,
        frame: UnicastFrame,
        size: Int,
    ) {
        val now = clock.monotonicMillis()
        routes.learn(frame.header.sourceTag, peer, now)
        if (seen.has(frame.header.messageId, now) || frame.header.hopLimit - 1 < 1) return
        val route = routes.use(frame.header.destinationTag, now)
        val targets =
            when {
                route != null -> listOf(route)
                size <= Reassembly.FRAME_SMALL_MAX -> lanes.peers - peer
                else -> return
            }
        seen.put(frame.header.messageId, now + SEEN_WINDOW_MILLIS)
        val relayed = relayed(frame)
        targets.forEach { lanes.put(it, Parcel(frame.header.messageId, relayed = true, { relayed }) {}) }
    }

    // Past expiry plus clock skew, or more than a lifetime plus skew ahead, a frame is dropped.
    private fun timely(
        expiry: Long,
        nowSeconds: Long,
    ): Boolean =
        nowSeconds <= expiry + UnsecuredMessaging.CLOCK_SKEW_SECONDS &&
            expiry <= nowSeconds + UnsecuredMessaging.LIFETIME_MAX_SECONDS + UnsecuredMessaging.CLOCK_SKEW_SECONDS

    // A MESSAGE must decode and carry a container kind the profile allows; other unicast types pass by header.
    private fun carried(
        type: Int,
        bytes: ByteArray,
    ): Boolean {
        if (type != MessageFrame.TYPE) return true
        val decoded = MessageFrame.decode(bytes)
        return decoded is Decoded.Valid && decoded.value.container.kind in kinds
    }

    // A destination processes each MESSAGE once until its expiry plus clock skew; other types come later.
    private fun accept(
        frame: UnicastFrame,
        bytes: ByteArray,
        nowSeconds: Long,
    ) {
        val id = frame.header.messageId
        val now = nowSeconds * MILLIS_PER_SECOND
        if (frame.type != MessageFrame.TYPE || delivered.has(id, now) || !deliver(bytes, nowSeconds)) return
        delivered.put(id, (frame.header.expiry.toLong() + UnsecuredMessaging.CLOCK_SKEW_SECONDS) * MILLIS_PER_SECOND)
    }

    private fun relayed(frame: UnicastFrame): ByteArray {
        val header = frame.header
        val hopLimit = header.hopLimit - 1
        val next = UnicastHeader(hopLimit, header.messageId, header.expiry, header.destinationTag, header.sourceTag)
        return UnicastFrame(frame.type, next, frame.body).encode()
    }

    companion object {
        /** The hop limit of a frame on a route, or flooded by a low-class origin. */
        const val HOP_LIMIT_MAX: Int = 255

        /** `u6-flood-radius`: the hop limit of a frame a medium- or high-class origin floods. */
        const val FLOOD_RADIUS: Int = 3

        /** `unicast-seen-window`. */
        const val SEEN_WINDOW_MILLIS: Long = 1_000

        private const val MILLIS_PER_SECOND = 1_000L
    }
}
