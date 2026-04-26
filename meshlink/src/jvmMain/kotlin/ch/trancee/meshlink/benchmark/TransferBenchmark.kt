package ch.trancee.meshlink.benchmark

import ch.trancee.meshlink.transfer.SackTracker
import ch.trancee.meshlink.wire.Chunk
import ch.trancee.meshlink.wire.WireCodec
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

/**
 * Benchmarks for the transfer layer hot path.
 *
 * - [chunk1KB] / [chunk10KB] / [chunk100KB] — split a payload into GATT-sized (244-byte) chunks and
 *   encode each as a [Chunk] wire message. Measures the full sender-side preparation cost.
 * - [sackProcessing] — [SackTracker.markReceived] + [SackTracker.buildAck] for sequential delivery.
 *   Each invocation marks the next expected seqNo, advancing the window by 1. Wraps at 65 535 → 0
 *   naturally.
 * - [reassembly] — concatenate pre-chunked byte arrays into the final payload. Measures only the
 *   receiver-side assembly step (chunk allocation excluded from the hot path).
 */
@State(Scope.Benchmark)
internal class TransferBenchmark {

    private val chunkSize: Int = 244 // ChunkSizePolicy.GATT

    private lateinit var payload1KB: ByteArray
    private lateinit var payload10KB: ByteArray
    private lateinit var payload100KB: ByteArray
    private lateinit var msgId: ByteArray

    /** Pre-chunked 10 KB payload — input for [reassembly]. */
    private lateinit var preChunked10K: List<ByteArray>

    /** Receiver-side SackTracker advancing through sequential seqNos. */
    private val sackTracker: SackTracker = SackTracker()
    private var sackSeqNo: UShort = 0u

    @Setup
    fun setup() {
        payload1KB = ByteArray(1_024) { it.toByte() }
        payload10KB = ByteArray(10_240) { it.toByte() }
        payload100KB = ByteArray(102_400) { it.toByte() }
        msgId = ByteArray(16) { it.toByte() }

        // Pre-chunk 10 KB for the reassembly benchmark so allocation is excluded.
        val count10K = (payload10KB.size + chunkSize - 1) / chunkSize
        preChunked10K =
            (0 until count10K).map { i ->
                payload10KB.copyOfRange(i * chunkSize, minOf((i + 1) * chunkSize, payload10KB.size))
            }
    }

    /**
     * Split + encode a 1 KB payload into GATT chunks. Returns the total number of bytes produced to
     * prevent dead-code elimination.
     */
    @Benchmark fun chunk1KB(): Int = chunkAndEncode(payload1KB)

    /** Split + encode a 10 KB payload. */
    @Benchmark fun chunk10KB(): Int = chunkAndEncode(payload10KB)

    /** Split + encode a 100 KB payload. */
    @Benchmark fun chunk100KB(): Int = chunkAndEncode(payload100KB)

    /**
     * Receiver-side SACK processing for sequential delivery. Each invocation marks the next
     * consecutive seqNo (window advances by 1) and builds the ACK. Naturally wraps at 65 535 → 0.
     */
    @Benchmark
    fun sackProcessing(): Pair<UShort, ULong> {
        sackTracker.markReceived(sackSeqNo)
        sackSeqNo = (sackSeqNo + 1u).toUShort()
        return sackTracker.buildAck()
    }

    /**
     * Reassemble a pre-chunked 10 KB payload. Benchmarks only the copy-concatenation step —
     * identical to [TransferSession.assemblePayload].
     */
    @Benchmark
    fun reassembly(): ByteArray {
        val chunks = preChunked10K
        val result = ByteArray(chunks.sumOf { it.size })
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }

    /** Split [payload] into [chunkSize]-byte chunks and encode each as a [Chunk] wire message. */
    private fun chunkAndEncode(payload: ByteArray): Int {
        val count = (payload.size + chunkSize - 1) / chunkSize
        var totalBytes = 0
        for (i in 0 until count) {
            val chunkPayload =
                payload.copyOfRange(i * chunkSize, minOf((i + 1) * chunkSize, payload.size))
            val encoded =
                WireCodec.encode(Chunk(msgId, i.toUShort(), count.toUShort(), chunkPayload))
            totalBytes += encoded.size
        }
        return totalBytes
    }
}
