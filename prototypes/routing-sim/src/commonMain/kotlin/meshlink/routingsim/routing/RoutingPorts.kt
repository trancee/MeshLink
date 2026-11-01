package meshlink.routingsim.routing

import meshlink.routingsim.strategy.Randomness

/** Wire sizes for routing packets, in bytes. Defaults: routing tag 8 B, message ID 16 B. */
class RoutingSizes(
    val tagBytes: Int = 8,
    val messageIdBytes: Int = 16,
    val headerBytes: Int = 4,
    val expiryBytes: Int = 4,
    val hopFieldBytes: Int = 2,
    val seqBytes: Int = 2,
    val metricBytes: Int = 2,
)

/** Protocol tunables. Times in milliseconds (node-local clock). */
data class RoutingParams(
    /** Relays never forward beyond this many hops. */
    val hopLimit: Int = 255,
    /** A message enters a hold (waiting for a route) only with at least this much lifetime left. */
    val minHoldMs: Long = 10_000,
    /** Duplicate-suppression memory cap; entries leave when their message expires (lifetime-bounded). */
    val seenWindowMs: Long = Long.MAX_VALUE / 4,
    /** Per-node cap on messages held while waiting for a route. */
    val holdCapacity: Int = 100,
    // U2 scoped topology announcements.
    val announceMinMs: Long = 15_000,
    val announceMaxMs: Long = 30_000,
    val announceScopeHops: Int = 3,
    val announceMaxNeighbors: Int = 10,
    val topologyFreshMs: Long = 60_000,
    // U3 distance vector.
    val helloMs: Long = 4_000,
    val updateMs: Long = 16_000,
    val routeExpiryMs: Long = 56_000,
    val triggeredMinGapMs: Long = 1_000,
    // U4 on-demand (RFC 3561 expanding ring; the RFC's TTL is a search radius in hops here).
    val ringStart: Int = 1,
    val ringIncrement: Int = 2,
    val ringThreshold: Int = 7,
    val netDiameter: Int = 64,
    val nodeTraversalMs: Long = 50,
    val rreqRetries: Int = 2,
    val activeRouteMs: Long = 30_000,
    val reverseRouteMs: Long = 6_000,
    /** U3/U4: a message that found no route waits this long before re-trying (if held). */
    val holdRetryMs: Long = 10_000,
    // Stage C: tuned store-and-forward (U4sf, U6).
    /** Tuned holding: exponential retry backoff, retry on new links, flush when the destination's own request passes. */
    val sfTuned: Boolean = false,
    val sfRetryMinMs: Long = 2_000,
    val sfRetryMaxMs: Long = 30_000,
    /** A new link re-tries discovery for each held destination at most this often. */
    val sfLinkUpMinGapMs: Long = 5_000,
    // Stage C: U6 hybrid. Without a route, the message itself is flooded this many hops (0 = off).
    val dataRingHops: Int = 0,
    /** Data ring radius that U6 instances use (copied into [dataRingHops]). */
    val u6RingHops: Int = 3,
    /** Per-hop allowance for the data ring timeout (data packets are larger than requests). */
    val dataNodeTraversalMs: Long = 100,
    // Stage C: relay preference. Nodes that sleep (background iOS) delay relaying so awake paths win.
    val relayPreference: Boolean = false,
    val sleepyRelayDelayMs: Long = 150,
    /** Stage C adaptive unicast (UA): behavior per local density class, or null for a fixed protocol. */
    val classPolicy: UnicastClassPolicy? = null,
    // Stage D: keep routes alive during conversations, sparse senders flood.
    /** K1: a route used by a message stays valid this long after the last message (0 = active route time only). */
    val convKeepMs: Long = 0,
    /** K2: the destination acknowledges every routed message along the path; the ack re-arms the routes. */
    val deliveryAck: Boolean = false,
    /** K2: a sender without an ack after this long (plus per-hop time) re-discovers and re-sends once. */
    val ackWaitMs: Long = 1_000,
    /** K3: every phone a routed message or ring copy passes keeps the way back to its source this long (0 = off). */
    val reverseCacheMs: Long = 0,
    /** S: a phone whose own density estimate is below this floods its own messages (0 = off). */
    val sparseFloodBelow: Double = 0.0,
    /** F: with no route, send a full-range route request at once (alongside the data ring) instead of an expanding ring. */
    val fullRangeDiscovery: Boolean = false,
    /** Pinned low class: the adaptive broadcast floods in every class. */
    val pinFlood: Boolean = false,
    /** Plumtree catch-up: on a new link, announce (IHAVE) cached messages younger than this (0 = off). */
    val catchUpMs: Long = 0,
    // B2 fanout subsetting: forward to ceil(log2(links)) links, at least this many.
    val fanoutMin: Int = 2,
    // B3 Plumtree.
    val graftTimeoutMs: Long = 500,
    val graftRetryMs: Long = 250,
    val cacheWindowMs: Long = 60_000,
    // Gossipsub (scaled to the small BLE degree budget).
    val gsD: Int = 3,
    val gsDlo: Int = 2,
    val gsDhi: Int = 4,
    val gsDlazy: Int = 3,
    val gsHeartbeatMs: Long = 1_000,
    val gsHistory: Int = 5,
    val gsGossipWindows: Int = 3,
    val gsPruneBackoffMs: Long = 10_000,
    val gsIdontwantThresholdBytes: Int = 1_024,
    val gsFloodPublish: Boolean = true,
)

/** Anything sent over a link by a routing protocol. */
abstract class Packet {
    abstract val isControl: Boolean

    /** Control traffic a protocol sends even when no application messages flow (idle control). */
    open val isIdleControl: Boolean get() = false

    abstract fun sizeBytes(s: RoutingSizes): Int
}

/** Destination tag of a mesh broadcast. */
const val BROADCAST: Long = 0L

/**
 * An application message (unicast or mesh broadcast). [id] stands for the 16 B message ID.
 * [expiresAt] is in the originator's clock: message lifetime rules compare it with the local clock.
 */
class DataPacket(
    val id: Int,
    val srcTag: Long,
    val dstTag: Long,
    val expiresAt: Long,
    val payloadBytes: Int,
    val hops: Int,
    val hopLimit: Int,
    /** U2 source route (tags of the remaining path, destination last), or null when flooded. */
    val route: LongArray? = null,
    val routeIndex: Int = 0,
    /** U6 data ring: hops this flooded copy may still travel (−1 = not a ring copy). */
    val scope: Int = -1,
) : Packet() {
    override val isControl get() = false

    val isBroadcast get() = dstTag == BROADCAST

    fun forwarded(
        route: LongArray? = this.route,
        routeIndex: Int = this.routeIndex,
        scope: Int = this.scope,
    ) = DataPacket(id, srcTag, dstTag, expiresAt, payloadBytes, hops + 1, hopLimit, route, routeIndex, scope)

    override fun sizeBytes(s: RoutingSizes): Int =
        s.headerBytes + s.messageIdBytes + s.tagBytes + (if (isBroadcast) 0 else s.tagBytes) + s.expiryBytes +
            s.hopFieldBytes + (route?.size ?: 0) * s.tagBytes + payloadBytes
}

/** Why a protocol gave up on a message (for loss accounting). */
enum class DropReason { EXPIRED, HOP_LIMIT, NO_ROUTE, HOLD_REFUSED, HOLD_FULL, DISCOVERY_FAILED }

/** What a routing protocol instance at one node may use. */
interface RoutingPort {
    val params: RoutingParams
    val random: Randomness

    /** Node-local clock (may be skewed against other nodes). */
    fun now(): Long

    fun linkedPeers(): IntArray

    fun isLinked(peer: Int): Boolean

    /** Queues [packet] on the link to [peer]; false if there is no such link. */
    fun send(
        peer: Int,
        packet: Packet,
    ): Boolean

    /** Runs [action] after [delayMs] unless this protocol instance has been discarded. */
    fun after(
        delayMs: Long,
        action: () -> Unit,
    )

    /** Hands a message addressed to this node (or a broadcast) to the application. */
    fun deliver(packet: DataPacket)

    /** A data packet arrived that this node had already seen. */
    fun duplicate(packet: DataPacket)

    /** The routing tag this node announces now. */
    fun ownTag(): Long

    /** The current routing tag of a linked peer (learned at link set-up and on rotation). */
    fun peerTag(peer: Int): Long

    /** Whether [tag] addresses this node (current, previous or next tag epoch). */
    fun acceptsTag(tag: Long): Boolean

    fun drop(
        packet: DataPacket,
        reason: DropReason,
    )

    /** Whether this node stays awake (Android or foreground iOS) rather than waking in short windows. */
    fun selfStable(): Boolean = true

    /** The stable flag a linked peer announced at link set-up (1 bit). */
    fun peerStable(peer: Int): Boolean = true

    /** This phone's own local density class (0 low, 1 medium, 2 high), from its own observations only. */
    fun densityClass(): Int = 1

    /** This phone's own smoothed count of peers heard (driving estimator), −1 before its first scan. */
    fun densityEstimate(): Double = -1.0
}

/**
 * Stage C adaptive unicast: what a phone does with its own messages and relays in each local
 * density class (index 0 low, 1 medium, 2 high). The wire format is the same in every class.
 */
class UnicastClassPolicy(
    val label: String,
    /** Data ring radius when no route is known: [FLOOD_ALL] floods every message (U1-like), 0 = route requests only (U4). */
    val ringHops: IntArray,
    /** Re-try held destinations when a new link comes up. */
    val linkUpRetry: BooleanArray,
    val retryMinMs: LongArray,
    val retryMaxMs: LongArray,
) {
    companion object {
        const val FLOOD_ALL = 1_000
    }
}

fun RoutingPort.expired(p: DataPacket) = now() > p.expiresAt

/** Message lifetime rule: hold only unexpired messages with at least the minimum hold time left. */
fun RoutingPort.mayHold(p: DataPacket) = p.expiresAt - now() >= params.minHoldMs

/** One routing or mesh-broadcast protocol instance at one node. */
interface Routing {
    fun onLinkUp(peer: Int)

    fun onLinkDown(peer: Int)

    fun onPacket(
        from: Int,
        packet: Packet,
    )

    /** The application sends [packet] (hops = 0). */
    fun originate(packet: DataPacket)

    /** A packet queued for [peer] never left because the link went down first. */
    fun onSendFailed(
        peer: Int,
        packet: Packet,
    ) {}

    /** This node's routing tag just rotated. */
    fun onTagRotated() {}

    /** State sizes for memory metrics. */
    fun tableSize(): Int = 0

    fun seenSize(): Int = 0

    fun heldCount(): Int = 0
}

/** Generic control packet with a fixed size. */
class Control(
    val kind: String,
    private val bytes: Int,
    override val isIdleControl: Boolean,
) : Packet() {
    override val isControl get() = true

    override fun sizeBytes(s: RoutingSizes) = bytes
}

/**
 * Messages waiting for a route. A message enters only while unexpired and with at least the
 * minimum hold time left; expired messages are discarded on every sweep (message lifetime rules).
 */
class HoldQueue(
    private val port: RoutingPort,
) {
    private val byDst = LinkedHashMap<Long, ArrayList<DataPacket>>()
    var size = 0
        private set

    /** Offers [p]; returns false (and reports the drop) when the lifetime rules or capacity refuse it. */
    fun offer(
        p: DataPacket,
        gated: Boolean = true,
    ): Boolean {
        if (port.expired(p)) {
            port.drop(p, DropReason.EXPIRED)
            return false
        }
        if (gated && !port.mayHold(p)) {
            port.drop(p, DropReason.HOLD_REFUSED)
            return false
        }
        if (size >= port.params.holdCapacity) {
            port.drop(p, DropReason.HOLD_FULL)
            return false
        }
        byDst.getOrPut(p.dstTag) { ArrayList(2) }.add(p)
        size++
        return true
    }

    fun has(dst: Long) = byDst.containsKey(dst)

    fun peek(dst: Long): DataPacket? = byDst[dst]?.firstOrNull()

    /** Removes the message [id] for [dst] (acknowledged by the destination). */
    fun remove(
        dst: Long,
        id: Int,
    ) {
        val list = byDst[dst] ?: return
        if (list.removeAll { it.id == id }) size = byDst.values.sumOf { it.size }
        if (list.isEmpty()) byDst.remove(dst)
    }

    fun destinations(): List<Long> = byDst.keys.toList()

    /** Removes and returns the unexpired messages for [dst]; expired ones are dropped. */
    fun take(dst: Long): List<DataPacket> {
        val list = byDst.remove(dst) ?: return emptyList()
        size -= list.size
        val out = ArrayList<DataPacket>(list.size)
        for (p in list) if (port.expired(p)) port.drop(p, DropReason.EXPIRED) else out.add(p)
        return out
    }

    /** Drops expired messages everywhere. */
    fun sweep() {
        val it = byDst.values.iterator()
        while (it.hasNext()) {
            val list = it.next()
            val before = list.size
            list.removeAll { p -> port.expired(p).also { e -> if (e) port.drop(p, DropReason.EXPIRED) } }
            size -= before - list.size
            if (list.isEmpty()) it.remove()
        }
    }
}

/** Duplicate suppression keyed by message ID, forgetting entries at expiry or after the window. */
class SeenCache(
    private val port: RoutingPort,
) {
    private val until = HashMap<Int, Long>()
    private var nextPrune = 0L

    /** Records [p]; returns false if it was already seen. */
    fun add(p: DataPacket): Boolean {
        val t = port.now()
        if (t >= nextPrune) {
            until.entries.removeAll { it.value < t }
            nextPrune = t + 5_000
        }
        if (until.containsKey(p.id)) return false
        until[p.id] = minOf(p.expiresAt, t + port.params.seenWindowMs)
        return true
    }

    fun contains(id: Int) = until.containsKey(id)

    val size get() = until.size
}

/** Open-addressing map from 64-bit tags to non-negative ints (route-table index). */
class LongIntMap(
    initial: Int = 16,
) {
    private var keys = LongArray(cap(initial))
    private var values = IntArray(keys.size)
    private var used = BooleanArray(keys.size)
    var size = 0
        private set

    private fun cap(n: Int): Int {
        var c = 16
        while (c < n * 2) c = c shl 1
        return c
    }

    private fun slot(k: Long): Int {
        var h = k * -0x61c8864680b583ebL
        h = h xor (h ushr 29)
        return (h.toInt()) and (keys.size - 1)
    }

    operator fun get(k: Long): Int {
        var i = slot(k)
        while (used[i]) {
            if (keys[i] == k) return values[i]
            i = (i + 1) and (keys.size - 1)
        }
        return -1
    }

    operator fun set(
        k: Long,
        v: Int,
    ) {
        if ((size + 1) * 2 > keys.size) grow()
        var i = slot(k)
        while (used[i]) {
            if (keys[i] == k) {
                values[i] = v
                return
            }
            i = (i + 1) and (keys.size - 1)
        }
        used[i] = true
        keys[i] = k
        values[i] = v
        size++
    }

    fun remove(k: Long) {
        var i = slot(k)
        while (used[i]) {
            if (keys[i] == k) {
                used[i] = false
                size--
                // Re-insert the rest of the cluster.
                var j = (i + 1) and (keys.size - 1)
                while (used[j]) {
                    val rk = keys[j]
                    val rv = values[j]
                    used[j] = false
                    size--
                    set(rk, rv)
                    j = (j + 1) and (keys.size - 1)
                }
                return
            }
            i = (i + 1) and (keys.size - 1)
        }
    }

    private fun grow() {
        val ok = keys
        val ov = values
        val ou = used
        keys = LongArray(ok.size * 2)
        values = IntArray(keys.size)
        used = BooleanArray(keys.size)
        size = 0
        for (i in ok.indices) if (ou[i]) set(ok[i], ov[i])
    }
}

/** Picks [k] distinct elements of [from] pseudo-randomly from [seed] (message-ID-seeded subsets). */
fun seededSubset(
    from: IntArray,
    k: Int,
    seed: Long,
): IntArray {
    if (from.size <= k) return from
    val a = from.copyOf()
    var x = seed
    for (i in 0 until k) {
        x = x * 6364136223846793005L + 1442695040888963407L
        val j = i + ((x ushr 33).toInt() and Int.MAX_VALUE) % (a.size - i)
        val t = a[i]
        a[i] = a[j]
        a[j] = t
    }
    return a.copyOf(k)
}

/**
 * Messages without a route: a short ungated route wait (convergence or discovery), then
 * store-and-forward holding, which the lifetime rules gate (minimum hold time).
 */
class PendingRoutes(
    private val port: RoutingPort,
) {
    val waiting = HoldQueue(port)
    val held = HoldQueue(port)

    /** Puts [p] in the route wait; returns true if it is the first message waiting for its destination. */
    fun wait(p: DataPacket): Boolean {
        val first = !waiting.has(p.dstTag)
        return waiting.offer(p, gated = false) && first
    }

    /** The route wait for [dst] ended without a route: move its messages into the gated hold. */
    fun promote(dst: Long) {
        for (p in waiting.take(dst)) held.offer(p, gated = true)
    }

    fun take(dst: Long): List<DataPacket> = waiting.take(dst) + held.take(dst)

    fun has(dst: Long) = waiting.has(dst) || held.has(dst)

    fun remove(
        dst: Long,
        id: Int,
    ) {
        waiting.remove(dst, id)
        held.remove(dst, id)
    }

    fun sweep() {
        waiting.sweep()
        held.sweep()
    }

    val size get() = waiting.size + held.size
}
