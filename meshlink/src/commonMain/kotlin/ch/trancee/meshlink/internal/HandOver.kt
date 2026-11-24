package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId

/**
 * A relay's hand-over of the unicasts it holds for others (spec "Holding" and "Catch-up", D-52): it
 * holds those it relays with no active route, claims by `GRAFT` the ones a peer's `CATCH_UP` lists
 * for this node, and sends its own to a destination tag on a new route. A holder runs no timed
 * retries. [delivered] are the message ids this node took. Runs on the node's serial dispatcher.
 */
internal class HandOver(
    private val clock: Clock,
    private val lanes: Lanes,
    private val routes: Routes,
    private val endpoint: Endpoint,
    private val holding: Holding,
    private val delivered: MessageIds,
) {
    private val claimed = MessageIds()

    /** Holds [frame] as [relayed] from [peer] if it is a held type and has no active route on. */
    fun relayed(
        peer: NodeId,
        frame: UnicastFrame,
        relayed: ByteArray,
    ) {
        val header = frame.header
        if (frame.type in HELD_TYPES && routes.active(header.destinationTag, clock.monotonicMillis()) == null) {
            val copy = HeldFrame(peer, header.messageId, header.destinationTag, header.expiry.toLong(), relayed)
            holding.hold(copy, header.hopLimit, clock.wallClockMillis())
        }
    }

    /**
     * Linked [peer]'s `CATCH_UP` listed [held] unicasts (spec "Catch-up"): this node claims, by
     * `GRAFT`, each not delivered here, not claimed within the catch-up window, and toward one of its
     * own tags or a destination it has an active route to through another link.
     */
    fun caughtUp(
        peer: NodeId,
        held: List<LinkFrame.CatchUp.Held>,
    ) {
        val now = clock.monotonicMillis()
        val nowSeconds = clock.wallClockMillis() / MILLIS_PER_SECOND
        held.filter { claims(peer, it, now, nowSeconds) }.forEach {
            claimed.put(it.messageId, now + CLAIM_WINDOW_MILLIS)
            val graft = LinkFrame.Graft(it.messageId).encode()
            lanes.put(peer, Parcel(graft, relayed = false, { graft }) {})
        }
    }

    /** The frames held toward [tag] go on its new [route], each once and never back to the link it came from. */
    fun flush(
        tag: ByteArray,
        route: NodeId,
    ) {
        holding
            .toward(tag, clock.wallClockMillis())
            .filter { it.from != route && it.sentTo.add(route) }
            .forEach { lanes.put(route, it.parcel()) }
    }

    private fun claims(
        peer: NodeId,
        held: LinkFrame.CatchUp.Held,
        now: Long,
        nowSeconds: Long,
    ): Boolean {
        val id = held.messageId
        val wanted =
            endpoint.owns(held.destinationTag, nowSeconds) ||
                routes.active(held.destinationTag, now).let { it != null && it.peer != peer }
        return wanted && !delivered.has(id, nowSeconds * MILLIS_PER_SECOND) && !claimed.has(id, now)
    }

    companion object {
        /** `catch-up-window`: how long a claimed held message is not claimed again. */
        const val CLAIM_WINDOW_MILLIS: Long = 60_000

        // The unicast types a relay holds when it has no route on (spec "Holding").
        private val HELD_TYPES = setOf(MessageFrame.TYPE, DeliveryReceipt.TYPE, ReadReceipt.TYPE)

        private const val MILLIS_PER_SECOND = 1_000L
    }
}
