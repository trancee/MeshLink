package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DensityClass
import ch.trancee.meshlink.DiagnosticEvent.RelayRateLimited.Frame
import ch.trancee.meshlink.NodeId

/** Whether a relay passes on a flood frame from a linked peer (spec "Relay rate limits"). */
internal interface Admission {
    /** Whether [frame] from linked [peer], a broadcast's from [origin], may be relayed. */
    fun admits(
        peer: NodeId,
        frame: Frame,
        origin: NodeId?,
    ): Boolean

    /** The last link to [peer] closed. */
    fun unlinked(peer: NodeId)

    companion object {
        /** Relays every frame. */
        val ALL: Admission =
            object : Admission {
                override fun admits(
                    peer: NodeId,
                    frame: Frame,
                    origin: NodeId?,
                ): Boolean = true

                override fun unlinked(peer: NodeId) = Unit
            }
    }
}

/**
 * The relay rate limits (spec "Relay rate limits", D-63): each linked peer has a token bucket per
 * [Frame] type, refilled continuously at its rate up to its burst by the current [density] class, and
 * a [secured] relay also a bucket per signed broadcast origin inside the link's broadcast total, at
 * most [originsMax] per link, the least recently used evicted first. A frame takes a token from every
 * bucket that applies, or none if one is empty: then it is [dropped]. Buckets start full when a peer
 * first sends and go when its last link closes. Runs on the node's serial dispatcher.
 */
internal class NeighborLimits(
    private val clock: Clock,
    private val density: () -> DensityClass,
    private val secured: Boolean,
    private val originsMax: Int = ORIGINS_PER_LINK,
    private val dropped: (peer: NodeId, frame: Frame) -> Unit,
) : Admission {
    private val links = mutableMapOf<NodeId, Link>()

    override fun admits(
        peer: NodeId,
        frame: Frame,
        origin: NodeId?,
    ): Boolean {
        val now = clock.monotonicMillis()
        val link = links.getOrPut(peer, ::Link)
        val total = link.buckets.getOrPut(frame) { Bucket(rate(frame).burst.toDouble(), now) }
        val share = origin?.takeIf { secured }?.let { link.origin(it, now) }
        val buckets = listOfNotNull(total to rate(frame), share?.let { it to ORIGIN })
        val admitted = buckets.all { (bucket, rate) -> bucket.has(rate, now) }
        if (admitted) buckets.forEach { it.first.take() } else dropped(peer, frame)
        return admitted
    }

    override fun unlinked(peer: NodeId) {
        links -= peer
    }

    private fun rate(frame: Frame): Rate =
        when {
            frame == Frame.Broadcast -> BROADCAST
            frame != Frame.Unicast -> REQUEST
            density() == DensityClass.Low -> UNICAST_LOW
            else -> UNICAST
        }

    // A link's bucket per frame type and per secured broadcast origin, the latter in order of use.
    private inner class Link {
        val buckets = mutableMapOf<Frame, Bucket>()
        private val origins = LinkedHashMap<NodeId, Bucket>()

        fun origin(
            id: NodeId,
            now: Long,
        ): Bucket {
            val bucket = origins.remove(id) ?: Bucket(ORIGIN.burst.toDouble(), now)
            if (origins.size == originsMax) origins.remove(origins.keys.first())
            origins[id] = bucket
            return bucket
        }
    }

    // A token bucket holding [tokens] at monotonic [at].
    private class Bucket(
        private var tokens: Double,
        private var at: Long,
    ) {
        // Whether a token is left at [now], after refilling at [rate].
        fun has(
            rate: Rate,
            now: Long,
        ): Boolean {
            tokens = minOf(rate.burst.toDouble(), tokens + (now - at).toDouble() / rate.gapMillis)
            at = now
            return tokens >= 1
        }

        fun take() {
            tokens -= 1
        }
    }

    // A bucket that gains a token every [gapMillis], up to [burst].
    private class Rate(
        val gapMillis: Long,
        val burst: Int,
    )

    companion object {
        /** `rate-origins-per-link`: the most signed broadcast origins a link keeps a bucket for. */
        const val ORIGINS_PER_LINK: Int = 32

        // `rate-broadcast-*`, `rate-origin-*`, `rate-unicast-low-*`, `rate-unicast-*` and `rate-request-*`.
        private val BROADCAST = Rate(250, 20)
        private val ORIGIN = Rate(1_000, 5)
        private val UNICAST_LOW = Rate(200, 20)
        private val UNICAST = Rate(100, 40)
        private val REQUEST = Rate(200, 20)
    }
}
