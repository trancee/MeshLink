package ch.trancee.meshlink.benchmark

import ch.trancee.meshlink.wire.Broadcast
import ch.trancee.meshlink.wire.Chunk
import ch.trancee.meshlink.wire.RoutedMessage
import ch.trancee.meshlink.wire.WireCodec
import ch.trancee.meshlink.wire.WireMessage
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

/**
 * Benchmarks for the WireCodec encode/decode hot path.
 *
 * Covers the three most-frequent message types in a running mesh:
 * - [RoutedMessage] — multi-hop unicast payload with visited list
 * - [Chunk] — GATT-sized transfer chunk (244-byte payload)
 * - [Broadcast] — signed flood-fill with signature and signer key
 *
 * Internal — JMH generates subclasses in the same package. `internal` compiles to JVM-public
 * bytecode, so JMH reflection can still generate and instantiate the benchmark subclass.
 */
@State(Scope.Benchmark)
internal class WireFormatBenchmark {

    private lateinit var routedMsg: RoutedMessage
    private lateinit var chunk: Chunk
    private lateinit var broadcast: Broadcast

    /** Pre-encoded bytes — used as input for the decode benchmarks. */
    private lateinit var encodedRouted: ByteArray
    private lateinit var encodedChunk: ByteArray
    private lateinit var encodedBroadcast: ByteArray

    @Setup
    fun setup() {
        routedMsg =
            RoutedMessage(
                messageId = ByteArray(16) { it.toByte() },
                origin = ByteArray(12) { 0x0A },
                destination = ByteArray(12) { 0x0B },
                hopLimit = 7u,
                visitedList = listOf(ByteArray(12) { 0x0C }, ByteArray(12) { 0x0D }),
                priority = 0,
                originationTime = 1_700_000_000_000uL,
                payload = ByteArray(256) { it.toByte() },
            )
        chunk =
            Chunk(
                messageId = ByteArray(16) { it.toByte() },
                seqNo = 5u,
                totalChunks = 42u,
                payload = ByteArray(244) { it.toByte() },
            )
        broadcast =
            Broadcast(
                messageId = ByteArray(16) { it.toByte() },
                origin = ByteArray(12) { 0x0A },
                remainingHops = 5u,
                appIdHash = 0x1234u,
                flags = 0x01u,
                priority = 1,
                signature = ByteArray(64) { it.toByte() },
                signerKey = ByteArray(32) { it.toByte() },
                payload = ByteArray(64) { it.toByte() },
            )

        encodedRouted = WireCodec.encode(routedMsg)
        encodedChunk = WireCodec.encode(chunk)
        encodedBroadcast = WireCodec.encode(broadcast)
    }

    /** Encode a [RoutedMessage] with a 256-byte payload and 2-hop visited list. */
    @Benchmark fun encodeRoutedMessage(): ByteArray = WireCodec.encode(routedMsg)

    /** Decode a pre-encoded [RoutedMessage]. */
    @Benchmark fun decodeRoutedMessage(): WireMessage = WireCodec.decode(encodedRouted)

    /** Encode a GATT-sized [Chunk] (244-byte payload). */
    @Benchmark fun encodeChunk(): ByteArray = WireCodec.encode(chunk)

    /** Decode a pre-encoded [Chunk]. */
    @Benchmark fun decodeChunk(): WireMessage = WireCodec.decode(encodedChunk)

    /**
     * Full round-trip encode then decode a signed [Broadcast] — the most expensive single-message
     * operation due to the signature and signer key byte arrays.
     */
    @Benchmark fun roundTripBroadcast(): WireMessage = WireCodec.decode(WireCodec.encode(broadcast))
}
