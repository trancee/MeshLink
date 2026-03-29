package io.meshlink.transfer

/**
 * Receiver-side tracker for selective acknowledgement.
 * Records which chunks have been received, produces ackSeq (highest contiguous)
 * and a 64-bit SACK bitmask for out-of-order chunks beyond ackSeq.
 */
internal class SackTracker(val totalChunks: Int) {

    data class Status(val ackSeq: Int, val sackBitmask: ULong)

    private val received = BooleanArray(totalChunks)

    fun record(seqNum: Int) {
        if (seqNum in 0 until totalChunks) received[seqNum] = true
    }

    fun status(): Status {
        // ackSeq = highest contiguous received from 0
        var ackSeq = -1
        for (i in 0 until totalChunks) {
            if (received[i]) ackSeq = i else break
        }

        // sackBitmask: bits for received chunks beyond ackSeq+1
        var bitmask = 0uL
        val base = ackSeq + 1
        for (i in base until totalChunks.coerceAtMost(base + 64)) {
            if (received[i]) {
                bitmask = bitmask or (1uL shl (i - base))
            }
        }
        return Status(ackSeq.coerceAtLeast(0), bitmask)
    }

    fun isComplete(): Boolean = received.all { it }

    companion object {
        fun missingChunks(totalChunks: Int, ackSeq: Int, sackBitmask: ULong): List<Int> {
            val missing = mutableListOf<Int>()
            val base = ackSeq + 1
            for (i in base until totalChunks) {
                val offset = i - base
                if (offset < 64 && sackBitmask and (1uL shl offset) != 0uL) continue
                missing.add(i)
            }
            return missing
        }
    }
}
