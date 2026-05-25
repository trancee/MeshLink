package ch.trancee.meshlink.transfer

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.engine.MeshEngineHardRunToken
import ch.trancee.meshlink.engine.MeshEngineRuntimeGate
import ch.trancee.meshlink.engine.MeshEngineRuntimeTimedWaitResult
import ch.trancee.meshlink.engine.waitWithRuntimeGate
import ch.trancee.meshlink.wire.WireFrame
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

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
        runtimeGate: MeshEngineRuntimeGate,
        hardRunToken: MeshEngineHardRunToken,
    ): AcknowledgementSettlementResult {
        if (maximumWait <= Duration.ZERO || acknowledgedChunkCount >= totalChunks) {
            return AcknowledgementSettlementResult.Completed(acknowledgedChunkCount)
        }
        var observedAcknowledgedChunks = acknowledgedChunkCount
        val startedAt = TimeSource.Monotonic.markNow()
        while (startedAt.elapsedNow() < maximumWait && observedAcknowledgedChunks < totalChunks) {
            val remainingBudget = maximumWait - startedAt.elapsedNow()
            val waitWindow = remainingBudget.coerceAtMost(idleWindow)
            when (
                val waitResult =
                    waitWithRuntimeGate(
                        runtimeGate = runtimeGate,
                        hardRunToken = hardRunToken,
                        maximumActiveWait = waitWindow,
                        awaitChange = { activeWait ->
                            withTimeoutOrNull(activeWait) {
                                acknowledgedChunkCountFlow.first { count ->
                                    count > observedAcknowledgedChunks
                                }
                            }
                        },
                    )
            ) {
                is MeshEngineRuntimeTimedWaitResult.Completed -> {
                    observedAcknowledgedChunks = waitResult.value
                }
                MeshEngineRuntimeTimedWaitResult.TimedOut -> break
                MeshEngineRuntimeTimedWaitResult.HardRunEnded -> {
                    return AcknowledgementSettlementResult.HardRunEnded
                }
            }
        }
        return AcknowledgementSettlementResult.Completed(
            observedAcknowledgedChunks.coerceAtLeast(acknowledgedChunkCount)
        )
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
