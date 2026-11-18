package ch.trancee.meshlink.internal

import kotlinx.coroutines.channels.Channel
import kotlin.math.min

/**
 * One GATT link's notification credits (spec "Flow control", D-47). The peripheral sends one
 * notification per credit the central granted; the central grants [HALF] again each time it has
 * received [HALF] notifications. Not thread-safe: the link's node uses it on its serial dispatcher.
 *
 * @property peripheral whether this end of the link is the peripheral, which takes credits, or
 *   the central, which grants them.
 */
internal class Credits(
    private val peripheral: Boolean,
) {
    private var available = 0
    private var stopped = false
    private var waived = false
    private val granted = Channel<Unit>(Channel.CONFLATED)
    private var unacknowledged = 0

    /** Whether a record was cut short because the link closed while it waited for a credit. */
    var torn: Boolean = false
        private set

    /** Adds the central's grant of [credits]; `false` if this end is the central or the grant would exceed [WINDOW]. */
    fun grant(credits: Int): Boolean {
        val within = peripheral && available + credits <= WINDOW
        if (within) {
            available += credits
            granted.trySend(Unit)
        }
        return within
    }

    /**
     * Ends the need for credits: the central sent `BEARER_SWITCH` and grants no more, so this end's
     * notifications until its own switch go uncredited (D-49).
     */
    fun waive() {
        waived = true
        granted.trySend(Unit)
    }

    /** Ends every wait for a credit: the link is closing. */
    fun stop() {
        stopped = true
        granted.trySend(Unit)
    }

    /**
     * Writes [bytes] to [link]: at once if not [credited], else as one write per ATT MTU − 3 byte
     * chunk, each after taking a credit. Fails without writing more if the link closes first.
     */
    suspend fun write(
        link: RadioLink,
        bytes: ByteArray,
        credited: Boolean,
    ): RadioResult<Unit> {
        if (!credited) return link.write(bytes)
        val chunk = link.attMtu - RadioPort.ATT_HEADER
        var result: RadioResult<Unit> = RadioResult.Ok(Unit)
        var start = 0
        while (start < bytes.size && result is RadioResult.Ok) {
            result =
                if (take()) {
                    link.write(bytes.copyOfRange(start, min(start + chunk, bytes.size)))
                } else {
                    if (start > 0) torn = true
                    RadioResult.Failed(RadioFailure.LINK_CLOSED)
                }
            start += chunk
        }
        return result
    }

    /**
     * Counts one notification the central received if it [counts], one that arrived on the open
     * link; the credits to grant now, or `null`. A peripheral grants none.
     */
    fun notified(counts: Boolean): Int? {
        if (peripheral || !counts) return null
        unacknowledged = (unacknowledged + 1) % HALF
        return if (unacknowledged == 0) HALF else null
    }

    private suspend fun take(): Boolean {
        while (available == 0 && !stopped && !waived) granted.receive()
        if (available > 0) available--
        return !stopped
    }

    companion object {
        /** The most notifications a peripheral may have unacknowledged (spec `gatt-credit-window`). */
        const val WINDOW: Int = 8

        /** The grant a central sends per [HALF] notifications received. */
        const val HALF: Int = WINDOW / 2
    }
}
