package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A node's active view (ADR 0008 decision 1; its passive view is [PassiveView]): the node's
 * links within the budget of [LINKS] bearers, kept or opening, in either role: the node dials
 * one candidate at a time, heard in the last [HEARD_MILLIS], while it holds fewer than [LINKS] − 1,
 * which keeps one slot free for newcomers, and accepts a bearer while it holds fewer than [LINKS],
 * or one more while its bridge or replacement dial is in flight ([accepts]). A node that has held
 * [LINKS] for [FULL_MILLIS] frees one (newcomer reservation N3). A bridge, a likely split-off part of
 * the mesh, is dialed first, and every [REPLACE_INTERVAL_MILLIS] a node at its last free slot may
 * replace its most redundant link with a less named candidate (bridge rule and diversity
 * replacement; see [dial]). A candidate
 * whose dial failed, whose peer closed its kept link or whose link the node freed is kept undialed
 * by [backoff] for a while. Draws every choice from [random]. Not thread-safe: [Links] calls it on
 * the node's serial dispatcher.
 */
internal class Views<T>(
    private val clock: Clock,
    private val random: RandomSource,
    private val neighbors: NeighborLists,
    private val linked: () -> Map<NodeId, T>,
    private val free: (T) -> Unit,
) {
    /** The candidates the node keeps undialed. */
    val backoff: Backoff = Backoff(clock)

    // The last bridge dial, the bridge to dial once an evicted link's slot is free, the bridge or
    // replacement dial in flight, and whether a diversify tick is running.
    private var bridgedAt: Long? = null
    private var pending: PeerHandle? = null
    private var reserved: PeerHandle? = null
    private var replacing = false

    // Frees a slot once the node has held its whole budget for FULL_MILLIS.
    private var full: Job? = null

    /**
     * Which of [dialable] to dial while the node holds [used] bearers, or `null`. A candidate is
     * ready if heard in the last [HEARD_MILLIS] and neither backed off nor cooled down. A bridge is a
     * ready candidate that is identifier-less, or whose identifier no peer's neighbor list names, the
     * node doesn't know, and that was first heard in the last [NEW_MILLIS]: likely a split-off part of
     * the mesh; it must also have been first heard at least [SETTLE_MILLIS] ago, so that a linked
     * peer's neighbor that rotated its identifier is named by the lists the rotation triggers first.
     * At most once per [BRIDGE_INTERVAL_MILLIS] the node dials a random bridge first, even
     * with only the newcomer slot free; holding [LINKS], it frees its most redundant link instead, if
     * one has redundancy, and dials the bridge once the slot is free. Otherwise, below [LINKS] − 1,
     * it dials a random identified candidate of those the fewest peers' lists name; at
     * [LINKS] − 1, only during a [diversify] tick, only one first heard at least [SETTLE_MILLIS] ago,
     * and only one at least [REPLACE_MARGIN] less named than its most redundant link. A bridge or
     * replacement dial reserves its handle for [kept]. One dial at a time keeps two nodes from
     * filling their budgets with dials and refusing each other's. Forgets the failures of handles no
     * longer among [candidates].
     */
    fun dial(
        dialable: List<PeerHandle>,
        candidates: Map<PeerHandle, Candidate>,
        used: Int,
    ): PeerHandle? {
        val now = clock.monotonicMillis()
        backoff.update(candidates.keys, now)
        val ready =
            dialable.filter { handle ->
                val candidate = candidates.getValue(handle)
                handle !in backoff && now - candidate.heard <= HEARD_MILLIS && candidate.rssi >= BRIDGE_FLOOR_DBM
            }
        val bridges = ready.filter { bridges(candidates.getValue(it), now) }
        val due = bridges.isNotEmpty() && bridgedAt.let { it == null || now - it >= BRIDGE_INTERVAL_MILLIS }
        if (used >= LINKS) {
            if (due) evict(now, bridges)
            return null
        }
        val waiting = ready.filter { it == pending }
        pending = null
        return when {
            waiting.isNotEmpty() -> {
                waiting.single().also { reserved = it }
            }

            due -> {
                bridgedAt = now
                bridges.pick().also { reserved = it }
            }

            else -> {
                ordinary(ready, candidates, used, now)
            }
        }
    }

    /** Whether [candidate] is a bridge at [now]. */
    private fun bridges(
        candidate: Candidate,
        now: Long,
    ): Boolean {
        val advertisement = candidate.advertisement ?: return true
        val age = now - candidate.since
        val tag = advertisement.resolvableTag
        return !candidate.known && age >= SETTLE_MILLIS && age <= NEW_MILLIS && !neighbors.nearby(tag)
    }

    /** Frees the most redundant link, if one has redundancy, for one of [bridges] to dial next. */
    private fun evict(
        now: Long,
        bridges: List<PeerHandle>,
    ) {
        val links = linked()
        val peer = mostRedundant(links.keys, least = 1) ?: return
        bridgedAt = now
        pending = bridges.pick()
        free(links.getValue(peer))
    }

    /** An identified one of [ready] the fewest peers' lists name, if the node may dial it holding [used]. */
    private fun ordinary(
        ready: List<PeerHandle>,
        candidates: Map<PeerHandle, Candidate>,
        used: Int,
        now: Long,
    ): PeerHandle? {
        val settled = { handle: PeerHandle -> now - candidates.getValue(handle).since >= SETTLE_MILLIS }
        val strong = ready.filter { candidates.getValue(it).rssi >= DIAL_FLOOR_DBM }
        val eligible = if (used < LINKS - 1) strong else strong.filter(settled)
        val overlaps =
            eligible
                .mapNotNull { handle ->
                    candidates.getValue(handle).advertisement?.let { handle to neighbors.overlap(it.resolvableTag) }
                }.toMap()
        val fewest = overlaps.filterValues { it == overlaps.values.min() }
        val pick = fewest.keys.toList().pick() ?: return null
        val most = linked().keys.map(neighbors::redundancy).fold(0, ::maxOf)
        return when {
            used < LINKS - 1 -> pick
            replacing && fewest.getValue(pick) + REPLACE_MARGIN <= most -> pick.also { reserved = it }
            else -> null
        }
    }

    /** A random one of [peers] with the highest redundancy, at least [least], or `null`. */
    private fun mostRedundant(
        peers: Collection<NodeId>,
        least: Int,
    ): NodeId? {
        val scores = peers.associateWith(neighbors::redundancy)
        return scores
            .filterValues { it >= least && it == scores.values.max() }
            .keys
            .toList()
            .pick()
    }

    /**
     * The dial to [handle] opened a kept link to [peer], and the node holds [used] bearers: if it
     * was a bridge or replacement dial and the node holds [LINKS], frees its most redundant other link.
     */
    fun kept(
        handle: PeerHandle,
        peer: NodeId,
        used: Int,
    ) {
        if (handle != reserved) return
        reserved = null
        if (used < LINKS) return
        val links = linked()
        mostRedundant(links.keys - peer, least = 0)?.let { free(links.getValue(it)) }
    }

    /** A dial to [handle] ended, its link [kept] or not: ends its reservation and updates its [backoff]. */
    fun dialed(
        handle: PeerHandle,
        kept: Boolean,
    ) {
        if (handle == reserved) reserved = null
        backoff.dialed(handle, kept)
    }

    /**
     * The node holds [used] bearers: once it has held [LINKS] for [FULL_MILLIS], in [scope], it
     * [free]s a random one of its [linked] links, if any, so a newcomer can join it (newcomer reservation).
     */
    fun held(
        used: Int,
        scope: CoroutineScope,
    ) {
        when {
            used < LINKS -> {
                full?.cancel()
                full = null
            }

            full == null -> {
                full =
                    scope.launch {
                        delay(FULL_MILLIS)
                        full = null
                        val held = linked().values.toList()
                        if (held.isNotEmpty()) free(held[random.nextInt(0, held.size)])
                    }
            }
        }
    }

    /**
     * Every [REPLACE_INTERVAL_MILLIS], [retry]s a dial that may replace a redundant link (diversity
     * replacement). Runs until cancelled.
     */
    suspend fun diversify(retry: () -> Unit): Nothing {
        while (true) {
            delay(REPLACE_INTERVAL_MILLIS)
            replacing = true
            retry()
            replacing = false
        }
    }

    /**
     * Whether the node, holding [used] bearers, accepts another: one beyond [LINKS] while its bridge
     * or replacement dial is in flight, so two nodes dialing each other at once both accept, and the
     * duplicate-link rule or [kept] brings the node back within its budget (crossed-dial slack).
     */
    fun accepts(used: Int): Boolean = used < LINKS + if (reserved == null) 0 else 1

    /** A random element, or `null` if empty. */
    private fun <E> List<E>.pick(): E? = if (isEmpty()) null else get(random.nextInt(0, size))

    companion object {
        /** The link budget d, the low-density value (ADR 0008 decision 1; ticket 36 makes it per class). */
        const val LINKS: Int = 6

        /** How recently a candidate must have been heard to be dialed. */
        const val HEARD_MILLIS: Long = 4_000

        /** How long a node holds [LINKS] bearers before it frees one (newcomer reservation N3). */
        const val FULL_MILLIS: Long = 30_000

        /**
         * How recently a candidate must have first been heard to be a bridge: [SETTLE_MILLIS] plus
         * one 8 s scan period, so a newcomer is heard in at least one scan window once settled.
         */
        const val NEW_MILLIS: Long = 13_000

        /**
         * How long ago a candidate must have first been heard to be a bridge or a replacement: time
         * for the neighbor lists a peer's identifier rotation triggers to name its new identifier.
         */
        const val SETTLE_MILLIS: Long = 5_000

        /** How often a node at most dials a bridge (bridge rule). */
        const val BRIDGE_INTERVAL_MILLIS: Long = 60_000

        /** How often a node that can't dial tries to replace a redundant link (diversity replacement). */
        const val REPLACE_INTERVAL_MILLIS: Long = 30_000

        /** How many fewer lists must name a replacement than the redundancy of the link it replaces. */
        const val REPLACE_MARGIN: Int = 2

        /** The weakest smoothed signal, in dBm, at which a node dials a candidate other than a bridge. */
        const val DIAL_FLOOR_DBM: Double = -85.0

        /** The weakest smoothed signal, in dBm, at which a node dials a bridge (the bridge rule's relaxed floor). */
        const val BRIDGE_FLOOR_DBM: Double = -88.0
    }
}

/**
 * A link candidate: its latest mesh [advertisement], or `null` for an identifier-less one, when it
 * was last [heard] and first heard ([since]) on the monotonic clock, whether the node knows its
 * peer ([known]): its identifier resolved to a peer's resolving key, and its smoothed signal
 * strength in dBm ([rssi]).
 */
internal class Candidate(
    val advertisement: Advertisement?,
    val heard: Long,
    val since: Long,
    val known: Boolean,
    val rssi: Double,
)
