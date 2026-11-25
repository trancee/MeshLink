package ch.trancee.meshlink.internal

/**
 * One link's reassembly of `FRAGMENT` streams (spec "Fragments", "Busy reply"): at most [SLOTS]
 * streams, at most one of them large, every large one charged to the node's shared [budget]. A
 * stream idle for [IDLE_TIMEOUT_MILLIS] is discarded when the next fragment arrives on this link,
 * or when the budget runs short.
 *
 * Not thread-safe: the node calls every reassembly sharing a budget from its own dispatcher.
 */
public class Reassembly(
    private val budget: ReassemblyBudget,
    private val clock: Clock,
) {
    private val streams = mutableMapOf<Int, Stream>()

    // Streams refused with BUSY whose remaining fragments are dropped silently.
    private val refused = mutableSetOf<Int>()

    init {
        budget.members += this
    }

    /**
     * Takes [fragment]. A stream whose frame would pass [limit] bytes, a fragment that is not the
     * next one of its stream, or a stream that starts with a `FRAGMENT` frame is [Reassembled.Fatal]:
     * the caller closes the bearer and calls [clear].
     */
    public fun accept(
        fragment: LinkFrame.Fragment,
        limit: Int = FRAME_MAX,
    ): Reassembled {
        purge()
        val id = fragment.streamId
        val stream = streams[id]
        val first = fragment.number == 0
        return when {
            stream != null -> {
                append(id, stream, fragment, limit)
            }

            !first && id in refused -> {
                if (fragment.last) refused -= id
                Reassembled.Waiting
            }

            !first || fragment.data[0].toInt() == LinkFrame.Fragment.TYPE -> {
                Reassembled.Fatal
            }

            streams.size == SLOTS -> {
                refuse(id, BusyReason.REASSEMBLY_SLOTS)
            }

            else -> {
                refused -= id
                append(id, Stream().also { streams[id] = it }, fragment, limit)
            }
        }
    }

    /** Frees every stream and leaves the budget; the link has ended. */
    public fun clear() {
        streams.values.forEach { budget.used -= it.charged }
        streams.clear()
        refused.clear()
        budget.members -= this
    }

    /** Discards every stream idle for [IDLE_TIMEOUT_MILLIS]. */
    internal fun purge() {
        val now = clock.monotonicMillis()
        streams.values.removeAll { stream ->
            val idle = now - stream.touched >= IDLE_TIMEOUT_MILLIS
            if (idle) budget.used -= stream.charged
            idle
        }
    }

    private fun append(
        id: Int,
        stream: Stream,
        fragment: LinkFrame.Fragment,
        limit: Int,
    ): Reassembled {
        stream.touched = clock.monotonicMillis()
        val size = stream.size + fragment.data.size
        val large = size > FRAME_SMALL_MAX
        return when {
            fragment.number != stream.next || size > limit -> {
                Reassembled.Fatal
            }

            large && stream.charged == 0 && streams.values.any { it.charged > 0 } -> {
                refuse(id, BusyReason.REASSEMBLY_SLOTS)
            }

            large && !budget.charge(size - stream.charged) -> {
                refuse(id, BusyReason.LARGE_BUDGET)
            }

            else -> {
                if (large) stream.charged = size
                stream.add(fragment.data)
                if (fragment.last) complete(id, stream) else Reassembled.Waiting
            }
        }
    }

    private fun complete(
        id: Int,
        stream: Stream,
    ): Reassembled {
        streams -= id
        budget.used -= stream.charged
        return Reassembled.Complete(stream.frame())
    }

    private fun refuse(
        id: Int,
        reason: BusyReason,
    ): Reassembled {
        streams.remove(id)?.let { budget.used -= it.charged }
        refused += id
        return Reassembled.Refused(LinkFrame.Busy(id, reason))
    }

    private inner class Stream {
        private val pieces = mutableListOf<ByteArray>()
        var size = 0
            private set
        var next = 0
            private set
        var touched = clock.monotonicMillis()

        /** The bytes charged to the budget: all of them once the stream is large, else none. */
        var charged = 0

        fun add(data: ByteArray) {
            pieces += data
            size += data.size
            next++
        }

        fun frame(): ByteArray {
            val frame = ByteArray(size)
            var at = 0
            pieces.forEach {
                it.copyInto(frame, at)
                at += it.size
            }
            return frame
        }
    }

    public companion object {
        /** The largest frame (spec `frame-max`). */
        public const val FRAME_MAX: Int = 139_264

        /** The largest small frame; a stream past it is large (spec `frame-small-max`). */
        public const val FRAME_SMALL_MAX: Int = 8_192

        /** Streams one link reassembles at once (spec `reassemblies-per-link`). */
        public const val SLOTS: Int = 4

        /** How long a stream waits for its next fragment (spec `stream-idle-timeout`). */
        public const val IDLE_TIMEOUT_MILLIS: Long = 10_000
    }
}

/**
 * The bytes all large reassemblies on a node share (spec `large-reassembly-budget`). Before it
 * refuses a charge, it discards the idle streams of every link.
 */
public class ReassemblyBudget {
    /** The bytes charged now. */
    public var used: Int = 0
        internal set

    internal val members = mutableSetOf<Reassembly>()

    internal fun charge(bytes: Int): Boolean {
        if (used + bytes > BYTES) members.forEach { it.purge() }
        val fits = used + bytes <= BYTES
        if (fits) used += bytes
        return fits
    }

    public companion object {
        /** The budget, in bytes. */
        public const val BYTES: Int = 524_288
    }
}

/** What [Reassembly.accept] made of a fragment. */
public sealed interface Reassembled {
    /** Nothing to do yet: the stream continues, or the fragment belongs to a refused stream. */
    public data object Waiting : Reassembled

    /** The stream's last fragment arrived; [frame] is the reassembled frame. */
    public class Complete(
        public val frame: ByteArray,
    ) : Reassembled

    /** The stream was refused; the caller sends [busy] once. */
    public class Refused(
        public val busy: LinkFrame.Busy,
    ) : Reassembled

    /** A fatal link error: the caller closes the bearer. */
    public data object Fatal : Reassembled
}
