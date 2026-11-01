package meshlink.routingsim.strategy

/**
 * A fake identity: always advertises, accepts everything, dials every honest advertiser it hears,
 * never closes a link, claims an empty neighbor list (so it looks maximally diverse to N2) and
 * answers shuffles only with other fake identities. It never forwards; the simulator excludes
 * its links from honest connectivity.
 */
class SybilSelection(
    private val ctx: NodeContext,
    private val fakeIdentities: IntArray,
    private val dialsPerScan: Int = 2,
) : NeighborSelection {
    private val fake = fakeIdentities.toHashSet()

    override fun advertising(): Boolean = true

    override fun onScan(results: List<ScanResult>) {
        var dials = 0
        for (r in results.sortedByDescending { it.rssi }) {
            if (dials >= dialsPerScan) return
            if (r.peer in fake || ctx.links.isLinkedOrPending(r.peer)) continue
            if (ctx.port.dial(r.peer)) dials++
        }
    }

    override fun onTick() = Unit

    override fun onLinkUp(
        peer: Int,
        inbound: Boolean,
    ) {
        ctx.port.send(peer, NeighborListMessage(IntArray(0)))
    }

    override fun onLinkDown(
        peer: Int,
        reason: LinkDownReason,
    ) = Unit

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
}
