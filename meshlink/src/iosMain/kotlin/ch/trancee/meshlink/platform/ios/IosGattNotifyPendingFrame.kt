package ch.trancee.meshlink.platform.ios

import kotlinx.coroutines.CompletableDeferred

internal class IosGattNotifyPendingFrame internal constructor(chunks: List<ByteArray>) {
    private val completion: CompletableDeferred<Boolean> = CompletableDeferred()
    private val chunks: List<ByteArray> = chunks.map { chunk -> chunk.copyOf() }
    private var nextChunkIndex: Int = 0

    internal fun nextChunkOrNull(): ByteArray? {
        return chunks.getOrNull(nextChunkIndex)
    }

    internal fun markCurrentChunkSent(): Boolean {
        if (nextChunkIndex < chunks.size) {
            nextChunkIndex += 1
        }
        return nextChunkIndex >= chunks.size
    }

    internal fun remainingChunkCount(): Int {
        return (chunks.size - nextChunkIndex).coerceAtLeast(0)
    }

    internal suspend fun awaitCompletion(): Boolean {
        return completion.await()
    }

    internal fun completeIfPending(result: Boolean): Unit {
        if (!completion.isCompleted) {
            completion.complete(result)
        }
    }
}
