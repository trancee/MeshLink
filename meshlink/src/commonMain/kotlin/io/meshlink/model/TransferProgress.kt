package io.meshlink.model

data class TransferProgress(
    val messageId: MessageId,
    val chunksAcked: Int,
    val totalChunks: Int,
) {
    val fraction: Float get() = if (totalChunks == 0) 1f else chunksAcked.toFloat() / totalChunks
}
