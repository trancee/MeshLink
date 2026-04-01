package io.meshlink.transfer

/**
 * Receiver-side tracker for selective acknowledgement.
 * Records which chunks have been received, produces ackSeq (highest contiguous)
 * and a 128-bit SACK bitmask (two ULongs) for out-of-order chunks beyond ackSeq.
 */
internal class SackTracker(val totalChunks: Int) {

    data class Status(val ackSeq: Int, val sackBitmask: ULong, val sackBitmaskHigh: ULong)

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

        // sackBitmask: bits 0–63 for received chunks beyond ackSeq+1
        var low = 0uL
        val base = ackSeq + 1
        for (i in base until totalChunks.coerceAtMost(base + 64)) {
            if (received[i]) {
                low = low or (1uL shl (i - base))
            }
        }

        // sackBitmaskHigh: bits 64–127 for received chunks beyond ackSeq+65
        var high = 0uL
        val highBase = base + 64
        for (i in highBase until totalChunks.coerceAtMost(highBase + 64)) {
            if (received[i]) {
                high = high or (1uL shl (i - highBase))
            }
        }

        return Status(ackSeq.coerceAtLeast(0), low, high)
    }

    fun isComplete(): Boolean = received.all { it }

    companion object {
        fun missingChunks(
            totalChunks: Int,
            ackSeq: Int,
            sackBitmask: ULong,
            sackBitmaskHigh: ULong,
        ): List<Int> {
            val missing = mutableListOf<Int>()
            val base = ackSeq + 1
            for (i in base until totalChunks) {
                val offset = i - base
                if (offset < 64 && sackBitmask and (1uL shl offset) != 0uL) continue
                if (offset in 64..127 && sackBitmaskHigh and (1uL shl (offset - 64)) != 0uL) continue
                missing.add(i)
            }
            return missing
        }
    }
}
