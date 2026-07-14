package ch.trancee.meshlink.transfer

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.engine.assembly.MeshEngineHardRunToken
import ch.trancee.meshlink.wire.WireFrame

internal data class InboundChunkAcceptance(
    val accepted: Boolean,
    val chunkIndex: Int,
    val payloadBytes: Int,
    val duplicateChunk: Boolean,
    val receivedChunkCount: Int,
    val newlyReceivedChunksSinceLastAck: Int,
    val highestContiguousAck: Int,
    val complete: Boolean,
    val shouldAcknowledge: Boolean,
)

internal data class PreparedInboundTransferAck(
    val frame: WireFrame.TransferAck,
    val receivedChunkCount: Int,
    val newlyReceivedChunksSinceLastAck: Int,
    val highestContiguousAck: Int,
    val complete: Boolean,
)

internal class InboundTransferSession
internal constructor(
    private val startDescriptor: TransferStartDescriptor,
    internal var upstreamPeerId: PeerId,
    internal val hardRunToken: MeshEngineHardRunToken,
) {
    internal val transferId: String
        get() = startDescriptor.route.transferId

    internal val messageId: String
        get() = startDescriptor.route.messageId

    internal val originPeerId: PeerId
        get() = startDescriptor.route.originPeerId

    internal val destinationPeerId: PeerId
        get() = startDescriptor.route.destinationPeerId

    internal val totalBytes: Int
        get() = startDescriptor.totalBytes

    internal val totalChunks: Int
        get() = startDescriptor.totalChunks

    internal val maxChunkPayloadBytes: Int
        get() = startDescriptor.maxChunkPayloadBytes

    private val receivedChunks: Array<ByteArray?> = arrayOfNulls(totalChunks)
    private val receivedChunkBitSet: ByteArray = ByteArray(bitSetSizeFor(totalChunks))
    private var receivedChunkCount: Int = 0
    private var newlyReceivedChunksSinceLastAck: Int = 0
    private var highestContiguousAck: Int = -1

    internal fun acceptChunk(frame: WireFrame.TransferChunk): InboundChunkAcceptance {
        if (frame.chunkIndex !in 0 until totalChunks) {
            return InboundChunkAcceptance(
                accepted = false,
                chunkIndex = frame.chunkIndex,
                payloadBytes = frame.payload.size,
                duplicateChunk = false,
                receivedChunkCount = receivedChunkCount,
                newlyReceivedChunksSinceLastAck = newlyReceivedChunksSinceLastAck,
                highestContiguousAck = highestContiguousAck,
                complete = isComplete(),
                shouldAcknowledge = false,
            )
        }

        val duplicateChunk = receivedChunks[frame.chunkIndex] != null
        if (!duplicateChunk) {
            receivedChunks[frame.chunkIndex] = frame.payload.copyOf()
            receivedChunkBitSet.mark(frame.chunkIndex)
            receivedChunkCount += 1
            newlyReceivedChunksSinceLastAck += 1
            advanceHighestContiguousAck(frame.chunkIndex)
        }
        val complete = isComplete()
        val shouldAcknowledge =
            duplicateChunk || complete || newlyReceivedChunksSinceLastAck >= ACK_BATCH_CHUNK_COUNT
        return InboundChunkAcceptance(
            accepted = true,
            chunkIndex = frame.chunkIndex,
            payloadBytes = frame.payload.size,
            duplicateChunk = duplicateChunk,
            receivedChunkCount = receivedChunkCount,
            newlyReceivedChunksSinceLastAck = newlyReceivedChunksSinceLastAck,
            highestContiguousAck = highestContiguousAck,
            complete = complete,
            shouldAcknowledge = shouldAcknowledge,
        )
    }

    internal fun highestContiguousAck(): Int {
        return highestContiguousAck
    }

    internal fun selectiveRangesBitSet(): ByteArray {
        return receivedChunkBitSet.copyOf()
    }

    internal fun receivedChunkCount(): Int {
        return receivedChunkCount
    }

    internal fun isComplete(): Boolean {
        return receivedChunkCount >= totalChunks
    }

    internal fun assembledPayload(): ByteArray {
        val output = ByteArray(totalBytes)
        var writeOffset = 0
        receivedChunks.forEach { chunk ->
            val payload = checkNotNull(chunk) { "Transfer payload is incomplete" }
            payload.copyInto(output, destinationOffset = writeOffset)
            writeOffset += payload.size
        }
        return output
    }

    internal fun prepareAck(): PreparedInboundTransferAck {
        val preparedAck =
            PreparedInboundTransferAck(
                frame =
                    WireFrame.TransferAck(
                        transferId = transferId,
                        highestContiguousAck = highestContiguousAck,
                        selectiveRanges = receivedChunkBitSet,
                    ),
                receivedChunkCount = receivedChunkCount,
                newlyReceivedChunksSinceLastAck = newlyReceivedChunksSinceLastAck,
                highestContiguousAck = highestContiguousAck,
                complete = isComplete(),
            )
        newlyReceivedChunksSinceLastAck = 0
        return preparedAck
    }

    private fun advanceHighestContiguousAck(receivedChunkIndex: Int): Unit {
        if (receivedChunkIndex != highestContiguousAck + 1) {
            return
        }
        while (
            highestContiguousAck + 1 < totalChunks &&
                receivedChunks[highestContiguousAck + 1] != null
        ) {
            highestContiguousAck += 1
        }
    }

    internal fun asAckFrame(): WireFrame.TransferAck {
        return prepareAck().frame
    }

    private companion object {
        private const val ACK_BATCH_CHUNK_COUNT: Int = 16
    }
}
