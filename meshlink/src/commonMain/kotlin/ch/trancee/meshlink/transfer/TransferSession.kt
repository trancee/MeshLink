package ch.trancee.meshlink.transfer

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.wire.WireFrame
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal class OutboundTransferSession
internal constructor(
    internal val transferId: String,
    internal val messageId: String,
    internal val originPeerId: PeerId,
    internal val destinationPeerId: PeerId,
    chunks: List<ByteArray>,
    internal val totalBytes: Int,
    internal val maxChunkPayloadBytes: Int,
) {
    internal val chunks: List<ByteArray> = chunks.map { chunk -> chunk.copyOf() }
    private val acknowledgedChunks: BooleanArray = BooleanArray(chunks.size)
    private val acknowledgedChunkCountFlow: MutableStateFlow<Int> = MutableStateFlow(0)
    private var acknowledgedChunkCount: Int = 0

    internal val totalChunks: Int
        get() = chunks.size

    internal fun asStartFrame(): WireFrame.TransferStart {
        return WireFrame.TransferStart(
            transferId = transferId,
            messageId = messageId,
            originPeerId = originPeerId,
            destinationPeerId = destinationPeerId,
            totalBytes = totalBytes,
            totalChunks = totalChunks,
            maxChunkPayloadBytes = maxChunkPayloadBytes,
        )
    }

    internal fun markAcknowledged(ackFrame: WireFrame.TransferAck): Unit {
        val selectiveBitSet = ackFrame.selectiveRanges
        var updatedAcknowledgedChunkCount = acknowledgedChunkCount
        repeat(totalChunks) { chunkIndex ->
            if (
                chunkIndex <= ackFrame.highestContiguousAck || selectiveBitSet.isMarked(chunkIndex)
            ) {
                if (!acknowledgedChunks[chunkIndex]) {
                    acknowledgedChunks[chunkIndex] = true
                    updatedAcknowledgedChunkCount += 1
                }
            }
        }
        if (updatedAcknowledgedChunkCount != acknowledgedChunkCount) {
            acknowledgedChunkCount = updatedAcknowledgedChunkCount
            acknowledgedChunkCountFlow.value = updatedAcknowledgedChunkCount
        }
    }

    internal fun missingChunkIndices(): List<Int> {
        return acknowledgedChunks.indices.filter { chunkIndex -> !acknowledgedChunks[chunkIndex] }
    }

    internal fun acknowledgedChunkCount(): Int {
        return acknowledgedChunkCount
    }

    internal suspend fun awaitAcknowledgementSettlement(
        maximumWait: Duration,
        idleWindow: Duration,
    ): Int {
        if (maximumWait <= Duration.ZERO) {
            return acknowledgedChunkCount
        }
        var observedAcknowledgedChunks = acknowledgedChunkCount
        if (observedAcknowledgedChunks >= totalChunks) {
            return observedAcknowledgedChunks
        }
        val startedAt = TimeSource.Monotonic.markNow()
        while (startedAt.elapsedNow() < maximumWait) {
            val remainingBudget = maximumWait - startedAt.elapsedNow()
            val waitWindow = remainingBudget.coerceAtMost(idleWindow)
            val progressedAcknowledgedChunks =
                withTimeoutOrNull(waitWindow) {
                    acknowledgedChunkCountFlow.first { count -> count > observedAcknowledgedChunks }
                } ?: return acknowledgedChunkCount
            observedAcknowledgedChunks = progressedAcknowledgedChunks
            if (observedAcknowledgedChunks >= totalChunks) {
                return observedAcknowledgedChunks
            }
        }
        return acknowledgedChunkCount
    }

    internal fun isComplete(): Boolean {
        return acknowledgedChunkCount >= totalChunks
    }
}

internal class InboundTransferSession
internal constructor(
    internal val transferId: String,
    internal val messageId: String,
    internal val originPeerId: PeerId,
    internal val destinationPeerId: PeerId,
    internal var upstreamPeerId: PeerId,
    internal val totalBytes: Int,
    internal val totalChunks: Int,
    internal val maxChunkPayloadBytes: Int,
) {
    private val receivedChunks: Array<ByteArray?> = arrayOfNulls(totalChunks)
    private var newlyReceivedChunksSinceLastAck: Int = 0

    internal fun acceptChunk(frame: WireFrame.TransferChunk): Boolean {
        if (frame.chunkIndex !in 0 until totalChunks) {
            return false
        }
        val duplicateChunk = receivedChunks[frame.chunkIndex] != null
        if (!duplicateChunk) {
            receivedChunks[frame.chunkIndex] = frame.payload.copyOf()
            newlyReceivedChunksSinceLastAck += 1
        }
        return duplicateChunk || isComplete() ||
            newlyReceivedChunksSinceLastAck >= ACK_BATCH_CHUNK_COUNT
    }

    internal fun highestContiguousAck(): Int {
        var highest = -1
        while (highest + 1 < totalChunks && receivedChunks[highest + 1] != null) {
            highest += 1
        }
        return highest
    }

    internal fun selectiveRangesBitSet(): ByteArray {
        val bitSet = ByteArray((totalChunks + 7) / 8)
        receivedChunks.forEachIndexed { chunkIndex, payload ->
            if (payload != null) {
                bitSet.mark(chunkIndex)
            }
        }
        return bitSet
    }

    internal fun isComplete(): Boolean {
        return receivedChunks.all { chunk -> chunk != null }
    }

    internal fun assembledPayload(): ByteArray {
        val output = ByteArray(totalBytes)
        var writeOffset = 0
        receivedChunks.forEach { chunk ->
            val payload = chunk ?: throw IllegalStateException("Transfer payload is incomplete")
            payload.copyInto(output, destinationOffset = writeOffset)
            writeOffset += payload.size
        }
        return output
    }

    internal fun asAckFrame(): WireFrame.TransferAck {
        newlyReceivedChunksSinceLastAck = 0
        return WireFrame.TransferAck(
            transferId = transferId,
            highestContiguousAck = highestContiguousAck(),
            selectiveRanges = selectiveRangesBitSet(),
        )
    }

    private companion object {
        private const val ACK_BATCH_CHUNK_COUNT: Int = 64
    }
}

internal class RelayTransferSession
internal constructor(
    internal val transferId: String,
    internal val messageId: String,
    internal val originPeerId: PeerId,
    internal val destinationPeerId: PeerId,
    internal var upstreamPeerId: PeerId,
)

private fun ByteArray.isMarked(index: Int): Boolean {
    if (index < 0) {
        return false
    }
    val byteIndex = index / 8
    if (byteIndex !in indices) {
        return false
    }
    val bitMask = 1 shl (index % 8)
    return (this[byteIndex].toInt() and bitMask) != 0
}

private fun ByteArray.mark(index: Int): Unit {
    if (index < 0) {
        return
    }
    val byteIndex = index / 8
    if (byteIndex !in indices) {
        return
    }
    val bitMask = 1 shl (index % 8)
    this[byteIndex] = (this[byteIndex].toInt() or bitMask).toByte()
}
