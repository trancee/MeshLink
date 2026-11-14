package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.FailureReason
import ch.trancee.meshlink.MeshState
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.OutgoingMessage
import ch.trancee.meshlink.ReceivedMessage
import ch.trancee.meshlink.RejectionReason
import ch.trancee.meshlink.SendResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What [Links] needs from the node's messaging; called on the node's dispatcher. */
internal interface Mail {
    /** A link to [peer] completed its hello. */
    fun linked(peer: NodeId)

    /** The last link to [peer] closed. */
    fun unlinked(peer: NodeId)

    /** The next message for [peer] as a link record, waiting for one. */
    suspend fun next(peer: NodeId): Letter

    /** A routed frame arrived on a link whose hello completed. */
    fun received(frame: ByteArray)
}

/** A message ready to write: its link [record], and [settle] to report whether the write succeeded. */
internal class Letter(
    val record: ByteArray,
    val settle: (sent: Boolean) -> Unit,
)

/**
 * A node's direct messages in the `unsecured` profile: validates and queues sends, and accepts
 * received `MESSAGE` frames into [inbox] (ADR 0007 decisions 7 and 9, ADR 0013).
 *
 * [send] and [awaitCapacity] are thread-safe; everything else runs on [scope]'s serial dispatcher.
 */
internal class Post(
    private val crypto: CryptoProvider,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val lifetimeSeconds: Int,
) : Mail {
    private val known = KnownPeers()
    private val capacity = Capacity()
    private val outbox = Outbox(clock, scope, capacity)
    private lateinit var codec: UnsecuredMessaging

    /** Messages received for this node. */
    val inbox = Inbox()

    /** Starts a run of the node whose NodeId is [nodeId]. */
    fun begin(nodeId: ByteArray) {
        codec = UnsecuredMessaging(crypto, nodeId)
        outbox.begin()
    }

    /** Ends the run: unfinished messages fail with `Stopped`. */
    suspend fun end() {
        outbox.end()
    }

    /** Ends [inbox] for good. */
    fun close() {
        inbox.close()
    }

    /** Accepts or rejects a direct message of [payload] to [to] from a node in [state]. */
    fun send(
        to: NodeId,
        payload: ByteArray,
        state: MeshState,
    ): SendResult {
        val reason =
            when {
                state != MeshState.Running && state !is MeshState.Blocked -> RejectionReason.NotRunning
                to !in known -> RejectionReason.UnknownPeer
                payload.size > UnsecuredMessaging.PAYLOAD_MAX -> RejectionReason.TooLarge
                !capacity.reserve(payload.size) -> RejectionReason.QueueFull
                else -> null
            }
        if (reason != null) return SendResult.Rejected(reason)
        val expiry = clock.wallClockMillis() / MILLIS_PER_SECOND + lifetimeSeconds
        val status = MutableStateFlow<DeliveryStatus>(DeliveryStatus.Queued)
        val bytes = payload.copyOf()
        // A node that closes before the message reaches the outbox cancels this before it runs.
        scope.launch { add(to, bytes, expiry, status) }.invokeOnCompletion {
            if (it != null) status.value = DeliveryStatus.Failed(FailureReason.Stopped)
        }
        return SendResult.Accepted(OutgoingMessage(to, status.asStateFlow()))
    }

    /** Returns once the send queue is below its cap. */
    suspend fun awaitCapacity() {
        capacity.await()
    }

    override fun linked(peer: NodeId) {
        known.touch(peer)
        outbox.linked(peer)
    }

    override fun unlinked(peer: NodeId) {
        outbox.unlinked(peer)
    }

    override suspend fun next(peer: NodeId): Letter {
        val entry = outbox.next(peer)
        val epoch = Derivations.epoch(clock.wallClockMillis() / MILLIS_PER_SECOND)
        return Letter(LinkRecord.encode(codec.frame(entry.draft, epoch))) { outbox.settle(entry, it) }
    }

    override fun received(frame: ByteArray) {
        codec.accept(frame, clock.wallClockMillis() / MILLIS_PER_SECOND)?.let {
            inbox.offer(ReceivedMessage(NodeId(it.sender), it.payload))
        }
    }

    private fun add(
        to: NodeId,
        payload: ByteArray,
        expiry: Long,
        status: MutableStateFlow<DeliveryStatus>,
    ) {
        val draft = codec.draft(to.toByteArray(), payload, expiry.toUInt(), DIRECT_HOP_LIMIT)
        val deadline = (expiry + UnsecuredMessaging.CLOCK_SKEW_SECONDS) * MILLIS_PER_SECOND
        outbox.add(Outbox.Entry(to, draft, payload.size, status, deadline))
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L

        // Direct-only, and every send until routing arrives (spec "Origin behavior", D-27).
        const val DIRECT_HOP_LIMIT = 1
    }
}
