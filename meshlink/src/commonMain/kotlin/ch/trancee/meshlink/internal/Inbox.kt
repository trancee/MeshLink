package ch.trancee.meshlink.internal

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
 * is dropped. Thread-safe.
 */
internal class Inbox {
    private val channel = Channel<ReceivedMessage>(Channel.UNLIMITED)
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
                for (message in channel) {
                    usage.update { it - message }
                    emit(message)
                }
            } finally {
                collecting.value = false
            }
        }

    /** Adds [message] unless it would cross a bound; returns whether it was added. */
    fun offer(message: ReceivedMessage): Boolean {
        val fits = usage.getAndUpdate { if (it.fits(message)) it + message else it }.fits(message)
        if (fits) channel.trySend(message)
        return fits
    }

    /** Ends [messages] once the waiting messages are taken. */
    fun close() {
        channel.close()
    }

    private class Usage(
        val count: Int = 0,
        val bytes: Long = 0,
        val bySender: Map<NodeId, Long> = emptyMap(),
    ) {
        fun fits(message: ReceivedMessage): Boolean {
            val size = message.payload.size
            return count < MAX_COUNT &&
                bytes + size <= MAX_BYTES &&
                (bySender[message.sender] ?: 0) + size <= SENDER_BYTES
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
