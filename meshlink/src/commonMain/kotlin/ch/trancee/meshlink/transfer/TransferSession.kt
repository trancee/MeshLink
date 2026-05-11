package ch.trancee.meshlink.transfer

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.wire.WireFrame

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
        repeat(totalChunks) { chunkIndex ->
            if (
                chunkIndex <= ackFrame.highestContiguousAck || selectiveBitSet.isMarked(chunkIndex)
            ) {
                acknowledgedChunks[chunkIndex] = true
            }
        }
    }

    internal fun missingChunkIndices(): List<Int> {
        return acknowledgedChunks.indices.filter { chunkIndex -> !acknowledgedChunks[chunkIndex] }
    }

    internal fun isComplete(): Boolean {
        return acknowledgedChunks.all { acknowledged -> acknowledged }
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

    internal fun acceptChunk(frame: WireFrame.TransferChunk): Unit {
        if (frame.chunkIndex !in 0 until totalChunks) {
            return
        }
        if (receivedChunks[frame.chunkIndex] == null) {
            receivedChunks[frame.chunkIndex] = frame.payload.copyOf()
        }
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
        return WireFrame.TransferAck(
            transferId = transferId,
            highestContiguousAck = highestContiguousAck(),
            selectiveRanges = selectiveRangesBitSet(),
        )
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
