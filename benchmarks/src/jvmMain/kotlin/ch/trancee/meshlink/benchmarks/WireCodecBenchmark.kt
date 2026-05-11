@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package ch.trancee.meshlink.benchmarks

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.wire.WireCodec
import ch.trancee.meshlink.wire.WireFrame
import java.util.concurrent.TimeUnit
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
class WireCodecBenchmark {
    private lateinit var messageFrame: WireFrame.Message
    private lateinit var transferChunkFrame: WireFrame.TransferChunk
    private lateinit var encodedMessage: ByteArray
    private lateinit var encodedTransferChunk: ByteArray

    @Setup
    fun prepare(): Unit {
        messageFrame =
            WireFrame.Message(
                messageId = "msg-001",
                originPeerId = PeerId("origin-001"),
                destinationPeerId = PeerId("destination-001"),
                priority = DeliveryPriority.NORMAL,
                ttlMillis = 15_000,
                encryptedPayload = ByteArray(256) { index -> (index and 0xFF).toByte() },
            )
        transferChunkFrame =
            WireFrame.TransferChunk(
                transferId = "transfer-001",
                chunkIndex = 3,
                payload = ByteArray(512) { index -> ((index * 3) and 0xFF).toByte() },
            )
        encodedMessage = WireCodec.encode(messageFrame)
        encodedTransferChunk = WireCodec.encode(transferChunkFrame)
    }

    @Benchmark
    fun encodeMessage(blackhole: Blackhole): Unit {
        blackhole.consume(WireCodec.encode(messageFrame))
    }

    @Benchmark
    fun decodeMessage(blackhole: Blackhole): Unit {
        blackhole.consume(WireCodec.decode(encodedMessage))
    }

    @Benchmark
    fun encodeTransferChunk(blackhole: Blackhole): Unit {
        blackhole.consume(WireCodec.encode(transferChunkFrame))
    }

    @Benchmark
    fun decodeTransferChunk(blackhole: Blackhole): Unit {
        blackhole.consume(WireCodec.decode(encodedTransferChunk))
    }
}
