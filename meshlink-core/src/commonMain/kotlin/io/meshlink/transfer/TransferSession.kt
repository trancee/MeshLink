package io.meshlink.transfer

/**
 * Sender-side state machine for a single multi-chunk transfer.
 * Tracks which chunks have been acknowledged, uses AIMD window for pacing,
 * and computes which chunks need (re)transmission.
 */
class TransferSession(val totalChunks: Int, initialWindow: Int = 1) {

    private val aimd = AimdController(initialWindow)
    private val acked = BooleanArray(totalChunks)
    private var nextUnsent = 0
    private var highestAckSeq = -1
    private var failed = false

    fun nextChunksToSend(): List<Int> {
        if (isComplete() || failed) return emptyList()

        val toSend = mutableListOf<Int>()

        // First: retransmit sent-but-unacked chunks (gaps)
        for (i in 0 until nextUnsent) {
            if (!acked[i] && toSend.size < aimd.window) {
                toSend.add(i)
            }
        }

        // Then: send new chunks up to window
        while (nextUnsent < totalChunks && toSend.size < aimd.window) {
            toSend.add(nextUnsent++)
        }

        return toSend
    }

    fun onAck(ackSeq: Int, sackBitmask: ULong) {
        // Mark contiguous chunks as acked
        for (i in 0..ackSeq.coerceAtMost(totalChunks - 1)) {
            acked[i] = true
        }
        // Mark sack bits
        val base = ackSeq + 1
        for (offset in 0 until 64) {
            if (sackBitmask and (1uL shl offset) != 0uL) {
                val idx = base + offset
                if (idx < totalChunks) acked[idx] = true
            }
        }
        if (ackSeq > highestAckSeq) highestAckSeq = ackSeq
        aimd.onAck()
    }

    fun onTimeout() {
        aimd.onTimeout()
    }

    fun isComplete(): Boolean = acked.all { it }

    fun isFailed(): Boolean = failed

    fun onInactivityTimeout() {
        failed = true
    }
}
