package meshlink.routingsim.routing

import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max

/**
 * U1 managed flood (unicast) and B1 flood / B2 fanout subsetting (mesh broadcast).
 *
 * Every node forwards a new message to all links except the ingress link, with message-ID dedup
 * and a hop limit. With [fanout], relays forward only to a message-ID-seeded subset of
 * max(fanoutMin, ceil(log2(links))) links; the originator always sends on all links (B2).
 * A unicast destination delivers and does not forward.
 */
class Flood(
    private val port: RoutingPort,
    private val self: Int,
    private val fanout: Boolean = false,
) : Routing {
    private val seen = SeenCache(port)

    override fun onLinkUp(peer: Int) {}

    override fun onLinkDown(peer: Int) {}

    override fun originate(packet: DataPacket) {
        seen.add(packet)
        relay(packet, -1, origin = true)
    }

    override fun onPacket(
        from: Int,
        packet: Packet,
    ) {
        val p = packet as? DataPacket ?: return
        if (!seen.add(p)) {
            port.duplicate(p)
            return
        }
        if (port.expired(p)) {
            port.drop(p, DropReason.EXPIRED)
            return
        }
        if (p.isBroadcast) {
            port.deliver(p)
        } else if (port.acceptsTag(p.dstTag)) {
            port.deliver(p)
            return
        }
        if (p.hops >= p.hopLimit) {
            port.drop(p, DropReason.HOP_LIMIT)
            return
        }
        relay(p.forwarded(), from, origin = false)
    }

    private fun relay(
        p: DataPacket,
        from: Int,
        origin: Boolean,
    ) {
        val all = port.linkedPeers()
        var peers = if (from < 0) all else all.filter { it != from }.toIntArray()
        if (fanout && !origin) {
            val k = max(port.params.fanoutMin, ceil(ln(all.size.toDouble()) / ln(2.0)).toInt())
            peers = seededSubset(peers, k, p.id.toLong() * 1_000_003L + self)
        }
        for (peer in peers) port.send(peer, p)
    }

    override fun seenSize() = seen.size
}
