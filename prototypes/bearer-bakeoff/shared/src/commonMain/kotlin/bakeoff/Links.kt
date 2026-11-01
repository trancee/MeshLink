package bakeoff

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LinkClosedException(message: String) : Exception(message)

/** A reliable ordered byte stream between two nodes (GATT data path or an L2CAP CoC). */
interface ByteLink {
    val label: String

    suspend fun write(bytes: ByteArray)

    /** Next received stream segment; throws [LinkClosedException] after close. */
    suspend fun read(): ByteArray

    fun close(reason: String)
}

/** Stream link fed by platform callbacks or a reader loop. */
abstract class ChannelByteLink(override val label: String) : ByteLink {
    private val inbound = Channel<ByteArray>(Channel.UNLIMITED)

    fun deliver(bytes: ByteArray) {
        inbound.trySend(bytes)
    }

    override suspend fun read(): ByteArray =
        inbound.receiveCatching().getOrNull() ?: throw LinkClosedException("$label closed")

    protected fun closeInbound(reason: String) {
        inbound.close(LinkClosedException("$label closed: $reason"))
    }
}

/**
 * GATT data path: each ATT value is a 1-byte sequence number followed by stream bytes, so the receiver can
 * detect lost or reordered values (notify / write-without-response give no app-level acknowledgement).
 */
abstract class GattChunkLink(label: String) : ChannelByteLink(label) {
    private var sendSequence = 0
    private var expectedSequence = 0
    private val writeMutex = Mutex()

    /** Largest ATT value this side may send right now (MTU - 3, capped at 512). */
    abstract fun maxValueSize(): Int

    protected abstract suspend fun sendValue(value: ByteArray)

    override suspend fun write(bytes: ByteArray) {
        writeMutex.withLock {
            val bodySize = (maxValueSize() - 1).coerceAtLeast(1)
            var offset = 0
            while (offset < bytes.size) {
                val take = minOf(bodySize, bytes.size - offset)
                val value = ByteArray(take + 1)
                value[0] = sendSequence.toByte()
                bytes.copyInto(value, 1, offset, offset + take)
                sendSequence = (sendSequence + 1) and 0xff
                sendValue(value)
                offset += take
            }
        }
    }

    fun onValueReceived(value: ByteArray) {
        if (value.isEmpty()) return
        val sequence = value[0].toInt() and 0xff
        if (sequence != expectedSequence) {
            close("sequence gap: expected $expectedSequence got $sequence")
            return
        }
        expectedSequence = (expectedSequence + 1) and 0xff
        deliver(value.copyOfRange(1, value.size))
    }
}

class MessagePipe(val link: ByteLink) {
    private val deframer = Deframer()
    private val pending = ArrayDeque<Message>()
    private val sendMutex = Mutex()

    suspend fun send(type: MessageType, payload: ByteArray = ByteArray(0)) {
        sendMutex.withLock { link.write(encodeMessage(type, payload)) }
    }

    suspend fun receive(): Message {
        while (pending.isEmpty()) pending.addAll(deframer.feed(link.read()))
        return pending.removeFirst()
    }

    suspend fun expect(type: MessageType): Message {
        val message = receive()
        if (message.type != type) throw ProtocolException("expected $type got ${message.type}")
        return message
    }
}
