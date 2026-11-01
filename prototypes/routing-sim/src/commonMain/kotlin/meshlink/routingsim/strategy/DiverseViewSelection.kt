package meshlink.routingsim.strategy

/**
 * N1+N2 (+N3): HyParView-style active/passive views with neighbor-overlap diversity.
 *
 * - Active view: the live links (at most d, last slot inbound-only per N3). Links are kept
 *   until they fail; on failure the best passive candidate is promoted at once.
 * - Passive view: up to 4·d recently heard advertisers and shuffle-learned peers, random
 *   replacement when full (HyParView). Only peers heard recently can be dialed.
 * - Shuffle: every ~10 s, exchange a few active and passive identifiers with one neighbor.
 * - Diversity (N2): neighbors exchange their neighbor lists. A candidate is scored by how many
 *   peers it would add that are already reachable within two hops; lower is better. Every
 *   30 s, the most redundant link is replaced if a clearly more diverse candidate is heard.
 * - Bridge preference ([bridge], stage B variant "N1+N2+bridge"): a newly discovered peer (not
 *   heard in the last 60 s) that is outside the known 1–2-hop neighborhood probably belongs to
 *   another group. It is dialed first, with the RSSI floor relaxed, even into the reserved slot
 *   (or after evicting the most redundant link); once linked, the most redundant other link is
 *   rotated out to restore the newcomer reservation. At most one bridge attempt per 10 s.
 */
class DiverseViewSelection(
    ctx: NodeContext,
    params: SelectionParams,
    private val bridge: Boolean = false,
) : NewcomerReservingSelection(ctx, params) {
    private val firstHeard = HashMap<Int, Long>()
    private val lastHeardAny = HashMap<Int, Long>()
    private var lastBridgeAt = Long.MIN_VALUE / 2
    private var bridgePeer = -1
    private val passive = LinkedHashMap<Int, HeardPeer>()
    private val lists = HashMap<Int, IntArray>()
    private val formerLists = HashMap<Int, IntArray>()
    private val formerListAt = HashMap<Int, Long>()
    private var listDirty = false
    private var lastListSent = Long.MIN_VALUE / 2
    private var nextShuffleAt = -1L
    private var nextDiversityAt = -1L
    private var fullSince = -1L
    private var replacementDial = -1
    private var replacing = -1
    private val capacity get() = params.peerTableFactor * budget

    override fun onScan(results: List<ScanResult>) {
        val t = now()
        for (r in results) {
            if (bridge) {
                val prev = lastHeardAny[r.peer]
                if (prev == null || t - prev > params.bridgeNoveltyGapMs) firstHeard[r.peer] = t
                lastHeardAny[r.peer] = t
            }
            insertPassive(r.peer, r.rssi, t)
        }
        if (bridge && lastHeardAny.size > 8 * capacity) {
            val stale = lastHeardAny.filterValues { t - it > 2 * params.bridgeNoveltyGapMs }.keys
            for (k in stale) {
                lastHeardAny.remove(k)
                firstHeard.remove(k)
            }
        }
    }

    /** Dials a newly discovered peer outside the 1–2-hop neighborhood (bridge preference). */
    private fun tryBridge(t: Long) {
        val gap = ctx.links.bridgeGapMs() ?: params.bridgeMinGapMs
        if (gap < 0 || bridgePeer >= 0 || t - lastBridgeAt < gap) return
        val reach = reachable(-1)
        var best = -1
        var bestRssi = -Double.MAX_VALUE
        for ((p, h) in passive) {
            if (t - h.lastHeard > params.freshMs || h.rssi < params.bridgeRssiFloorDbm || !isDialable(p)) continue
            if (p in reach || t - (firstHeard[p] ?: Long.MIN_VALUE / 2) > params.bridgeNewMs) continue
            if (h.rssi > bestRssi) {
                best = p
                bestRssi = h.rssi
            }
        }
        if (best < 0) return
        if (links.usedSlots() >= budget) {
            val worst = worstLink(-1)
            if (worst < 0 || redundancy(worst) == 0) return
            ctx.port.disconnect(worst)
            coolDown(worst)
        }
        lastBridgeAt = t
        if (ctx.port.dial(best)) bridgePeer = best
    }

    private fun insertPassive(
        peer: Int,
        rssi: Double,
        heardAt: Long,
    ) {
        if (peer == ctx.self || links.isLinkedOrPending(peer)) return
        val e = passive[peer]
        if (e != null) {
            if (heardAt > e.lastHeard) {
                e.lastHeard = heardAt
                e.rssi = rssi
            }
            return
        }
        if (passive.size >= capacity) {
            val victim = nth(passive.keys, ctx.random.nextInt(passive.size))
            passive.remove(victim)
        }
        passive[peer] = HeardPeer(rssi, heardAt)
    }

    override fun onTick() {
        val t = now()
        pruneBlocked()
        if (nextShuffleAt < 0) {
            nextShuffleAt = t + (ctx.random.nextDouble() * params.shuffleIntervalMs).toLong()
            nextDiversityAt = t + (ctx.random.nextDouble() * params.diversityCheckMs).toLong()
        }
        if (listDirty && t - lastListSent >= params.listMinIntervalMs) sendListToAll(t)
        if (t >= nextShuffleAt) {
            shuffle()
            nextShuffleAt = t + (params.shuffleIntervalMs * (0.5 + ctx.random.nextDouble())).toLong()
        }
        rotateWhenFull(t)
        if (bridge) tryBridge(t)
        if (t >= nextDiversityAt) {
            nextDiversityAt = t + params.diversityCheckMs
            replaceRedundant()
        }
        fill()
    }

    private fun rotateWhenFull(t: Long) {
        if (links.linkCount() < budget) {
            fullSince = -1
            return
        }
        if (fullSince < 0) {
            fullSince = t
            return
        }
        if (t - fullSince < params.fullRotationMs) return
        val worst = worstLink(-1)
        if (worst >= 0) {
            ctx.port.disconnect(worst)
            coolDown(worst)
        }
        fullSince = -1
    }

    private fun replaceRedundant() {
        if (replacementDial >= 0 || links.usedSlots() != budget - 1) return
        val worst = worstLink(-1)
        if (worst < 0) return
        val reach = reachable(-1)
        val candidate = bestCandidate(reach)
        if (candidate < 0) return
        if (score(candidate, reach) + params.diversityMargin <= redundancy(worst)) {
            if (ctx.port.dial(candidate)) {
                replacementDial = candidate
                replacing = worst
            }
        }
    }

    private fun fill() {
        var dials = 0
        while (dials < 2 && mayDialForSelf()) {
            val c = bestCandidate(reachable(-1))
            if (c < 0 || !ctx.port.dial(c)) return
            dials++
        }
    }

    /** Self, neighbors and their neighbors, optionally ignoring what [without] contributes. */
    private fun reachable(without: Int): HashSet<Int> {
        val reach = HashSet<Int>()
        reach.add(ctx.self)
        for (a in links.linkedPeers()) {
            if (a == without) continue
            reach.add(a)
            lists[a]?.let { for (p in it) reach.add(p) }
        }
        return reach
    }

    private fun score(
        candidate: Int,
        reach: HashSet<Int>,
    ): Int {
        val former = formerLists[candidate]
        if (former != null && now() - (formerListAt[candidate] ?: 0) <= params.formerListTtlMs) {
            return former.count { it != ctx.self && it in reach }
        }
        var common = 0
        for (a in links.linkedPeers()) if (lists[a]?.contains(candidate) == true) common++
        return common
    }

    private fun redundancy(peer: Int): Int {
        val list = lists[peer] ?: return 0
        val reach = reachable(peer)
        return list.count { it != ctx.self && it in reach }
    }

    private fun worstLink(exclude: Int): Int {
        var worst = -1
        var worstRed = -1
        var worstRssi = Double.MAX_VALUE
        for (p in links.linkedPeers()) {
            if (p == exclude) continue
            val red = redundancy(p)
            val rssi = links.linkRssi(p)
            if (red > worstRed || (red == worstRed && rssi < worstRssi)) {
                worst = p
                worstRed = red
                worstRssi = rssi
            }
        }
        return worst
    }

    private fun bestCandidate(reach: HashSet<Int>): Int {
        val t = now()
        var best = -1
        var bestScore = Int.MAX_VALUE
        var bestBucket = Int.MIN_VALUE
        var ties = 0
        for ((p, h) in passive) {
            if (t - h.lastHeard > params.freshMs || h.rssi < params.rssiFloorDbm || !isDialable(p)) continue
            val s = score(p, reach)
            val bucket = (h.rssi / 10.0).toInt()
            if (s < bestScore || (s == bestScore && bucket > bestBucket)) {
                best = p
                bestScore = s
                bestBucket = bucket
                ties = 1
            } else if (s == bestScore && bucket == bestBucket) {
                ties++
                if (ctx.random.nextInt(ties) == 0) best = p
            }
        }
        return best
    }

    private fun sendListToAll(t: Long) {
        val peers = links.linkedPeers()
        for (p in peers) ctx.port.send(p, NeighborListMessage(peers))
        lastListSent = t
        listDirty = false
    }

    private fun shuffle() {
        val active = links.linkedPeers()
        if (active.isEmpty()) return
        val target = active[ctx.random.nextInt(active.size)]
        ctx.port.send(target, ShuffleMessage(sample(target), isReply = false))
    }

    private fun sample(exclude: Int): IntArray {
        val out = ArrayList<Int>(params.shuffleActive + params.shufflePassive)
        val active = links.linkedPeers().filter { it != exclude }.toMutableList()
        repeat(minOf(params.shuffleActive, active.size)) {
            out.add(active.removeAt(ctx.random.nextInt(active.size)))
        }
        val pass = passive.keys.filter { it != exclude }.toMutableList()
        repeat(minOf(params.shufflePassive, pass.size)) {
            out.add(pass.removeAt(ctx.random.nextInt(pass.size)))
        }
        return out.toIntArray()
    }

    override fun onLinkUp(
        peer: Int,
        inbound: Boolean,
    ) {
        super.onLinkUp(peer, inbound)
        passive.remove(peer)
        ctx.port.send(peer, NeighborListMessage(links.linkedPeers()))
        listDirty = true
        if (peer == replacementDial) {
            if (replacing >= 0 && links.isLinkedOrPending(replacing)) ctx.port.disconnect(replacing)
            replacementDial = -1
            replacing = -1
        }
        if (peer == bridgePeer) {
            bridgePeer = -1
            if (links.usedSlots() > budget - 1) {
                val worst = worstLink(peer)
                if (worst >= 0) {
                    ctx.port.disconnect(worst)
                    coolDown(worst)
                }
            }
        }
    }

    override fun onLinkDown(
        peer: Int,
        reason: LinkDownReason,
    ) {
        super.onLinkDown(peer, reason)
        lists.remove(peer)?.let {
            formerLists[peer] = it
            formerListAt[peer] = now()
        }
        if (formerLists.size > 4 * capacity) {
            val t = now()
            val expired = formerListAt.filterValues { t - it > params.formerListTtlMs }.keys
            for (k in expired) {
                formerLists.remove(k)
                formerListAt.remove(k)
            }
        }
        if (peer == replacing) replacing = -1
        listDirty = true
        fill()
    }

    override fun onDialFailed(peer: Int) {
        super.onDialFailed(peer)
        if (peer == replacementDial) {
            replacementDial = -1
            replacing = -1
        }
        if (peer == bridgePeer) bridgePeer = -1
        fill()
    }

    override fun onMessage(
        from: Int,
        message: ControlMessage,
    ) {
        when (message) {
            is NeighborListMessage -> if (links.isLinkedOrPending(from)) lists[from] = message.neighbors
            is ShuffleMessage -> {
                if (!message.isReply) ctx.port.send(from, ShuffleMessage(sample(from), isReply = true))
                for (p in message.peers) insertPassive(p, -Double.MAX_VALUE, Long.MIN_VALUE / 2)
            }
        }
    }

    override fun peerTable(): IntArray = passive.keys.toIntArray()
}

internal fun nth(
    keys: Collection<Int>,
    index: Int,
): Int {
    var i = 0
    for (k in keys) {
        if (i == index) return k
        i++
    }
    throw IndexOutOfBoundsException("index $index")
}
