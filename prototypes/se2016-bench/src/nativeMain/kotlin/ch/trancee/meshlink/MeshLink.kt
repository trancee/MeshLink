package ch.trancee.meshlink

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import platform.posix.CLOCK_UPTIME_RAW
import platform.posix.clock_gettime_nsec_np
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * Fake local node with the ADR 0007 surface. [send] hands the payload to an in-memory echo peer that runs on
 * `Dispatchers.Default` and puts it back into the bounded local inbox, which [messages] drains.
 */
public class MeshLink(outboundCapacity: Int = 64, inboxCapacity: Int = 64) : AutoCloseable {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private val outbound = Channel<Pending>(outboundCapacity)
    private val inbox = Channel<ReceivedMessage>(inboxCapacity)
    private val nextId = AtomicLong(0)
    private val mutableState = MutableStateFlow(MeshState.Stopped)
    private var echo: Job? = null

    public val localNodeId: NodeId = NodeId("local")
    public val echoPeer: NodeId = NodeId("echo")
    public val state: StateFlow<MeshState> get() = mutableState

    /** Bench probes; see [Probes]. */
    public val probes: Probes = Probes()

    /**
     * Messages from the bounded inbox. Single collector: a second concurrent collector completes at once (and is
     * counted in [Probes.rejectedCollectors]) instead of throwing, because a throwing Flow crashes Swift under SKIE.
     */
    public val messages: Flow<ReceivedMessage> = flow {
        if (!probes.activeCollectors.compareAndSet(0, 1)) {
            probes.rejectedCollectors.incrementAndGet()
            return@flow
        }
        probes.collectorStarts.incrementAndGet()
        try {
            for (message in inbox) emit(message)
        } catch (cancellation: CancellationException) {
            probes.lastCollectorCancelNanos.value = Probes.now()
            probes.collectorCancellations.incrementAndGet()
            throw cancellation
        } finally {
            probes.activeCollectors.value = 0
        }
    }

    @Throws(CancellationException::class)
    public suspend fun start() {
        if (mutableState.value == MeshState.Running) return
        echo = scope.launch {
            for (pending in outbound) {
                pending.status.value = DeliveryStatus.Sent
                inbox.send(ReceivedMessage(pending.id, echoPeer, pending.payload, hops = 1))
            }
        }
        mutableState.value = MeshState.Running
    }

    @Throws(CancellationException::class)
    public suspend fun stop() {
        echo?.cancel()
        echo = null
        mutableState.value = MeshState.Stopped
    }

    /** Does not suspend. Copies [payload], as the API prototype does. */
    public fun send(to: NodeId, payload: ByteArray, options: SendOptions = SendOptions.Default): SendResult {
        if (mutableState.value != MeshState.Running) return SendResult.Rejected(RejectionReason.NotRunning)
        if (payload.isEmpty() || payload.size > MAX_PAYLOAD_BYTES) return SendResult.Rejected(RejectionReason.PayloadSize)
        val status = MutableStateFlow<DeliveryStatus>(DeliveryStatus.Queued)
        val pending = Pending(nextId.incrementAndGet(), payload.copyOf(), status)
        if (!outbound.trySend(pending).isSuccess) return SendResult.Rejected(RejectionReason.QueueFull)
        return SendResult.Accepted(OutgoingMessage(pending.id, to, status))
    }

    /** Bench-only: suspends until cancelled, recording entry and cancellation in [probes]. */
    @Throws(CancellationException::class)
    public suspend fun awaitForever() {
        probes.activeSuspendCalls.incrementAndGet()
        probes.suspendStarts.incrementAndGet()
        try {
            awaitCancellation()
        } finally {
            probes.lastSuspendCancelNanos.value = Probes.now()
            probes.suspendCancellations.incrementAndGet()
            probes.activeSuspendCalls.decrementAndGet()
        }
    }

    /** Number of live coroutines this node launched (the echo loop counts as one while running). */
    public val liveJobs: Int get() = job.children.count { it.isActive }

    override fun close() {
        outbound.close()
        inbox.close()
        scope.cancel()
    }

    private class Pending(val id: Long, val payload: ByteArray, val status: MutableStateFlow<DeliveryStatus>)

    public companion object {
        public const val MAX_PAYLOAD_BYTES: Int = 65_536
    }
}

/** Counters and timestamps on `CLOCK_UPTIME_RAW` (the same clock Swift reads with `clock_gettime_nsec_np`). */
@OptIn(ExperimentalForeignApi::class)
public class Probes internal constructor() {
    internal val activeCollectors = AtomicInt(0)
    internal val rejectedCollectors = AtomicInt(0)
    internal val collectorStarts = AtomicInt(0)
    internal val collectorCancellations = AtomicInt(0)
    internal val lastCollectorCancelNanos = AtomicLong(0)
    internal val activeSuspendCalls = AtomicInt(0)
    internal val suspendStarts = AtomicInt(0)
    internal val suspendCancellations = AtomicInt(0)
    internal val lastSuspendCancelNanos = AtomicLong(0)

    public val activeCollectorCount: Int get() = activeCollectors.value
    public val rejectedCollectorCount: Int get() = rejectedCollectors.value
    public val collectorStartCount: Int get() = collectorStarts.value
    public val collectorCancellationCount: Int get() = collectorCancellations.value
    public val lastCollectorCancelUptimeNanos: Long get() = lastCollectorCancelNanos.value
    public val activeSuspendCallCount: Int get() = activeSuspendCalls.value
    public val suspendStartCount: Int get() = suspendStarts.value
    public val suspendCancellationCount: Int get() = suspendCancellations.value
    public val lastSuspendCancelUptimeNanos: Long get() = lastSuspendCancelNanos.value

    public companion object {
        public fun now(): Long = clock_gettime_nsec_np(CLOCK_UPTIME_RAW.toUInt()).toLong()
    }
}
