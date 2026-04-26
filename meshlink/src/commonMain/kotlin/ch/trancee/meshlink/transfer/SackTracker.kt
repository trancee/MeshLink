package ch.trancee.meshlink.transfer

/**
 * 64-bit SACK sliding window tracker for the receiver side.
 *
 * [ackSequence] is the highest consecutive sequence number received. Initially UShort.MAX_VALUE
 * (65535), meaning "nothing received yet" — the first expected chunk is 0. [sackBitmap] bit i
 * represents chunk (ackSequence + 1 + i) has been received.
 *
 * Sequence numbers are 16-bit unsigned (UShort) and wrap at 65535 → 0.
 */
internal class SackTracker {
    private var ackSequence: UShort = UShort.MAX_VALUE
    private var sackBitmap: ULong = 0uL

    /**
     * Computes the zero-based offset of [seqNo] relative to the current [ackSequence], handling
     * UShort wraparound. Returns a value in [0, 65535]. Offset 0 means the chunk immediately after
     * ackSequence. Offset >= 64 means out-of-window or already acknowledged.
     */
    private fun offset(seqNo: UShort): UInt =
        ((seqNo.toUInt() + 65536u - ackSequence.toUInt() - 1u) and 0xFFFFu)

    /**
     * Records [seqNo] as received. Advances [ackSequence] and shifts the bitmap while consecutive
     * chunks are filled.
     */
    fun markReceived(seqNo: UShort) {
        val offset = offset(seqNo)
        if (offset >= 64u) return

        sackBitmap = sackBitmap or (1uL shl offset.toInt())

        // Advance ackSequence as long as the next chunk is already in the bitmap.
        while (sackBitmap and 1uL != 0uL) {
            ackSequence = (ackSequence + 1u).toUShort()
            sackBitmap = sackBitmap shr 1
        }
    }

    /** Returns (ackSequence, sackBitmap) suitable for encoding in a [ChunkAck] message. */
    fun buildAck(): Pair<UShort, ULong> = Pair(ackSequence, sackBitmap)

    /**
     * Returns true if [seqNo] is within the 64-chunk window and has NOT been received. Returns
     * false when [seqNo] is outside the window (already acknowledged or too far ahead).
     */
    fun isMissing(seqNo: UShort): Boolean {
        val offset = offset(seqNo)
        if (offset >= 64u) return false
        return (sackBitmap shr offset.toInt()) and 1uL == 0uL
    }

    /**
     * Returns true if the sender is allowed to send [seqNo] (i.e., it falls within the 64-chunk
     * inflight window: ackSequence+1 … ackSequence+64).
     */
    fun canSend(seqNo: UShort): Boolean = offset(seqNo) < 64u
}
