package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DiagnosticEvent
import ch.trancee.meshlink.DiagnosticEvent.InboxFull.Bound
import ch.trancee.meshlink.MeshLinkException
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.ReceivedMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * Received messages waiting for the app (ADR 0007 decision 9): at most [MAX_COUNT] messages and
 * [MAX_BYTES] payload bytes, of which one sender may hold [SENDER_BYTES]. A message beyond a bound
 * is dropped and reported (ML7003) to [diagnose]. Thread-safe.
 */
internal class Inbox(
    private val diagnose: (DiagnosticEvent) -> Unit = {},
) {
    private val channel = Channel<Waiting>(Channel.UNLIMITED)
    private val usage = MutableStateFlow(Usage())
    private val collecting = MutableStateFlow(false)

    /**
     * The waiting messages, then each new one, until [close]. Throws [MeshLinkException.AlreadyCollected]
     * to a collector that starts while another is collecting.
     */
    val messages: Flow<ReceivedMessage> =
        flow {
            if (!collecting.compareAndSet(expect = false, update = true)) throw MeshLinkException.AlreadyCollected()
            try {
                for (waiting in channel) {
                    usage.update { it - waiting.message }
                    waiting.taken()
                    emit(waiting.message)
                }
            } finally {
                collecting.value = false
            }
        }

    /** How many messages wait for the app. */
    val waiting: Int get() = usage.value.count

    /**
     * Adds [message] unless it would cross a bound; returns whether it was added. [taken] runs, on the
     * collector's thread, when the app takes the message.
     */
    fun offer(
        message: ReceivedMessage,
        taken: () -> Unit = {},
    ): Boolean {
        val crossed = usage.getAndUpdate { if (it.crossed(message) == null) it + message else it }.crossed(message)
        if (crossed == null) channel.trySend(Waiting(message, taken)) else diagnose(DiagnosticEvent.InboxFull(crossed))
        return crossed == null
    }

    /** Ends [messages] once the waiting messages are taken. */
    fun close() {
        channel.close()
    }

    private class Waiting(
        val message: ReceivedMessage,
        val taken: () -> Unit,
    )

    private class Usage(
        val count: Int = 0,
        val bytes: Long = 0,
        val bySender: Map<NodeId, Long> = emptyMap(),
    ) {
        // The bound [message] would cross, or null if it fits.
        fun crossed(message: ReceivedMessage): Bound? {
            val size = message.payload.size
            return when {
                count >= MAX_COUNT -> Bound.Count
                bytes + size > MAX_BYTES -> Bound.Bytes
                (bySender[message.sender] ?: 0) + size > SENDER_BYTES -> Bound.Sender
                else -> null
            }
        }

        operator fun plus(message: ReceivedMessage): Usage = change(message, 1)

        operator fun minus(message: ReceivedMessage): Usage = change(message, -1)

        private fun change(
            message: ReceivedMessage,
            sign: Int,
        ): Usage {
            val size = sign * message.payload.size.toLong()
            val held = (bySender[message.sender] ?: 0) + size
            val senders = if (held == 0L) bySender - message.sender else bySender + (message.sender to held)
            return Usage(count + sign, bytes + size, senders)
        }
    }

    companion object {
        const val MAX_COUNT: Int = 1_024
        const val MAX_BYTES: Long = 1_048_576
        const val SENDER_BYTES: Long = MAX_BYTES / 4
    }
}
