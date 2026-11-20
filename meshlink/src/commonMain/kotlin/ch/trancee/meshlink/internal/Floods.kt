package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.ReceivedMessage

/**
 * Flood forwarding of every flood type but route requests (spec "Flood forwarding" and
 * "BROADCAST"): a frame that decodes, is timely and is new is processed and forwarded on every
 * other link with one hop less, if one remains. A node remembers each flood `message_id` until its
 * frame's expiry plus clock skew.
 *
 * A `BROADCAST` is carried only if [codec] opens it: the `unsecured` profile takes kind `0x04`, and a
 * secured profile takes kind `0x05` with a valid signature, so a forged or tampered broadcast is
 * neither delivered nor relayed. Its message goes to [deliver]. A flood type this version does not
 * know is relayed by its header alone, if it is a small frame (spec "Size classes"). Any other frame
 * is dropped without a trace, and its `message_id` is not remembered, so a forgery cannot shadow the
 * genuine frame.
 *
 * A `BROADCAST` spreads through [tree] (spec "Plumtree"), which also takes the link-local `IHAVE`,
 * `GRAFT` and `PRUNE` frames; a duplicate `BROADCAST` goes to it too.
 *
 * It also places this node's own broadcasts: through [tree], or on the first link that opens if
 * there is none.
 *
 * Runs on the node's serial dispatcher.
 */
internal class Floods(
    private val lanes: Lanes,
    private val codec: BroadcastCodec,
    private val tree: Plumtree,
    private val deliver: (ReceivedMessage) -> Unit,
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
        val header = frame.header
        val expiry = header.expiry.toLong()
        // Freshness first, so a duplicate costs no signature check.
        when {
            !timely(expiry, nowSeconds) -> {}

            seen.has(header.messageId, nowSeconds * MILLIS_PER_SECOND) -> {
                duplicate(peer, frame.type)
            }

            carried(frame.type, bytes) -> {
                remember(header.messageId, expiry).also { relay(peer, frame) }
            }
        }
    }

    /** A link-local `IHAVE`, `GRAFT` or `PRUNE` arrived from linked [peer] at wall-clock [nowMillis]. */
    fun plumtree(
        peer: NodeId,
        frame: LinkFrame,
        nowMillis: Long,
    ) {
        when (frame) {
            is LinkFrame.Ihave -> {
                frame.messageIds.filterNot { seen.has(it, nowMillis) }.forEach { tree.announced(peer, it) }
            }

            is LinkFrame.Graft -> {
                tree.grafted(peer, frame.messageId)
            }

            else -> {
                tree.pruned(peer)
            }
        }
    }

    /** The last link to [peer] closed. */
    fun unlinked(peer: NodeId) {
        tree.unlinked(peer)
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
        if (lanes.peers.isEmpty()) waiting += parcel else tree.spread(null, parcel, expiry)
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
        tree.arrived(id)
    }

    private fun duplicate(
        peer: NodeId,
        type: Int,
    ) {
        if (type == BroadcastFrame.TYPE) tree.duplicate(peer)
    }

    // Whether a frame of [type] is carried: a BROADCAST the codec opens, delivered here, or a small frame of
    // another type.
    private fun carried(
        type: Int,
        bytes: ByteArray,
    ): Boolean =
        if (type == BroadcastFrame.TYPE) {
            codec.open(bytes)?.also(deliver) != null
        } else {
            bytes.size <= Reassembly.FRAME_SMALL_MAX
        }

    private fun relay(
        peer: NodeId,
        frame: FloodFrame,
    ) {
        val header = frame.header
        val hopLimit = header.hopLimit - 1
        if (hopLimit < 1) return
        val bytes = FloodFrame(frame.type, FloodHeader(hopLimit, header.messageId, header.expiry), frame.body).encode()
        val parcel = Parcel(header.messageId, relayed = true, { bytes }) {}
        if (frame.type == BroadcastFrame.TYPE) {
            tree.spread(peer, parcel, header.expiry.toLong())
        } else {
            (lanes.peers - peer).forEach { lanes.put(it, parcel) }
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
