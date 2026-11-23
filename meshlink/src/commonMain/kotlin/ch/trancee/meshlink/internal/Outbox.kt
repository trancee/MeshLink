package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.FailureReason
import ch.trancee.meshlink.NodeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * The messages this node accepted and has not finished (ADR 0013 decisions 5, 6, 8 and 9; spec
 * "Origin behavior"). Each one is sent with [transmit] at once, then again after doubling intervals
 * for the node's [density] class until its deadline; in the low and medium classes also on each new
 * link, at most once per [LINK_RETRY_GAP_MILLIS] per destination. A message holds its bytes in
 * [capacity] until it finishes.
 *
 * Runs on [scope]'s serial dispatcher.
 */
internal class Outbox(
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val capacity: Capacity,
    private val density: () -> DensityClass,
    private val transmit: (Entry) -> Unit,
) {
    private val entries = mutableListOf<Entry>()
    private val linkRetries = mutableMapOf<NodeId, Long>()
    private lateinit var run: CoroutineScope

    /** Starts a run of the node: messages added from now on live until it [end]s. */
    fun begin() {
        run = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job))
    }

    /** Ends the run: every unfinished message becomes `Failed(Stopped)`. */
    suspend fun end() {
        run.coroutineContext.job.cancelAndJoin()
    }

    /** Sends [entry], whose bytes are reserved, or fails it if the run has ended. */
    fun add(entry: Entry) {
        if (!run.isActive) return finish(entry, DeliveryStatus.Failed(FailureReason.Stopped))
        entries += entry
        // Started at once so it is inside live's try before a stop can cancel it.
        run.launch(start = CoroutineStart.UNDISPATCHED) { live(entry) }
    }

    /** A link completed its hello: low and medium classes send each destination's messages again. */
    fun linked() {
        if (density() == DensityClass.HIGH) return
        val now = clock.monotonicMillis()
        linkRetries.keys.retainAll(entries.map { it.to }.toSet())
        entries.groupBy { it.to }.forEach { (to, waiting) ->
            val last = linkRetries[to]
            if (last == null || now - last >= LINK_RETRY_GAP_MILLIS) {
                linkRetries[to] = now
                waiting.forEach(transmit)
            }
        }
    }

    /**
     * Sends again the unfinished messages that [match], each once [gapMillis] have passed since it was
     * last sent: next if they have, else when they have. A message waits for one such send at a time.
     */
    fun found(
        gapMillis: Long,
        match: (Entry) -> Boolean,
    ) {
        val now = clock.monotonicMillis()
        entries.filter { !it.flushing && match(it) }.forEach { entry ->
            entry.flushing = true
            after(entry, entry.sentMillis + gapMillis - now) {
                entry.flushing = false
                transmit(entry)
            }
        }
    }

    /** Runs [action] after [delayMillis] unless [entry] has finished by then. */
    fun after(
        entry: Entry,
        delayMillis: Long,
        action: () -> Unit,
    ) {
        run.launch {
            delay(delayMillis)
            if (!entry.done) action()
        }
    }

    /** The node has no link left: messages not yet sent are `Held`. */
    fun unreachable() {
        entries.forEach { it.hold() }
    }

    private suspend fun live(entry: Entry) {
        try {
            coroutineScope {
                val retries = launch(start = CoroutineStart.UNDISPATCHED) { retry(entry) }
                delay(entry.deadlineMillis - clock.wallClockMillis())
                retries.cancel()
            }
            finish(entry, DeliveryStatus.Expired)
        } finally {
            finish(entry, DeliveryStatus.Failed(FailureReason.Stopped))
        }
    }

    private suspend fun retry(entry: Entry) {
        var doublings = 0
        while (true) {
            transmit(entry)
            delay(retryMillis(density(), doublings))
            doublings = minOf(doublings + 1, DOUBLINGS_MAX)
        }
    }

    private fun finish(
        entry: Entry,
        status: DeliveryStatus,
    ) {
        if (entry.done) return
        entry.done = true
        entry.status.value = status
        entries -= entry
        capacity.release(entry.size)
    }

    /**
     * One message of [payload] to [to], whose bytes are reserved, expiring at Unix second [expiry] and
     * ending at [deadlineMillis] wall-clock time.
     */
    class Entry(
        val to: NodeId,
        val payload: ByteArray,
        val expiry: Long,
        val status: MutableStateFlow<DeliveryStatus>,
        val deadlineMillis: Long,
    ) {
        /** The reserved bytes. */
        val size: Int get() = payload.size

        /** The message drafted for its first send, which every retry sends again; `null` before. */
        var draft: Draft? = null

        var done = false

        /** Whether this message was sent once more by a new search (spec "Origin behavior"). */
        var resent = false

        /** When this message was last sent, by the monotonic clock; 0 before its first send. */
        var sentMillis = 0L

        /** Whether this message waits to be sent on a route just found. */
        var flushing = false

        /** A write of this message succeeded. */
        fun sent() {
            if (!done) status.value = DeliveryStatus.Sent
        }

        /** No link can take this message now. */
        fun hold() {
            if (status.value == DeliveryStatus.Queued) status.value = DeliveryStatus.Held
        }
    }

    companion object {
        /** `link-retry-gap`: the least time between two new-link retries to one destination. */
        const val LINK_RETRY_GAP_MILLIS: Long = 5_000

        // retry-min-low to retry-max-low (low and medium) and retry-min-high to retry-max-high.
        private val LOW = 2_000L to 30_000L
        private val HIGH = 5_000L to 60_000L

        /** Enough doublings to reach either maximum. */
        const val DOUBLINGS_MAX: Int = 5

        /** The wait after a send that followed [doublings] doublings, in [density]'s class (spec "Origin behavior"). */
        fun retryMillis(
            density: DensityClass,
            doublings: Int,
        ): Long {
            val (min, max) = if (density == DensityClass.HIGH) HIGH else LOW
            return minOf(min shl doublings, max)
        }
    }
}
