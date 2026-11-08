package meshlink.routingsim.strategy

import kotlin.math.exp
import kotlin.math.ln

/**
 * Stage S Sybil defenses of an honest phone (all off by default). Values are assumptions, fixed before the runs.
 *
 * - D1 scoring: every [checkMs] (with the diversity check), the least useful link past [graceMs] is closed when the
 *   phone has no free slot for its own dials, the other links' median score is at least [minEvidence] (enough traffic
 *   to judge) and the link's score is below [evictFraction] × that median.
 * - D2 probation: at most [maxUnproven] inbound links (incoming dials in progress included) with a score below
 *   [provenScore]; further incoming dials are refused.
 * - D3 confirmed neighbor lists: an entry X in neighbor A's list counts for N2 only if X is this phone, one of its
 *   linked neighbors, listed by another linked neighbor, or heard by its own scans in the last [heardMs]. A neighbor
 *   with no confirmed entry is the most redundant link.
 */
class SybilDefense(
    val d1: Boolean = false,
    val d2: Boolean = false,
    val d3: Boolean = false,
    val halfLifeMs: Long = 60_000,
    val graceMs: Long = 60_000,
    val evictFraction: Double = 0.25,
    val minEvidence: Double = 2.0,
    val maxUnproven: Int = 2,
    val provenScore: Double = 1.0,
    val heardMs: Long = 60_000,
) {
    val usesScore get() = d1 || d2

    companion object {
        val OFF = SybilDefense()
    }
}

/** Per-link exponentially decaying count of timely deliveries (half-life [halfLifeMs]). */
class Usefulness(
    private val halfLifeMs: Long,
) {
    private val score = HashMap<Int, Double>()
    private val at = HashMap<Int, Long>()
    private val rate = ln(2.0) / halfLifeMs

    fun reset(peer: Int) {
        score.remove(peer)
        at.remove(peer)
    }

    fun credit(
        peer: Int,
        now: Long,
    ) {
        score[peer] = get(peer, now) + 1.0
        at[peer] = now
    }

    fun get(
        peer: Int,
        now: Long,
    ): Double {
        val s = score[peer] ?: return 0.0
        val t = at[peer] ?: return 0.0
        return s * exp(-rate * (now - t))
    }
}

/**
 * D1 rule: [scores] holds every link's score, [pastGrace] the links old enough to be judged.
 * Returns the least useful judged link if it is below [SybilDefense.evictFraction] × the median of the other links
 * (which must reach [SybilDefense.minEvidence]), else −1.
 */
fun d1Victim(
    scores: Map<Int, Double>,
    pastGrace: Set<Int>,
    defense: SybilDefense,
): Int {
    val peer = pastGrace.minByOrNull { scores[it] ?: 0.0 } ?: return -1
    val s = scores[peer] ?: 0.0
    val sorted = scores.filterKeys { it != peer }.values.sorted()
    if (sorted.isEmpty()) return -1
    val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    if (median < defense.minEvidence) return -1
    return if (s < defense.evictFraction * median) peer else -1
}
