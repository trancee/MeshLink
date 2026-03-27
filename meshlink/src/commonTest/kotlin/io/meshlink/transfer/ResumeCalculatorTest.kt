package io.meshlink.transfer

import kotlin.test.Test
import kotlin.test.assertEquals

class ResumeCalculatorTest {

    // 1. Zero bytes received → resume from chunk 0
    @Test
    fun zeroBytesReceived() {
        val sizes = listOf(100, 100, 100)
        val state = ResumeCalculator.calculate(sizes, 0u)
        assertEquals(0, state.resumeFromChunk)
        assertEquals(0, state.completedChunks)
        assertEquals(0, state.partialBytesInChunk)
    }

    // 2. All bytes received → resume from last chunk (nothing to send)
    @Test
    fun allBytesReceived() {
        val sizes = listOf(100, 100, 100)
        val state = ResumeCalculator.calculate(sizes, 300u)
        assertEquals(3, state.resumeFromChunk)
        assertEquals(3, state.completedChunks)
        assertEquals(0, state.partialBytesInChunk)
    }

    // 3. Exact chunk boundary → resume from next chunk
    @Test
    fun exactChunkBoundary() {
        val sizes = listOf(100, 200, 150)
        val state = ResumeCalculator.calculate(sizes, 300u) // 100 + 200
        assertEquals(2, state.resumeFromChunk)
        assertEquals(2, state.completedChunks)
        assertEquals(0, state.partialBytesInChunk)
    }

    // 4. Mid-chunk boundary → resume from that chunk (resend partial)
    @Test
    fun midChunkBoundary() {
        val sizes = listOf(100, 200, 150)
        val state = ResumeCalculator.calculate(sizes, 150u) // 100 complete + 50 into second
        assertEquals(1, state.resumeFromChunk)
        assertEquals(1, state.completedChunks)
        assertEquals(50, state.partialBytesInChunk)
    }

    // 5. Single chunk transfer, zero bytes → resume from 0
    @Test
    fun singleChunkZeroBytes() {
        val sizes = listOf(256)
        val state = ResumeCalculator.calculate(sizes, 0u)
        assertEquals(0, state.resumeFromChunk)
        assertEquals(0, state.completedChunks)
        assertEquals(0, state.partialBytesInChunk)
    }

    // 6. Single chunk transfer, all bytes → complete
    @Test
    fun singleChunkComplete() {
        val sizes = listOf(256)
        val state = ResumeCalculator.calculate(sizes, 256u)
        assertEquals(1, state.resumeFromChunk)
        assertEquals(1, state.completedChunks)
        assertEquals(0, state.partialBytesInChunk)
    }

    // 7. Variable chunk sizes (last chunk smaller)
    @Test
    fun variableChunkSizesLastSmaller() {
        val sizes = listOf(200, 200, 200, 50)
        // Receive first two chunks exactly
        val state = ResumeCalculator.calculate(sizes, 400u)
        assertEquals(2, state.resumeFromChunk)
        assertEquals(2, state.completedChunks)
        assertEquals(0, state.partialBytesInChunk)

        // Receive into the smaller last chunk
        val state2 = ResumeCalculator.calculate(sizes, 620u) // 200+200+200+20
        assertEquals(3, state2.resumeFromChunk)
        assertEquals(3, state2.completedChunks)
        assertEquals(20, state2.partialBytesInChunk)
    }

    // 8. bytesReceived exceeds total → resume is "complete" (no crash)
    @Test
    fun bytesReceivedExceedsTotal() {
        val sizes = listOf(100, 100)
        val state = ResumeCalculator.calculate(sizes, 9999u)
        assertEquals(2, state.resumeFromChunk)
        assertEquals(2, state.completedChunks)
        assertEquals(0, state.partialBytesInChunk)
    }

    // 9. bytesForChunks round-trip
    @Test
    fun bytesForChunksRoundTrip() {
        val sizes = listOf(100, 200, 150, 80)
        for (n in 0..sizes.size) {
            val bytes = ResumeCalculator.bytesForChunks(sizes, n)
            val state = ResumeCalculator.calculate(sizes, bytes)
            assertEquals(n, state.completedChunks, "round-trip failed for completedChunks=$n")
            assertEquals(0, state.partialBytesInChunk, "round-trip should have no partial bytes for n=$n")
        }
    }

    // 10. Empty chunk list → resume from 0
    @Test
    fun emptyChunkList() {
        val state = ResumeCalculator.calculate(emptyList(), 0u)
        assertEquals(0, state.resumeFromChunk)
        assertEquals(0, state.completedChunks)
        assertEquals(0, state.partialBytesInChunk)

        val state2 = ResumeCalculator.calculate(emptyList(), 100u)
        assertEquals(0, state2.resumeFromChunk)
        assertEquals(0, state2.completedChunks)
        assertEquals(0, state2.partialBytesInChunk)
    }

    // 11. Very large transfer (1000 chunks)
    @Test
    fun veryLargeTransfer() {
        val sizes = List(1000) { 512 }
        // Resume mid-way at chunk 500 boundary
        val state = ResumeCalculator.calculate(sizes, (500 * 512).toUInt())
        assertEquals(500, state.resumeFromChunk)
        assertEquals(500, state.completedChunks)
        assertEquals(0, state.partialBytesInChunk)

        // Resume mid-chunk 500 (1 byte in)
        val state2 = ResumeCalculator.calculate(sizes, (500 * 512 + 1).toUInt())
        assertEquals(500, state2.resumeFromChunk)
        assertEquals(500, state2.completedChunks)
        assertEquals(1, state2.partialBytesInChunk)
    }

    // 12. Partial bytes calculation with bytesForChunks
    @Test
    fun partialBytesCalculation() {
        val sizes = listOf(100, 200, 300)
        assertEquals(0u, ResumeCalculator.bytesForChunks(sizes, 0))
        assertEquals(100u, ResumeCalculator.bytesForChunks(sizes, 1))
        assertEquals(300u, ResumeCalculator.bytesForChunks(sizes, 2))
        assertEquals(600u, ResumeCalculator.bytesForChunks(sizes, 3))
        // Exceeding chunk count is clamped
        assertEquals(600u, ResumeCalculator.bytesForChunks(sizes, 99))
    }

    // 13. bytesForChunks with empty list
    @Test
    fun bytesForChunksEmptyList() {
        assertEquals(0u, ResumeCalculator.bytesForChunks(emptyList(), 0))
        assertEquals(0u, ResumeCalculator.bytesForChunks(emptyList(), 5))
    }

    // 14. First chunk boundary exactly
    @Test
    fun firstChunkBoundaryExact() {
        val sizes = listOf(50, 100, 150)
        val state = ResumeCalculator.calculate(sizes, 50u)
        assertEquals(1, state.resumeFromChunk)
        assertEquals(1, state.completedChunks)
        assertEquals(0, state.partialBytesInChunk)
    }
}
