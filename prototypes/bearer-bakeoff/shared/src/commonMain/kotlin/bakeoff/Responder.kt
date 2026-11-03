package bakeoff

import kotlin.random.Random
import kotlin.time.TimeSource

/** Peripheral-side protocol handler: answers the central's test messages on one link. */
class Responder(
    private val pipe: MessagePipe,
    private val local: PeerIdentity,
    private val onHello: (PeerIdentity) -> Unit,
) {
    private var bulkReceived = 0L
    private var bulkFirst: TimeSource.Monotonic.ValueTimeMark? = null
    private var bulkLast: TimeSource.Monotonic.ValueTimeMark? = null

    suspend fun run() {
        while (true) {
            val message = pipe.receive()
            when (message.type) {
                MessageType.HELLO -> {
                    onHello(PeerIdentity.decodeHello(message.payload))
                    pipe.send(MessageType.HELLO, local.encodeHello())
                }
                MessageType.ECHO -> pipe.send(MessageType.ECHO, message.payload)
                MessageType.BULK_REQUEST -> sendBulk(message.payload)
                MessageType.BULK -> {
                    val now = TimeSource.Monotonic.markNow()
                    if (bulkFirst == null) bulkFirst = now
                    bulkLast = now
                    bulkReceived += message.payload.size
                }
                MessageType.BULK_END -> {
                    val first = bulkFirst
                    val last = bulkLast
                    val spanMicros = if (first != null && last != null) (last - first).inWholeMicroseconds else 0L
                    pipe.send(MessageType.BULK_ACK, ByteWriter().long(bulkReceived).long(spanMicros).toByteArray())
                    bulkReceived = 0
                    bulkFirst = null
                    bulkLast = null
                }
                MessageType.HANDSHAKE -> {
                    val replySize = ByteReader(message.payload).short()
                    pipe.send(MessageType.HANDSHAKE, ByteWriter().short(0).bytes(Random.nextBytes(replySize)).toByteArray())
                }
                MessageType.RATE -> sendRate(message.payload)
                MessageType.BYE -> return
                MessageType.BULK_ACK -> throw ProtocolException("unexpected BULK_ACK at responder")
            }
        }
    }

    private suspend fun sendRate(request: ByteArray) {
        val reader = ByteReader(request)
        val count = reader.int()
        val size = reader.int()
        val intervalMs = reader.int().toLong()
        val start = TimeSource.Monotonic.markNow()
        for (i in 0 until count) {
            val due = intervalMs * i - start.elapsedNow().inWholeMilliseconds
            if (due > 0) kotlinx.coroutines.delay(due)
            pipe.send(MessageType.BULK, ByteWriter(size).int(i).bytes(Random.nextBytes(size - 4)).toByteArray())
        }
    }

    private suspend fun sendBulk(request: ByteArray) {
        val reader = ByteReader(request)
        val total = reader.int()
        val messageSize = reader.int()
        val block = Random.nextBytes(messageSize)
        var sent = 0
        while (sent < total) {
            val size = minOf(messageSize, total - sent)
            pipe.send(MessageType.BULK, if (size == messageSize) block else block.copyOf(size))
            sent += size
        }
        pipe.send(MessageType.BULK_END, ByteWriter().int(total).toByteArray())
    }
}
