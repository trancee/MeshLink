package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId

/**
 * The frames a relay holds for others (spec "Holding"): small unicasts toward an absent destination
 * and broadcasts, at most [MESSAGES_MAX] and [BYTES_MAX] together, each incoming link at most a
 * [LINK_SHARE_DIVISOR]th of either. A frame is live until its expiry; times are wall-clock
 * milliseconds. A node with holding off ([enabled] `false`, ADR 0011 decision 8) holds nothing. Runs on the
 * node's serial dispatcher.
 */
internal class Holding(
    private val enabled: Boolean,
) {
    private val frames = LinkedHashMap<String, HeldFrame>()

    /**
     * Holds [frame], which arrived with [hopLimit], if it can still go on, is small, has at least
     * [REMAINING_MIN_MILLIS] left, expires within a lifetime plus skew, is not held already and
     * finds room under the eviction order; returns whether it did.
     */
    fun hold(
        frame: HeldFrame,
        hopLimit: Int,
        nowMillis: Long,
    ): Boolean {
        purge(nowMillis)
        val key = frame.messageId.toHexString()
        val admitted = enabled && admits(frame, hopLimit, nowMillis) && key !in frames
        val victims = if (admitted) Eviction(frames.values).victims(frame) else null
        victims?.forEach { frames -= it.messageId.toHexString() }
        victims?.let { frames[key] = frame }
        return victims != null
    }

    /** The live unicasts, oldest first. */
    fun unicasts(nowMillis: Long): List<HeldFrame> = live(nowMillis).filter { it.destinationTag != null }

    /** The live broadcasts, oldest first. */
    fun broadcasts(nowMillis: Long): List<HeldFrame> = live(nowMillis).filter { it.destinationTag == null }

    /** The live unicasts as a `CATCH_UP` lists them, oldest first. */
    fun listed(nowMillis: Long): List<LinkFrame.CatchUp.Held> =
        live(nowMillis).mapNotNull { frame ->
            frame.destinationTag?.let { LinkFrame.CatchUp.Held(frame.messageId, it) }
        }

    /** The live unicasts toward destination [tag], oldest first. */
    fun toward(
        tag: ByteArray,
        nowMillis: Long,
    ): List<HeldFrame> = unicasts(nowMillis).filter { tag.contentEquals(it.destinationTag) }

    /** The live frame with message_id [id], if held. */
    fun copy(
        id: ByteArray,
        nowMillis: Long,
    ): HeldFrame? = frames[id.toHexString()]?.takeIf { it.liveAt(nowMillis) }

    /** Drops [frame]. */
    fun drop(frame: HeldFrame) {
        frames -= frame.messageId.toHexString()
    }

    /** Drops every held frame. */
    fun clear() {
        frames.clear()
    }

    private fun live(nowMillis: Long): List<HeldFrame> = frames.values.filter { it.liveAt(nowMillis) }

    private fun purge(nowMillis: Long) {
        frames.values.removeAll { !it.liveAt(nowMillis) }
    }

    private fun admits(
        frame: HeldFrame,
        hopLimit: Int,
        nowMillis: Long,
    ): Boolean =
        hopLimit >= HOP_LIMIT_MIN &&
            frame.bytes.size <= Reassembly.FRAME_SMALL_MAX &&
            frame.expiry * MILLIS_PER_SECOND - nowMillis >= REMAINING_MIN_MILLIS &&
            frame.expiry <= nowMillis / MILLIS_PER_SECOND + EXPIRY_AHEAD_MAX_SECONDS

    companion object {
        /** `held-messages-max`: the most frames held. */
        const val MESSAGES_MAX: Int = 100

        /** `held-bytes-max`: the most bytes held. */
        const val BYTES_MAX: Int = 524_288

        /** Each incoming link holds at most this fraction's reciprocal of [MESSAGES_MAX] and [BYTES_MAX]. */
        const val LINK_SHARE_DIVISOR: Int = 4

        /** A frame that arrived with less hop_limit could not go on. */
        private const val HOP_LIMIT_MIN = 2

        /** A frame with less time left is not worth holding. */
        private const val REMAINING_MIN_MILLIS = 10_000L

        private const val EXPIRY_AHEAD_MAX_SECONDS =
            UnsecuredMessaging.LIFETIME_MAX_SECONDS + UnsecuredMessaging.CLOCK_SKEW_SECONDS
    }
}

/**
 * The eviction order of spec "Holding" (ADR 0014 decision 5) over the frames [held]: a link over its
 * share gives up its own frames closest to expiry; a full hold then gives up the link furthest over its
 * fair share, else the frame closest to expiry. A frame never evicts one that outlives it.
 */
private class Eviction(
    private val held: Collection<HeldFrame>,
) {
    /** The frames [frame] would evict, or null if it finds no room. */
    fun victims(frame: HeldFrame): List<HeldFrame>? = ownVictims(frame)?.let { shared(frame, it) }

    // The frames of [frame]'s own link it evicts to stay within its link's share, or null.
    private fun ownVictims(frame: HeldFrame): List<HeldFrame>? {
        val own = held.filter { it.from == frame.from }.sortedBy { it.expiry }
        var replaced = 0
        while (own.size - replaced + 1 > LINK_MESSAGES_MAX ||
            own.drop(replaced).sumOf { it.bytes.size } + frame.bytes.size > LINK_BYTES_MAX
        ) {
            if (own[replaced].expiry >= frame.expiry) return null
            replaced++
        }
        return own.take(replaced)
    }

    // [victims] and the frames [frame] evicts besides them to fit within the hold, or null.
    private fun shared(
        frame: HeldFrame,
        victims: List<HeldFrame>,
    ): List<HeldFrame>? {
        val remaining = held.toMutableList().apply { removeAll(victims) }
        val evicted = victims.toMutableList()
        while (remaining.size + 1 > Holding.MESSAGES_MAX ||
            remaining.sumOf { it.bytes.size } + frame.bytes.size > Holding.BYTES_MAX
        ) {
            val victim =
                furthestOverShare(remaining, frame.from)
                    ?: remaining.minBy { it.expiry }.takeIf { it.expiry < frame.expiry }
                    ?: return null
            remaining -= victim
            evicted += victim
        }
        return evicted
    }

    // The frame closest to expiry from the link furthest over its fair share of [remaining], if any.
    private fun furthestOverShare(
        remaining: List<HeldFrame>,
        from: NodeId,
    ): HeldFrame? {
        val links = remaining.groupBy { it.from }
        val fair = Holding.MESSAGES_MAX.toLong() * Holding.BYTES_MAX / (links.keys + from).size
        return links.values
            .filter { usage(it) > fair }
            .maxByOrNull { usage(it) }
            ?.minBy { it.expiry }
    }

    private fun usage(frames: List<HeldFrame>): Long =
        maxOf(frames.size.toLong() * Holding.BYTES_MAX, frames.sumOf { it.bytes.size }.toLong() * Holding.MESSAGES_MAX)
}

/**
 * A frame held for others: as relayed, from link [from], with [messageId], toward [destinationTag]
 * (`null` for a broadcast), live until [expiry] in Unix seconds. A `MESSAGE` carries its tombstone
 * [commitment]; other types `null`. [sentTo] are the links it went to from the hold.
 */
internal class HeldFrame(
    val from: NodeId,
    val messageId: ByteArray,
    val destinationTag: ByteArray?,
    val expiry: Long,
    val bytes: ByteArray,
    val commitment: ByteArray? = null,
) {
    val sentTo: MutableSet<NodeId> = mutableSetOf()

    /** Whether the frame is live at wall-clock [nowMillis]. */
    fun liveAt(nowMillis: Long): Boolean = nowMillis < expiry * MILLIS_PER_SECOND

    /** The frame to write again, unchanged. */
    fun parcel(): Parcel = Parcel(messageId, relayed = true, { bytes }, kind = kind) {}

    private val kind: Parcel.Kind
        get() =
            when {
                destinationTag == null -> Parcel.Kind.BROADCAST
                commitment != null -> Parcel.Kind.MESSAGE
                else -> Parcel.Kind.OTHER
            }

    /** The frame to send to [peer], who asked for it with `GRAFT`. */
    fun takenBy(peer: NodeId): Parcel {
        sentTo += peer
        return parcel()
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val LINK_MESSAGES_MAX = Holding.MESSAGES_MAX / Holding.LINK_SHARE_DIVISOR
private const val LINK_BYTES_MAX = Holding.BYTES_MAX / Holding.LINK_SHARE_DIVISOR
