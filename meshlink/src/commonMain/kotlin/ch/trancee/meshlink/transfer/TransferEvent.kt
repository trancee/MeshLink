package ch.trancee.meshlink.transfer

sealed interface TransferEvent {

    data class AssemblyComplete(
        val messageId: ByteArray,
        val payload: ByteArray,
        val fromPeerId: ByteArray,
    ) : TransferEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AssemblyComplete) return false
            return messageId.contentEquals(other.messageId) &&
                payload.contentEquals(other.payload) &&
                fromPeerId.contentEquals(other.fromPeerId)
        }

        override fun hashCode(): Int {
            var h = messageId.contentHashCode()
            h = 31 * h + payload.contentHashCode()
            h = 31 * h + fromPeerId.contentHashCode()
            return h
        }
    }

    data class TransferFailed(
        val messageId: ByteArray,
        val reason: FailureReason,
    ) : TransferEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TransferFailed) return false
            return reason == other.reason && messageId.contentEquals(other.messageId)
        }

        override fun hashCode(): Int = 31 * messageId.contentHashCode() + reason.hashCode()
    }

    data class ChunkProgress(
        val messageId: ByteArray,
        val chunksReceived: Int,
        val totalChunks: Int,
    ) : TransferEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ChunkProgress) return false
            return chunksReceived == other.chunksReceived &&
                totalChunks == other.totalChunks &&
                messageId.contentEquals(other.messageId)
        }

        override fun hashCode(): Int {
            var h = messageId.contentHashCode()
            h = 31 * h + chunksReceived.hashCode()
            h = 31 * h + totalChunks.hashCode()
            return h
        }
    }
}
