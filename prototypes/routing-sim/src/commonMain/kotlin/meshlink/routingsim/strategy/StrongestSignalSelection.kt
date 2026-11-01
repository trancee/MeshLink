package meshlink.routingsim.strategy

/**
 * N0 (+N3): strongest-signal stable slots plus rotating slots (cairn ADR 0031 style).
 *
 * Links are ranked by RSSI; the top ⌊¾·d⌋ are stable. Every other link is rotating and is
 * closed once idle for [SelectionParams.rotationIdleMs] if another candidate is available.
 * Free own slots are filled with the strongest freshly heard advertiser.
 * The peer table keeps the strongest entries.
 */
class StrongestSignalSelection(
    ctx: NodeContext,
    params: SelectionParams,
) : NewcomerReservingSelection(ctx, params) {
    private val table = LinkedHashMap<Int, HeardPeer>()
    private val capacity get() = params.peerTableFactor * budget

    override fun onScan(results: List<ScanResult>) {
        val t = now()
        var weakest = -1
        var weakestRssi = Double.NaN
        for (r in results) {
            val e = table[r.peer]
            if (e != null) {
                e.rssi = 0.5 * e.rssi + 0.5 * r.rssi
                e.lastHeard = t
                if (r.peer == weakest) weakest = -1
                continue
            }
            if (table.size < capacity) {
                table[r.peer] = HeardPeer(r.rssi, t)
                continue
            }
            if (weakest < 0) {
                weakestRssi = Double.MAX_VALUE
                for ((p, h) in table) {
                    if (h.rssi < weakestRssi) {
                        weakestRssi = h.rssi
                        weakest = p
                    }
                }
            }
            if (r.rssi > weakestRssi) {
                table.remove(weakest)
                table[r.peer] = HeardPeer(r.rssi, t)
                weakest = -1
            }
        }
    }

    override fun onTick() {
        val t = now()
        table.entries.removeAll { t - it.value.lastHeard > params.staleMs }
        pruneBlocked()
        rotate(t)
        if (mayDialForSelf()) {
            val best = strongestCandidate(t)
            if (best >= 0) ctx.port.dial(best)
        }
    }

    private fun rotate(t: Long) {
        val peers = links.linkedPeers()
        val stable = maxOf(1, (params.stableFraction * budget).toInt())
        if (peers.size <= stable) return
        val ranked = peers.sortedByDescending { links.linkRssi(it) }
        var oldest = -1
        var oldestIdle = params.rotationIdleMs - 1
        for (i in stable until ranked.size) {
            val idle = t - links.lastActivityAt(ranked[i])
            if (idle > oldestIdle) {
                oldestIdle = idle
                oldest = ranked[i]
            }
        }
        if (oldest >= 0 && strongestCandidate(t) >= 0) {
            ctx.port.disconnect(oldest)
            coolDown(oldest)
        }
    }

    private fun strongestCandidate(t: Long): Int {
        var best = -1
        var bestRssi = -Double.MAX_VALUE
        for ((p, h) in table) {
            if (t - h.lastHeard > params.freshMs || !isDialable(p)) continue
            if (h.rssi > bestRssi) {
                bestRssi = h.rssi
                best = p
            }
        }
        return best
    }

    override fun onMessage(
        from: Int,
        message: ControlMessage,
    ) = Unit

    override fun peerTable(): IntArray = table.keys.toIntArray()
}
