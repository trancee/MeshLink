package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.DensityClass
import ch.trancee.meshlink.FailureReason
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.ReadReceiptRequest
import ch.trancee.meshlink.SendOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
 * for the node's [density] class until it expires, each of those a `retry`; in the low and medium
 * classes also on each new link, at most once per [LINK_RETRY_GAP_MILLIS] per destination. A
 * direct-only message is sent on each new link to its destination, in every class. A message is
 * `Delivered` when its receipt arrives, until its deadline, and holds its bytes in [capacity] until
 * it finishes. A message that asked for a read receipt is `Read` when it arrives, before or after
 * its delivery receipt; once delivered, [reads] watches for it.
 *
 * Runs on [scope]'s serial dispatcher.
 */
internal class Outbox(
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val capacity: Capacity,
    private val density: () -> DensityClass,
    private val reads: ReadTracking = ReadTracking {},
    private val transmit: (entry: Entry, retry: Boolean) -> Unit,
) {
    private val entries = mutableListOf<Entry>()
    private val linkRetries = mutableMapOf<NodeId, Long>()
    private lateinit var run: CoroutineScope

    /** How many messages are unfinished. */
    val size: Int get() = entries.size

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
        entry.life = run.launch(start = CoroutineStart.UNDISPATCHED) { live(entry) }
    }

    /**
     * A link to [peer] completed its hello: its direct-only messages are sent at once, and low and
     * medium classes send each destination's other messages again.
     */
    fun linked(peer: NodeId) {
        val sending = entries.filter { !it.quiet }
        sending.filter { it.directOnly && it.to == peer }.forEach { transmit(it, false) }
        if (density() == DensityClass.High) return
        val now = clock.monotonicMillis()
        linkRetries.keys.retainAll(sending.map { it.to }.toSet())
        sending.filter { !it.directOnly }.groupBy { it.to }.forEach { (to, waiting) ->
            val last = linkRetries[to]
            if (last == null || now - last >= LINK_RETRY_GAP_MILLIS) {
                linkRetries[to] = now
                waiting.forEach { transmit(it, false) }
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
                transmit(entry, false)
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
            if (!entry.quiet) action()
        }
    }

    /**
     * [from]'s delivery receipt, or its read receipt if [read], arrived for [messageIds]: those of its
     * messages are `Delivered`, or those that asked for a read receipt `Read`.
     */
    fun delivered(
        from: NodeId,
        messageIds: List<ByteArray>,
        read: Boolean = false,
    ) {
        val listed =
            entries.mapNotNull { entry ->
                val draft = entry.draft ?: return@mapNotNull null
                val id = draft.messageId.takeIf { id -> messageIds.any { it.contentEquals(id) } }
                id?.takeIf { entry.to == from && (entry.readReceipt || !read) }?.let { entry to it }
            }
        listed.forEach { (entry, id) ->
            finish(entry, if (read) DeliveryStatus.Read else DeliveryStatus.Delivered)
            if (entry.readReceipt && !read) reads.track(from, id, entry.status)
        }
        if (read) reads.read(from, messageIds)
    }

    /** The node has no link left: messages not yet sent are `Held`. */
    fun unreachable() {
        entries.forEach { it.hold() }
    }

    private suspend fun live(entry: Entry) {
        try {
            coroutineScope {
                val retries = launch(start = CoroutineStart.UNDISPATCHED) { retry(entry) }
                delay(entry.expiry * MILLIS_PER_SECOND - clock.wallClockMillis())
                retries.cancel()
            }
            // Past its expiry nothing sends it again, but a late receipt still delivers it.
            entry.quiet = true
            delay(entry.deadlineMillis - clock.wallClockMillis())
            finish(entry, DeliveryStatus.Expired)
        } finally {
            finish(entry, DeliveryStatus.Failed(FailureReason.Stopped))
        }
    }

    private suspend fun retry(entry: Entry) {
        var doublings = 0
        var retry = false
        while (true) {
            transmit(entry, retry)
            retry = true
            delay(retryMillis(density(), doublings))
            doublings = minOf(doublings + 1, DOUBLINGS_MAX)
            entry.retries++
        }
    }

    private fun finish(
        entry: Entry,
        status: DeliveryStatus,
    ) {
        if (entry.done) return
        entry.done = true
        entry.quiet = true
        entry.status.value = entry.status.value.then(status)
        entries -= entry
        capacity.release(entry.size)
        entry.life?.cancel()
    }

    /**
     * One message of [payload] to [to], whose bytes are reserved, expiring at Unix second [expiry] and
     * ending at [deadlineMillis] wall-clock time, sent as [options] say, whose read receipt request the
     * node resolved.
     */
    class Entry(
        val to: NodeId,
        val payload: ByteArray,
        val expiry: Long,
        val status: MutableStateFlow<DeliveryStatus>,
        val deadlineMillis: Long,
        options: SendOptions,
    ) {
        /** Whether only a link to [to] carries this message. */
        val directOnly: Boolean = options.directOnly

        /** Whether this is a large message (spec "Size classes"), which is never flooded. */
        val large: Boolean = payload.size > MessageContent.PAYLOAD_SMALL_MAX

        /** Whether this message asks for a read receipt. */
        val readReceipt: Boolean = options.readReceipt == ReadReceiptRequest.Request

        /** The reserved bytes. */
        val size: Int get() = payload.size

        /** The message drafted for its first send, which every retry sends again; `null` before. */
        var draft: Draft? = null

        /**
         * The `message_id` and tombstone secret of this message's first draft, which a draft sealed again
         * after its session lapsed keeps, so its destination takes it once; `null` before.
         */
        var tombstone: Tombstone? = null

        /** The timed retries so far: the sends after the first on the retry schedule. */
        var retries = 0

        /** [retries] when [draft] was drafted. */
        var draftedAt = 0

        var done = false

        /** The message's retries and deadline; `null` if it never went live. */
        var life: Job? = null

        /** Whether nothing sends this message again: it expired or finished. */
        var quiet = false

        /** Whether this message was sent once more by a new search (spec "Origin behavior"). */
        var resent = false

        /** When this message was last sent, by the monotonic clock; 0 before its first send. */
        var sentMillis = 0L

        /** Whether this message waits to be sent on a route just found. */
        var flushing = false

        /** A write of this message succeeded. */
        fun sent() {
            status.value = status.value.then(DeliveryStatus.Sent)
        }

        /** The node lost its last link: this message, if not yet sent, is `Held`. */
        fun hold() {
            if (status.value == DeliveryStatus.Queued) status.value = status.value.then(DeliveryStatus.Held)
        }

        /** A send found no link or no route to take this message: it is `Held` unless it finished. */
        fun held() {
            status.value = status.value.then(DeliveryStatus.Held)
        }
    }

    companion object {
        /** `link-retry-gap`: the least time between two new-link retries to one destination. */
        const val LINK_RETRY_GAP_MILLIS: Long = 5_000

        private const val MILLIS_PER_SECOND = 1_000L

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
            val (min, max) = if (density == DensityClass.High) HIGH else LOW
            return minOf(min shl doublings, max)
        }
    }
}
