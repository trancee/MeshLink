package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.ReceivedMessage
import kotlinx.coroutines.channels.Channel

/**
 * Bounded inbox with a per-sender fair share of a quarter of its capacity. Not thread-safe:
 * every call must run on the owning node's serial dispatcher.
 */
internal class Inbox(private val capacity: Int) {
    enum class Admission { Accepted, SenderShareExceeded, Full }

    private class Entry(val message: ReceivedMessage, val onTaken: () -> Unit)

    private val queue = ArrayDeque<Entry>()
    private val perSender = mutableMapOf<NodeId, Int>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var closed = false

    val senderShare: Int = maxOf(1, capacity / 4)

    fun offer(message: ReceivedMessage, onTaken: () -> Unit): Admission {
        val fromSender = perSender[message.sender] ?: 0
        return when {
            queue.size >= capacity -> Admission.Full
            fromSender >= senderShare -> Admission.SenderShareExceeded
            else -> {
                queue.addLast(Entry(message, onTaken))
                perSender[message.sender] = fromSender + 1
                signal.trySend(Unit)
                Admission.Accepted
            }
        }
    }

    /** Removes the oldest message, or returns `null` once closed and drained. */
    suspend fun take(): ReceivedMessage? {
        while (true) {
            val entry = queue.removeFirstOrNull()
            if (entry != null) {
                val sender = entry.message.sender
                perSender[sender] = (perSender.getValue(sender) - 1).also { if (it == 0) perSender.remove(sender) }
                entry.onTaken()
                return entry.message
            }
            if (closed) return null
            signal.receive()
        }
    }

    fun close() {
        closed = true
        signal.trySend(Unit)
    }
}
