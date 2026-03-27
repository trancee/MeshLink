package io.meshlink.transfer

/**
 * Calculates resume state for interrupted transfers.
 * Given a list of chunk sizes and the total bytes the receiver confirmed,
 * determines which chunks are already delivered and the starting chunk for resume.
 */
object ResumeCalculator {

    data class ResumeState(
        val resumeFromChunk: Int,
        val completedChunks: Int,
        val partialBytesInChunk: Int,
    )

    /**
     * Calculate resume state from bytesReceived and chunk sizes.
     *
     * @param chunkSizes List of sizes for each chunk (in order)
     * @param bytesReceived Total bytes the receiver confirmed
     * @return ResumeState indicating where to resume sending
     */
    fun calculate(chunkSizes: List<Int>, bytesReceived: UInt): ResumeState {
        if (chunkSizes.isEmpty()) {
            return ResumeState(resumeFromChunk = 0, completedChunks = 0, partialBytesInChunk = 0)
        }

        var remaining = bytesReceived
        var completedChunks = 0

        for (size in chunkSizes) {
            val chunkSize = size.toUInt()
            if (remaining >= chunkSize) {
                remaining -= chunkSize
                completedChunks++
            } else {
                // Partial chunk — resume from this one
                return ResumeState(
                    resumeFromChunk = completedChunks,
                    completedChunks = completedChunks,
                    partialBytesInChunk = remaining.toInt(),
                )
            }
        }

        // All chunks fully received (or bytesReceived exceeds total)
        return ResumeState(
            resumeFromChunk = chunkSizes.size,
            completedChunks = chunkSizes.size,
            partialBytesInChunk = 0,
        )
    }

    /**
     * Calculate total bytes for a given number of completed chunks.
     * Useful for receiver to compute bytesReceived to report.
     *
     * @param chunkSizes List of sizes for each chunk (in order)
     * @param completedChunks Number of fully received chunks
     * @return Total bytes in those chunks
     */
    fun bytesForChunks(chunkSizes: List<Int>, completedChunks: Int): UInt {
        val count = completedChunks.coerceIn(0, chunkSizes.size)
        var total = 0u
        for (i in 0 until count) {
            total += chunkSizes[i].toUInt()
        }
        return total
    }
}
