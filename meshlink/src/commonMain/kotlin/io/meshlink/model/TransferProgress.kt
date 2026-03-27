package io.meshlink.model

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class TransferProgress(
    val messageId: Uuid,
    val chunksAcked: Int,
    val totalChunks: Int,
) {
    val fraction: Float get() = if (totalChunks == 0) 1f else chunksAcked.toFloat() / totalChunks
}
