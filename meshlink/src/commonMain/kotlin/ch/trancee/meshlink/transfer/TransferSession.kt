package ch.trancee.meshlink.transfer

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.wire.WireFrame
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

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
            transferId = route.transferId,
            messageId = route.messageId,
            originPeerId = route.originPeerId,
            destinationPeerId = route.destinationPeerId,
            totalBytes = totalBytes,
            totalChunks = totalChunks,
            maxChunkPayloadBytes = maxChunkPayloadBytes,
        )
    }
}

internal class OutboundTransferSession
private constructor(
    private val startDescriptor: TransferStartDescriptor,
    chunkPlan: TransferChunkPlan,
    copyChunks: Boolean,
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

    internal val maxChunkPayloadBytes: Int
        get() = startDescriptor.maxChunkPayloadBytes

    internal constructor(
        route: TransferSessionRoute,
        chunkPlan: TransferChunkPlan,
    ) : this(
        startDescriptor = chunkPlan.toStartDescriptor(route),
        chunkPlan = chunkPlan,
        copyChunks = true,
    )

    internal val chunks: List<ByteArray> =
        if (copyChunks) {
            chunkPlan.chunks.map { chunk -> chunk.copyOf() }
        } else {
            chunkPlan.chunks.toList()
        }
    private val acknowledgedChunks: BooleanArray = BooleanArray(chunks.size)
    private val acknowledgedChunkCountFlow: MutableStateFlow<Int> = MutableStateFlow(0)
    private var acknowledgedChunkCount: Int = 0

    internal val totalChunks: Int
        get() = chunks.size

    internal fun asStartFrame(): WireFrame.TransferStart {
        return startDescriptor.asStartFrame()
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
        return buildList(capacity = totalChunks - acknowledgedChunkCount) {
            forEachMissingChunkIndex { chunkIndex -> add(chunkIndex) }
        }
    }

    internal inline fun forEachMissingChunkIndex(action: (Int) -> Unit): Unit {
        repeat(totalChunks) { chunkIndex ->
            if (!acknowledgedChunks[chunkIndex]) {
                action(chunkIndex)
            }
        }
    }

    internal fun acknowledgedChunkCount(): Int {
        return acknowledgedChunkCount
    }

    internal suspend fun awaitAcknowledgementSettlement(
        maximumWait: Duration,
        idleWindow: Duration,
    ): Int {
        if (maximumWait <= Duration.ZERO || acknowledgedChunkCount >= totalChunks) {
            return acknowledgedChunkCount
        }
        var observedAcknowledgedChunks = acknowledgedChunkCount
        val startedAt = TimeSource.Monotonic.markNow()
        while (startedAt.elapsedNow() < maximumWait && observedAcknowledgedChunks < totalChunks) {
            val remainingBudget = maximumWait - startedAt.elapsedNow()
            val waitWindow = remainingBudget.coerceAtMost(idleWindow)
            val progressedAcknowledgedChunks =
                withTimeoutOrNull(waitWindow) {
                    acknowledgedChunkCountFlow.first { count -> count > observedAcknowledgedChunks }
                }
            if (progressedAcknowledgedChunks == null) {
                break
            }
            observedAcknowledgedChunks = progressedAcknowledgedChunks
        }
        return observedAcknowledgedChunks.coerceAtLeast(acknowledgedChunkCount)
    }

    internal fun isComplete(): Boolean {
        return acknowledgedChunkCount >= totalChunks
    }

    internal companion object {
        internal fun fromOwnedPlan(
            route: TransferSessionRoute,
            chunkPlan: TransferChunkPlan,
        ): OutboundTransferSession {
            return OutboundTransferSession(
                startDescriptor = chunkPlan.toStartDescriptor(route),
                chunkPlan = chunkPlan,
                copyChunks = false,
            )
        }
    }
}

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

internal class RelayTransferSession
internal constructor(
    internal val transferId: String,
    internal val messageId: String,
    internal val originPeerId: PeerId,
    internal val destinationPeerId: PeerId,
    internal var upstreamPeerId: PeerId,
)

private fun TransferChunkPlan.toStartDescriptor(
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

private fun ByteArray.isMarked(index: Int): Boolean {
    val byteIndex = indexToByteIndex(index)
    if (byteIndex !in indices) {
        return false
    }
    return (this[byteIndex].toInt() and indexToBitMask(index)) != 0
}

private fun ByteArray.mark(index: Int): Unit {
    val byteIndex = indexToByteIndex(index)
    if (byteIndex !in indices) {
        return
    }
    this[byteIndex] = (this[byteIndex].toInt() or indexToBitMask(index)).toByte()
}

private fun bitSetSizeFor(bitCount: Int): Int {
    return (bitCount + BIT_INDEX_MASK) / BITS_PER_BYTE
}

private fun indexToByteIndex(index: Int): Int {
    return index / BITS_PER_BYTE
}

private fun indexToBitMask(index: Int): Int {
    return 1 shl (index % BITS_PER_BYTE)
}

private const val BITS_PER_BYTE: Int = 8
private const val BIT_INDEX_MASK: Int = 7
