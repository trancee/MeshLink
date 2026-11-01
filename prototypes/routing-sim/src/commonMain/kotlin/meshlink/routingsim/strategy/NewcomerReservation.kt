package meshlink.routingsim.strategy

/** Tunables shared by the neighbor-selection strategies. Times in milliseconds. */
class SelectionParams(
    /** N0: share of the degree budget held as stable (strongest-signal) slots. */
    val stableFraction: Double = 0.75,
    /** N0: a rotating link idle this long is closed to make room for another peer. */
    val rotationIdleMs: Long = 30_000,
    /** Do not redial a peer we rotated out, or that closed on us, for this long. */
    val cooldownMs: Long = 120_000,
    /** Peer table (N0) or passive view (N1) capacity as a multiple of the degree budget. */
    val peerTableFactor: Int = 4,
    /** Only dial peers whose advertisement was heard this recently. */
    val freshMs: Long = 4_000,
    /** Drop peer-table entries not heard for this long (N0). */
    val staleMs: Long = 30_000,
    /** Backoff after the first and after repeated dial failures to the same peer. */
    val firstBackoffMs: Long = 3_000,
    val repeatBackoffMs: Long = 60_000,
    /** N1: shuffle period and sample sizes. */
    val shuffleIntervalMs: Long = 10_000,
    val shuffleActive: Int = 2,
    val shufflePassive: Int = 3,
    /** N2: minimum gap between neighbor-list updates to all neighbors. */
    val listMinIntervalMs: Long = 2_000,
    /** N2: how often to look for a more diverse replacement link. */
    val diversityCheckMs: Long = 30_000,
    /** N2: candidate must beat the most redundant link by this many overlapping peers. */
    val diversityMargin: Int = 2,
    /** N1+N3: a node that is full for this long frees one slot. */
    val fullRotationMs: Long = 30_000,
    /** N1: ignore candidates weaker than this (edge links drop quickly). */
    val rssiFloorDbm: Double = -85.0,
    /** N2: how long a former neighbor's list stays usable for scoring. */
    val formerListTtlMs: Long = 120_000,
)

/**
 * N3 newcomer reservation shared by every honest strategy: advertise only with a free slot,
 * and never fill the last slot with an own dial, so it stays free for inbound newcomers.
 */
abstract class NewcomerReservingSelection(
    protected val ctx: NodeContext,
    protected val params: SelectionParams,
) : NeighborSelection {
    private val failures = HashMap<Int, Int>()
    private val blockedUntil = HashMap<Int, Long>()

    protected val links: LinkTable get() = ctx.links
    protected val budget: Int get() = ctx.links.degreeBudget

    protected fun now(): Long = ctx.clock.nowMillis()

    override fun advertising(): Boolean = links.usedSlots() < budget

    protected fun mayDialForSelf(): Boolean = links.usedSlots() + 1 <= budget - 1

    protected fun isDialable(peer: Int): Boolean {
        if (peer == ctx.self || links.isLinkedOrPending(peer)) return false
        val until = blockedUntil[peer] ?: return true
        return now() >= until
    }

    protected fun coolDown(peer: Int) {
        blockedUntil[peer] = now() + params.cooldownMs
    }

    override fun onDialFailed(peer: Int) {
        val count = (failures[peer] ?: 0) + 1
        failures[peer] = count
        blockedUntil[peer] = now() + if (count == 1) params.firstBackoffMs else params.repeatBackoffMs
    }

    override fun onLinkUp(
        peer: Int,
        inbound: Boolean,
    ) {
        failures.remove(peer)
    }

    override fun onLinkDown(
        peer: Int,
        reason: LinkDownReason,
    ) {
        if (reason == LinkDownReason.REMOTE_CLOSE) coolDown(peer)
    }

    protected fun pruneBlocked() {
        if (blockedUntil.size < 64) return
        val t = now()
        blockedUntil.entries.removeAll { it.value <= t }
    }
}

class HeardPeer(
    var rssi: Double,
    var lastHeard: Long,
)
