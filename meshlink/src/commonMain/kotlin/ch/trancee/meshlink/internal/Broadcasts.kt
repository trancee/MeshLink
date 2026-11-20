package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.FailureReason
import ch.trancee.meshlink.MeshState
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.OutgoingMessage
import ch.trancee.meshlink.RejectionReason
import ch.trancee.meshlink.SendResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * This node's broadcasts (spec "BROADCAST", "Flood forwarding"): each is written once to every
 * current link, or to the first link that opens if there is none, through [floods], which drops its
 * echoes. [codec] writes its frame: kind `0x04` in `unsecured`, signed kind `0x05` in a secured
 * profile. A broadcast is `Sent` at its first written copy and `Expired` at its expiry plus clock
 * skew; it holds its payload in [capacity] until then.
 *
 * [send] is thread-safe; everything else runs on [scope]'s serial dispatcher.
 */
internal class Broadcasts(
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val capacity: Capacity,
    private val floods: Floods,
    private val codec: BroadcastCodec,
    private val lifetimeSeconds: Int,
) {
    private lateinit var run: CoroutineScope

    /** Starts a run: broadcasts accepted from now on live until it [end]s. */
    fun begin() {
        run = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job))
    }

    /** Ends the run: every unfinished broadcast becomes `Failed(Stopped)`. */
    suspend fun end() {
        run.coroutineContext.job.cancelAndJoin()
    }

    /** Accepts or rejects a broadcast of [payload] with [hopLimit] from a node in [state]. */
    fun send(
        payload: ByteArray,
        hopLimit: Int,
        state: MeshState,
    ): SendResult {
        val reason =
            when {
                !accepting(state) -> RejectionReason.NotRunning
                hopLimit !in 1..Router.HOP_LIMIT_MAX -> RejectionReason.InvalidHopLimit
                payload.size > BroadcastFrame.PAYLOAD_MAX -> RejectionReason.TooLarge
                !capacity.reserve(payload.size) -> RejectionReason.QueueFull
                else -> null
            }
        if (reason != null) return SendResult.Rejected(reason)
        val expiry = clock.wallClockMillis() / MILLIS_PER_SECOND + lifetimeSeconds
        val status = MutableStateFlow<DeliveryStatus>(DeliveryStatus.Queued)
        val bytes = payload.copyOf()
        // A node that closes before the broadcast reaches its run cancels this before it runs.
        scope
            .launch { run.launch(start = CoroutineStart.UNDISPATCHED) { live(bytes, hopLimit, expiry, status) } }
            .invokeOnCompletion { if (it != null) status.value = DeliveryStatus.Failed(FailureReason.Stopped) }
        return SendResult.Accepted(OutgoingMessage(null, status.asStateFlow()))
    }

    private suspend fun live(
        payload: ByteArray,
        hopLimit: Int,
        expiry: Long,
        status: MutableStateFlow<DeliveryStatus>,
    ) {
        val frame = codec.write(hopLimit, expiry, payload)
        val bytes = frame.encode()
        val parcel =
            Parcel(frame.header.messageId, relayed = false, { bytes }) {
                if (it == Settled.SENT && status.value == DeliveryStatus.Queued) status.value = DeliveryStatus.Sent
            }
        var ended: DeliveryStatus = DeliveryStatus.Failed(FailureReason.Stopped)
        try {
            // A run that ended before this started places nothing.
            currentCoroutineContext().ensureActive()
            floods.originate(parcel, expiry)
            delay((expiry + UnsecuredMessaging.CLOCK_SKEW_SECONDS) * MILLIS_PER_SECOND - clock.wallClockMillis())
            ended = DeliveryStatus.Expired
        } finally {
            floods.withdraw(parcel)
            status.value = ended
            capacity.release(payload.size)
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
