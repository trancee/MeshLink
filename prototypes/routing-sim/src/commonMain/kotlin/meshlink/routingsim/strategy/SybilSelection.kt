package meshlink.routingsim.strategy

/**
 * A fake identity: always advertises, accepts everything, dials every honest advertiser it hears,
 * never closes a link, claims an empty neighbor list (so it looks maximally diverse to N2) and
 * answers shuffles only with other fake identities. It never forwards; the simulator excludes
 * its links from honest connectivity.
 *
 * Stage S: [smart] sends its true neighbor list (on link-up and on change, at most every 2 s, like an honest phone);
 * its forwarding is modeled in the routing layer. [redial]: identities are free, so a link that goes down is re-dialed
 * at once (under a fresh identity; the simulator makes the honest side forget the old one).
 */
class SybilSelection(
    private val ctx: NodeContext,
    private val fakeIdentities: IntArray,
    private val dialsPerScan: Int = 2,
    private val smart: Boolean = false,
    private val redial: Boolean = false,
) : NeighborSelection {
    private val fake = fakeIdentities.toHashSet()
    private var listDirty = false
    private var lastListSent = Long.MIN_VALUE / 2

    override fun advertising(): Boolean = true

    override fun onScan(results: List<ScanResult>) {
        var dials = 0
        for (r in results.sortedByDescending { it.rssi }) {
            if (dials >= dialsPerScan) return
            if (r.peer in fake || ctx.links.isLinkedOrPending(r.peer)) continue
            if (ctx.port.dial(r.peer)) dials++
        }
    }

    override fun onTick() {
        if (!smart || !listDirty) return
        val t = ctx.clock.nowMillis()
        if (t - lastListSent < LIST_MIN_INTERVAL_MS) return
        val peers = ctx.links.linkedPeers()
        for (p in peers) ctx.port.send(p, NeighborListMessage(peers))
        lastListSent = t
        listDirty = false
    }

    override fun onLinkUp(
        peer: Int,
        inbound: Boolean,
    ) {
        if (smart) {
            ctx.port.send(peer, NeighborListMessage(ctx.links.linkedPeers()))
            listDirty = true
        } else {
            ctx.port.send(peer, NeighborListMessage(IntArray(0)))
        }
    }

    override fun onLinkDown(
        peer: Int,
        reason: LinkDownReason,
    ) {
        if (smart) listDirty = true
        if (redial) ctx.port.dial(peer)
    }

    override fun onDialFailed(peer: Int) = Unit

    override fun onMessage(
        from: Int,
        message: ControlMessage,
    ) {
        if (message is ShuffleMessage && !message.isReply) {
            val reply = IntArray(minOf(5, fakeIdentities.size)) { fakeIdentities[ctx.random.nextInt(fakeIdentities.size)] }
            ctx.port.send(from, ShuffleMessage(reply, isReply = true))
        }
    }

    override fun peerTable(): IntArray = IntArray(0)

    private companion object {
        const val LIST_MIN_INTERVAL_MS = 2_000L
    }
}
