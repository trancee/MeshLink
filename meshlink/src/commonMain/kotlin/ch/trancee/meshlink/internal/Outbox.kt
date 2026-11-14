package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.FailureReason
import ch.trancee.meshlink.NodeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * The messages this node accepted and has not finished (ADR 0013 decisions 5, 6, 8 and 9): a queue
 * per destination that outlives links and each message's lifetime. A message frees its bytes in
 * [capacity] once written or finished.
 *
 * Runs on [scope]'s serial dispatcher.
 */
internal class Outbox(
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val capacity: Capacity,
) {
    private val queues = mutableMapOf<NodeId, ArrayDeque<Entry>>()
    private val linked = mutableSetOf<NodeId>()
    private val changes = MutableStateFlow(0L)
    private lateinit var run: CoroutineScope

    /** Starts a run of the node: messages added from now on live until it [end]s. */
    fun begin() {
        run = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job))
    }

    /** Ends the run: every unfinished message becomes `Failed(Stopped)`. */
    suspend fun end() {
        run.coroutineContext.job.cancelAndJoin()
    }

    /** Queues [entry], whose bytes are reserved, or fails it if the run has ended. */
    fun add(entry: Entry) {
        if (!run.isActive) return finish(entry, DeliveryStatus.Failed(FailureReason.Stopped))
        // Started at once so it is inside live's try before a stop can cancel it.
        run.launch(start = CoroutineStart.UNDISPATCHED) { live(entry) }
        queues.getOrPut(entry.to) { ArrayDeque() }.addLast(entry)
        if (entry.to !in linked) entry.status.value = DeliveryStatus.Held
        changes.value++
    }

    /** A link to [peer] completed its hello. */
    fun linked(peer: NodeId) {
        linked += peer
    }

    /** The link to [peer] closed: its waiting messages are `Held`. */
    fun unlinked(peer: NodeId) {
        linked -= peer
        queues[peer]?.forEach { it.status.value = DeliveryStatus.Held }
    }

    /** Takes the next message for [peer], waiting for one. */
    suspend fun next(peer: NodeId): Entry {
        while (true) {
            val seen = changes.value
            take(peer)?.let { return it }
            changes.first { it != seen }
        }
    }

    /** Ends a write of [entry] taken by [next]: `Sent`, or back to the front of its queue. */
    fun settle(
        entry: Entry,
        sent: Boolean,
    ) {
        if (entry.done) return
        if (sent) {
            entry.status.value = DeliveryStatus.Sent
            release(entry)
        } else {
            queues.getOrPut(entry.to) { ArrayDeque() }.addFirst(entry)
            if (entry.to !in linked) entry.status.value = DeliveryStatus.Held
            changes.value++
        }
    }

    private fun take(peer: NodeId): Entry? {
        val queue = queues[peer]
        val entry = queue?.removeFirstOrNull()
        if (queue?.isEmpty() == true) queues -= peer
        return entry
    }

    private suspend fun live(entry: Entry) {
        try {
            delay(entry.deadlineMillis - clock.wallClockMillis())
            finish(entry, DeliveryStatus.Expired)
        } finally {
            finish(entry, DeliveryStatus.Failed(FailureReason.Stopped))
        }
    }

    private fun finish(
        entry: Entry,
        status: DeliveryStatus,
    ) {
        if (entry.done) return
        entry.done = true
        entry.status.value = status
        queues[entry.to]?.remove(entry)
        if (queues[entry.to]?.isEmpty() == true) queues -= entry.to
        release(entry)
    }

    private fun release(entry: Entry) {
        if (entry.reserved) capacity.release(entry.size)
        entry.reserved = false
    }

    /** One message to [to] whose [size] payload bytes are reserved, ending at [deadlineMillis] wall-clock time. */
    class Entry(
        val to: NodeId,
        val draft: Draft,
        val size: Int,
        val status: MutableStateFlow<DeliveryStatus>,
        val deadlineMillis: Long,
    ) {
        var reserved = true
        var done = false
    }
}
