package ch.trancee.meshlink.internal

/**
 * Where one link's records travel (spec "Bearer switch"): on GATT, as [credits] allow, until this
 * end has written `BEARER_SWITCH`, then on the L2CAP CoC channel it [offer]ed. Not thread-safe: the
 * link writes through it holding its write lock.
 */
internal class Bearer(
    private val gatt: RadioLink,
    private val credits: Credits,
) {
    private var next: RadioStream? = null

    /** The channel this end writes to once it has written `BEARER_SWITCH`, or `null` before. */
    var channel: RadioStream? = null
        private set

    /** Whether the peer's `BEARER_SWITCH` arrived: its records come on [channel] from then on. */
    var moved: Boolean = false

    /** The channel the peer's records come on once [moved], else `null`. */
    val incoming: RadioStream? get() = channel.takeIf { moved }

    /** Makes [stream] the channel the next `BEARER_SWITCH` moves to. */
    fun offer(stream: RadioStream) {
        next = stream
    }

    /** This end wrote `BEARER_SWITCH`: later records go on the offered channel. */
    fun switched() {
        channel = next
    }

    /** Writes [bytes] on the channel once switched, else on GATT, [credited] if so. */
    suspend fun write(
        bytes: ByteArray,
        credited: Boolean,
    ): RadioResult<Unit> {
        val switched = channel
        return if (switched != null) switched.write(bytes) else credits.write(gatt, bytes, credited)
    }

    /** Disconnects GATT and any offered channel. */
    fun disconnect() {
        gatt.disconnect()
        next?.disconnect()
    }
}
