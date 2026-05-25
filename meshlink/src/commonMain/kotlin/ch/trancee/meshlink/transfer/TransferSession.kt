package ch.trancee.meshlink.transfer

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.wire.WireFrame

internal data class TransferSessionRoute(
    val transferId: String,
    val messageId: String,
    val originPeerId: PeerId,
    val destinationPeerId: PeerId,
)

internal data class TransferChunkPlan(
    val chunks: List<ByteArray>,
    val totalBytes: Int,
    val maxChunkPayloadBytes: Int,
)

internal data class TransferStartDescriptor(
    val route: TransferSessionRoute,
    val totalBytes: Int,
    val totalChunks: Int,
    val maxChunkPayloadBytes: Int,
) {
    fun asStartFrame(): WireFrame.TransferStart {
        return WireFrame.TransferStart(
            route =
                WireFrame.TransferStartRoute(
                    transferId = route.transferId,
                    messageId = route.messageId,
                    originPeerId = route.originPeerId,
                    destinationPeerId = route.destinationPeerId,
                ),
            sizing =
                WireFrame.TransferStartSizing(
                    totalBytes = totalBytes,
                    totalChunks = totalChunks,
                    maxChunkPayloadBytes = maxChunkPayloadBytes,
                ),
        )
    }
}

internal fun TransferChunkPlan.toStartDescriptor(
    route: TransferSessionRoute
): TransferStartDescriptor {
    return TransferStartDescriptor(
        route = route,
        totalBytes = totalBytes,
        totalChunks = chunks.size,
        maxChunkPayloadBytes = maxChunkPayloadBytes,
    )
}

internal fun WireFrame.TransferStart.toTransferStartDescriptor(): TransferStartDescriptor {
    return TransferStartDescriptor(
        route =
            TransferSessionRoute(
                transferId = transferId,
                messageId = messageId,
                originPeerId = originPeerId,
                destinationPeerId = destinationPeerId,
            ),
        totalBytes = totalBytes,
        totalChunks = totalChunks,
        maxChunkPayloadBytes = maxChunkPayloadBytes,
    )
}

internal fun ByteArray.isMarked(index: Int): Boolean {
    val byteIndex = indexToByteIndex(index)
    if (byteIndex !in indices) {
        return false
    }
    return (this[byteIndex].toInt() and indexToBitMask(index)) != 0
}

internal fun ByteArray.mark(index: Int): Unit {
    val byteIndex = indexToByteIndex(index)
    if (byteIndex !in indices) {
        return
    }
    this[byteIndex] = (this[byteIndex].toInt() or indexToBitMask(index)).toByte()
}

internal fun bitSetSizeFor(bitCount: Int): Int {
    return (bitCount + BIT_INDEX_MASK) / BITS_PER_BYTE
}

private fun indexToByteIndex(index: Int): Int {
    return index / BITS_PER_BYTE
}

private fun indexToBitMask(index: Int): Int {
    return 1 shl (index % BITS_PER_BYTE)
}

internal sealed class AcknowledgementSettlementResult {
    internal class Completed internal constructor(internal val acknowledgedChunkCount: Int) :
        AcknowledgementSettlementResult()

    internal data object HardRunEnded : AcknowledgementSettlementResult()
}

private const val BITS_PER_BYTE: Int = 8
private const val BIT_INDEX_MASK: Int = 7
