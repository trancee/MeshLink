package meshlink.routingsim.strategy

/** Time source in milliseconds; simulated in the simulator, wall-clock in a real node. */
interface Clock {
    fun nowMillis(): Long
}

/** Randomness source, injected so that a run is reproducible from its seed. */
interface Randomness {
    fun nextDouble(): Double

    fun nextInt(bound: Int): Int
}

/** Why a link went down, as the local node can observe it. */
enum class LinkDownReason {
    /** This node closed the link. */
    LOCAL_CLOSE,

    /** The peer closed the link cleanly (for example rotation eviction on its side). */
    REMOTE_CLOSE,

    /** Supervision timeout, out of range, or the peer went away. */
    LOST,
}

/** Link-layer actions a neighbor-selection strategy may request. */
interface LinkPort {
    /** Starts an outbound link attempt; false if the link layer refuses (no slot, already linked). */
    fun dial(peer: Int): Boolean

    fun disconnect(peer: Int)

    fun send(
        peer: Int,
        message: ControlMessage,
    )
}

/** Read-only view of the node's current links and degree budget. */
interface LinkTable {
    /** Maximum concurrent links, both roles, pending dials included. */
    val degreeBudget: Int

    fun linkCount(): Int

    fun pendingCount(): Int

    fun linkedPeers(): IntArray

    fun isLinkedOrPending(peer: Int): Boolean

    fun linkRssi(peer: Int): Double

    fun linkUpAt(peer: Int): Long

    fun lastActivityAt(peer: Int): Long

    /** Bridge rate limit chosen by this phone's local density class (null = fixed parameter, negative = bridge off). */
    fun bridgeGapMs(): Long? = null
}

fun LinkTable.usedSlots(): Int = linkCount() + pendingCount()

/** One advertiser heard by a scan. */
class ScanResult(
    val peer: Int,
    val rssi: Double,
)

/** Wire sizes used to count control bytes. */
class WireSizes(
    val routingTagBytes: Int = 8,
    /** Not used by stage A (no data messages); kept for later stages. */
    val messageIdBytes: Int = 16,
    val headerBytes: Int = 4,
)

sealed class ControlMessage {
    abstract fun sizeBytes(sizes: WireSizes): Int
}

/** The sender's current neighbors (N2 neighbor-list exchange). */
class NeighborListMessage(
    val neighbors: IntArray,
) : ControlMessage() {
    override fun sizeBytes(sizes: WireSizes): Int = sizes.headerBytes + sizes.routingTagBytes * neighbors.size
}

/** HyParView shuffle request or reply carrying peer identifiers. */
class ShuffleMessage(
    val peers: IntArray,
    val isReply: Boolean,
) : ControlMessage() {
    override fun sizeBytes(sizes: WireSizes): Int = sizes.headerBytes + sizes.routingTagBytes * peers.size
}

/** Everything a strategy instance needs from its node. */
class NodeContext(
    val self: Int,
    val clock: Clock,
    val random: Randomness,
    val links: LinkTable,
    val port: LinkPort,
)

/** Neighbor selection and topology formation for one node. */
interface NeighborSelection {
    /** Whether the node advertises as connectable right now. */
    fun advertising(): Boolean

    fun onScan(results: List<ScanResult>)

    fun onTick()

    fun onLinkUp(
        peer: Int,
        inbound: Boolean,
    )

    fun onLinkDown(
        peer: Int,
        reason: LinkDownReason,
    )

    fun onDialFailed(peer: Int)

    fun onMessage(
        from: Int,
        message: ControlMessage,
    )

    /** Peers currently held in the bounded peer table (for eviction metrics). */
    fun peerTable(): IntArray
}
