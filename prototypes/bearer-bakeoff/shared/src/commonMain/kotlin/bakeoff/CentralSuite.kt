package bakeoff

import kotlin.random.Random
import kotlin.time.TimeSource

private const val BULK_MESSAGE_SIZE = 16384
private const val ECHO_PAYLOAD_SIZE = 64

class TestContext(
    val recorder: Recorder,
    val central: DeviceInfo,
    val peripheral: DeviceInfo?,
    val bearer: Bearer,
    val phy: String,
    val mtu: Int,
    val attempt: Int,
)

/** Central-side measurements over one established link; each test emits one record. */
class CentralSuite(private val pipe: MessagePipe, private val context: TestContext) {
    private fun record(test: String, bearer: String, timings: Map<String, Any?>, bytes: Long?, extra: Map<String, Any?> = emptyMap()) =
        context.recorder.record(
            test = test, outcome = "ok", central = context.central, peripheral = context.peripheral,
            bearer = bearer, phy = context.phy, mtu = context.mtu, attempt = context.attempt,
            timings = timings, bytes = bytes, extra = extra,
        )

    suspend fun echo(count: Int) {
        val samples = ArrayList<Double>(count)
        repeat(count) {
            val payload = Random.nextBytes(ECHO_PAYLOAD_SIZE)
            val start = TimeSource.Monotonic.markNow()
            pipe.send(MessageType.ECHO, payload)
            val reply = pipe.expect(MessageType.ECHO)
            samples += start.elapsedNow().inWholeMicroseconds / 1000.0
            if (!reply.payload.contentEquals(payload)) throw ProtocolException("echo payload mismatch")
        }
        record("echo_rtt_64b", context.bearer.label, SampleStats(samples).summary, ECHO_PAYLOAD_SIZE.toLong() * count)
    }

    suspend fun throughputCentralToPeripheral(total: Int) {
        val block = Random.nextBytes(BULK_MESSAGE_SIZE)
        val start = TimeSource.Monotonic.markNow()
        var sent = 0
        while (sent < total) {
            val size = minOf(BULK_MESSAGE_SIZE, total - sent)
            pipe.send(MessageType.BULK, if (size == BULK_MESSAGE_SIZE) block else block.copyOf(size))
            sent += size
        }
        val sendDoneMs = start.elapsedNow().inWholeMicroseconds / 1000.0
        pipe.send(MessageType.BULK_END, ByteWriter().int(total).toByteArray())
        val ack = ByteReader(pipe.expect(MessageType.BULK_ACK).payload)
        val totalMs = start.elapsedNow().inWholeMicroseconds / 1000.0
        val received = ack.long()
        val receiverSpanMs = ack.long() / 1000.0
        if (received != total.toLong()) throw ProtocolException("peer received $received of $total")
        val bearer = if (context.bearer == Bearer.GATT) "gatt_write_without_response" else context.bearer.label
        record(
            "throughput_central_to_peripheral", bearer,
            mapOf(
                "send_done_ms" to round3(sendDoneMs), "acked_ms" to round3(totalMs),
                "receiver_first_to_last_ms" to round3(receiverSpanMs),
                "kbytes_per_s" to round3(total / totalMs),
            ),
            total.toLong(),
        )
    }

    suspend fun throughputPeripheralToCentral(total: Int) {
        val start = TimeSource.Monotonic.markNow()
        pipe.send(MessageType.BULK_REQUEST, ByteWriter().int(total).int(BULK_MESSAGE_SIZE).toByteArray())
        var received = 0L
        var firstMs = -1.0
        while (true) {
            val message = pipe.receive()
            when (message.type) {
                MessageType.BULK -> {
                    if (firstMs < 0) firstMs = start.elapsedNow().inWholeMicroseconds / 1000.0
                    received += message.payload.size
                }
                MessageType.BULK_END -> break
                else -> throw ProtocolException("unexpected ${message.type} during bulk download")
            }
        }
        val totalMs = start.elapsedNow().inWholeMicroseconds / 1000.0
        if (received != total.toLong()) throw ProtocolException("received $received of $total")
        val bearer = if (context.bearer == Bearer.GATT) "gatt_notify" else context.bearer.label
        record(
            "throughput_peripheral_to_central", bearer,
            mapOf(
                "first_byte_ms" to round3(firstMs), "done_ms" to round3(totalMs),
                "kbytes_per_s" to round3(total / totalMs),
                "kbytes_per_s_after_first" to round3(total / (totalMs - firstMs)),
            ),
            total.toLong(),
        )
    }

    /** Alternating dummy handshake: central sends odd messages, peripheral replies even ones; last message is acked. */
    suspend fun transcript(name: String, sizes: List<Int>, repetitions: Int) {
        val samples = ArrayList<Double>(repetitions)
        repeat(repetitions) {
            val start = TimeSource.Monotonic.markNow()
            var index = 0
            while (index < sizes.size) {
                val replySize = if (index + 1 < sizes.size) sizes[index + 1] else 0
                pipe.send(MessageType.HANDSHAKE, ByteWriter().short(replySize).bytes(Random.nextBytes(sizes[index])).toByteArray())
                val reply = pipe.expect(MessageType.HANDSHAKE)
                if (reply.payload.size - 2 != replySize) throw ProtocolException("handshake reply ${reply.payload.size - 2} != $replySize")
                index += 2
            }
            samples += start.elapsedNow().inWholeMicroseconds / 1000.0
        }
        record(name, context.bearer.label, SampleStats(samples).summary, sizes.sum().toLong(), mapOf("message_sizes" to sizes, "includes_final_ack" to true))
    }

    suspend fun runAll(throughputBytes: Int, echoCount: Int, repetitions: Int) {
        echo(echoCount)
        throughputCentralToPeripheral(throughputBytes)
        throughputPeripheralToCentral(throughputBytes)
        transcript("handshake_xx_classical", HandshakeSizes.CLASSICAL_XX, repetitions)
        transcript("handshake_xxhfs_pq", HandshakeSizes.PQ_XXHFS, repetitions)
        transcript("first_message_classical", listOf(HandshakeSizes.CLASSICAL_FIRST_MESSAGE), repetitions)
        transcript("first_message_pq", listOf(HandshakeSizes.PQ_FIRST_MESSAGE), repetitions)
    }
}
