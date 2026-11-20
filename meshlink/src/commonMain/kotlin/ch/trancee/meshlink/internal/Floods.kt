package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId

/**
 * Flood forwarding of every flood type but route requests (spec "Flood forwarding" and
 * "BROADCAST"): a frame that decodes, is timely and is new is processed and forwarded on every
 * other link with one hop less, if one remains. A node remembers each flood `message_id` until its
 * frame's expiry plus clock skew.
 *
 * A `BROADCAST` goes to [deliver] if this profile is [accepting] its container: the `unsecured`
 * profile takes kind `0x04`, and a secured profile takes none until broadcasts are signed, so it
 * neither delivers nor relays one. A flood type this version does not know is relayed by its header
 * alone, if it is a small frame (spec "Size classes"). Any other frame is dropped without a trace.
 *
 * It also places this node's own flood frames: on every link, or on the first link that opens if
 * there is none.
 *
 * Runs on the node's serial dispatcher.
 */
internal class Floods(
    private val lanes: Lanes,
    /** Whether this profile takes the unsecured broadcast container, kind `0x04`. */
    val accepting: Boolean,
    private val deliver: (Container.Unsecured) -> Unit,
) {
    private val seen = MessageIds()
    private val waiting = mutableListOf<Parcel>()

    /** A flood frame other than a route request arrived from linked [peer] at Unix second [nowSeconds]. */
    fun received(
        peer: NodeId,
        bytes: ByteArray,
        nowSeconds: Long,
    ) {
        val frame = (FloodFrame.decode(bytes) as? Decoded.Valid ?: return).value
        val broadcast = frame.type == BroadcastFrame.TYPE
        val container = if (broadcast) accepted(bytes) else null
        val carried = if (broadcast) container != null else bytes.size <= Reassembly.FRAME_SMALL_MAX
        if (!carried || !new(frame.header, nowSeconds)) return
        container?.let(deliver)
        relay(peer, frame)
    }

    /**
     * Places [parcel], this node's own flood frame expiring at Unix second [expiry], on every link, or
     * keeps it for the first link that opens. Its echoes are dropped.
     */
    fun originate(
        parcel: Parcel,
        expiry: Long,
    ) {
        remember(parcel.messageId, expiry)
        val peers = lanes.peers
        if (peers.isEmpty()) waiting += parcel else peers.forEach { lanes.put(it, parcel) }
    }

    /** A link to [peer] completed its hello: the frames waiting for a link go to it. */
    fun linked(peer: NodeId) {
        waiting.forEach { lanes.put(peer, it) }
        waiting.clear()
    }

    /** [parcel] ended: it no longer waits for a link. */
    fun withdraw(parcel: Parcel) {
        waiting -= parcel
    }

    private fun remember(
        id: ByteArray,
        expiry: Long,
    ) {
        seen.put(id, remembered(expiry))
    }

    // The container of a BROADCAST this profile takes; null if it takes none or the frame is malformed.
    private fun accepted(bytes: ByteArray): Container.Unsecured? {
        val decoded = BroadcastFrame.decode(bytes) as? Decoded.Valid ?: return null
        return (decoded.value.container as? Container.Unsecured)?.takeIf { accepting }
    }

    // Whether a frame with [header] is timely and not seen before; remembers it if so.
    private fun new(
        header: FloodHeader,
        nowSeconds: Long,
    ): Boolean {
        val expiry = header.expiry.toLong()
        val fresh = timely(expiry, nowSeconds) && !seen.has(header.messageId, nowSeconds * MILLIS_PER_SECOND)
        if (fresh) remember(header.messageId, expiry)
        return fresh
    }

    private fun relay(
        peer: NodeId,
        frame: FloodFrame,
    ) {
        val header = frame.header
        val hopLimit = header.hopLimit - 1
        if (hopLimit < 1) return
        val bytes = FloodFrame(frame.type, FloodHeader(hopLimit, header.messageId, header.expiry), frame.body).encode()
        (lanes.peers - peer).forEach { lanes.put(it, Parcel(header.messageId, relayed = true, { bytes }) {}) }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
